const express = require('express');
const http = require('http');
const path = require('path');
const { Server } = require('socket.io');
const { loadConfig, authorize } = require('./access-config');
const { loadTurnProvider, getRtcConfig } = require('./turn-provider');
const config = loadConfig();
const turn = loadTurnProvider();
if (turn.provider === 'cloudflare' && config.ttl < 32400) throw Error('Cloudflare TURN TTL must cover the eight-hour session');
const app = express();
const server = http.createServer(app);
const { RateLimiter, validSignal } = require('./security-limits');
// Use the direct transport address; never trust client-supplied X-Forwarded-For.
// Behind a proxy this deliberately shares a limit; deploy edge limits for finer isolation.
const limiter = new RateLimiter();
const publicOrigin = process.env.PUBLIC_ORIGIN || 'https://walkie-talkie-app-42l7.onrender.com';
if (!/^https:\/\/[a-zA-Z0-9.-]+(?::[0-9]+)?$/.test(publicOrigin)) throw Error('Invalid PUBLIC_ORIGIN');
const io = new Server(server, {
  maxHttpBufferSize: 64 * 1024,
  cors: { origin: publicOrigin },
  allowRequest: (req, done) => done(null, !req.headers.origin || req.headers.origin === publicOrigin)
});
app.disable('x-powered-by');
app.use((_req, res, next) => {
  res.set({
    'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Permissions-Policy': 'microphone=(self), camera=(), geolocation=()',
    'Strict-Transport-Security': 'max-age=31536000',
    'Content-Security-Policy': `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self' ${publicOrigin.replace('https:', 'wss:')}; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`
  });
  next();
});
app.use(express.static(path.join(__dirname, 'public')));
const rooms = new Map();
io.on('connection', (socket) => {
  socket.emit('server-capabilities', { accessProtocol: 1, inviteRequired: true });
  let membership = null, busy = false, revision = 0;
  let attempts = 0, windowStart = Date.now();
  let cachedRtc = null, rtcExpires = 0;
  async function socketRtc() {
    // Reuse this socket's short-lived credential; do not generate unused keys every 10 minutes.
    if (turn.provider === 'cloudflare' && cachedRtc && Date.now() < rtcExpires) return cachedRtc;
    const rtc = await getRtcConfig(config, turn, socket.id);
    if (turn.provider === 'cloudflare') {
      cachedRtc = rtc; rtcExpires = Date.now() + (config.ttl - 600) * 1000;
    }
    return rtc;
  }
  function leaveRoom() {
    revision++;
    if (!membership) return;
    const key = membership.key;
    const members = rooms.get(key);
    members?.delete(socket.id);
    socket.to(key).emit('user-left', { id: socket.id });
    socket.leave(key);
    if (!members?.size) rooms.delete(key);
    membership = null;
  }
  function active() {
    if (!membership) return false;
    if (membership.expires <= Date.now()) {
      leaveRoom(); socket.emit('join-error', { code: 'ACCESS_DENIED' }); return false;
    }
    return true;
  }
  const expiryTimer = setInterval(active, 1000);
  expiryTimer.unref();
  function allowedAttempt() {
    if (Date.now() - windowStart >= 60000) { attempts = 0; windowStart = Date.now(); }
    if (++attempts > 10 || !limiter.take(socket.handshake.address) || busy) { socket.emit('join-error', { code: 'RETRY_LATER' }); return false; }
    return true;
  }
  socket.on('join-room', async (payload) => {
    if (!allowedAttempt()) return;
    const operation = revision;
    const next = authorize(config, payload);
    if (!next) { leaveRoom(); socket.emit('join-error', { code: 'ACCESS_DENIED' }); return; }
    const currentMembers = rooms.get(next.key);
    if ((currentMembers?.size || 0) - (currentMembers?.has(socket.id) ? 1 : 0) >= next.max) { socket.emit('join-error', { code: 'ROOM_FULL' }); return; }
    busy = true;
    let rtc;
    try { rtc = await socketRtc(); }
    catch (_) { busy = false; socket.emit('join-error', { code: 'TURN_UNAVAILABLE' }); return; }
    busy = false;
    if (!socket.connected || operation !== revision || next.expires <= Date.now()) return;
    const members = rooms.get(next.key);
    if ((members?.size || 0) - (members?.has(socket.id) ? 1 : 0) >= next.max) {
      socket.emit('join-error', { code: 'ROOM_FULL' }); return;
    }
    leaveRoom(); membership = next;
    membership.expires = Math.min(membership.expires, Date.now() + 8 * 3600000);
    const userName = typeof payload.name === 'string' && payload.name.length <= 40 ? payload.name.trim() : '';
    socket.join(next.key);
    if (!rooms.has(next.key)) rooms.set(next.key, new Map());
    const users = rooms.get(next.key);
    // Ordered delivery is essential: configure TURN before creating any peer.
    socket.emit('rtc-config', rtc);
    socket.emit('existing-users', [...users].map(([id, name]) => ({ id, name })));
    users.set(socket.id, userName || `ゲスト${socket.id.slice(0, 4)}`);
    socket.to(next.key).emit('user-joined', { id: socket.id, name: users.get(socket.id) });
  });
  let lastRtcRequest = 0;
  socket.on('request-rtc-config', async () => {
    if (active() && Date.now() - lastRtcRequest > 30000) {
      lastRtcRequest = Date.now();
      const previous = membership;
      try {
        const rtc = await socketRtc();
        if (socket.connected && membership === previous && active()) socket.emit('rtc-config', rtc);
      } catch (_) { if (membership === previous) { leaveRoom(); socket.emit('join-error', { code: 'TURN_UNAVAILABLE' }); } }
    }
  });
  socket.on('leave-room', leaveRoom);
  let signalCount = 0, signalStart = Date.now();
  function signalBudget() {
    if (Date.now() - signalStart >= 1000) { signalStart = Date.now(); signalCount = 0; }
    return ++signalCount <= 100;
  }
  socket.on('signal', (payload) => {
    if (!payload || typeof payload !== 'object' || !active()) return;
    const { to, data } = payload;
    const target = io.sockets.sockets.get(to);
    if (typeof to !== 'string' || !rooms.get(membership.key)?.has(to) || !target?.data.isActive?.()) return;
    if (!validSignal(data) || !signalBudget()) return;
    io.to(to).emit('signal', { from: socket.id, data });
  });
  socket.data.isActive = active;
  socket.on('talking', (isTalking) => {
    if (active() && typeof isTalking === 'boolean' && signalBudget()) socket.to(membership.key).emit('user-talking', { id: socket.id, talking: isTalking });
  });
  socket.on('disconnecting', leaveRoom);
  socket.on('disconnect', () => { cachedRtc = null; clearInterval(expiryTimer); });
});
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`サーバー起動: port ${PORT}`));
