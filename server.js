const express = require('express');
const http = require('http');
const path = require('path');
const { Server } = require('socket.io');
const { loadConfig, authorize } = require('./access-config');
const { loadLicenseConfig, challenge, verifyLicense } = require('./play-license');
const { loadTurnProvider, getRtcConfig } = require('./turn-provider');
const config = loadConfig();
const license = loadLicenseConfig();
const turn = loadTurnProvider();
if (turn.provider === 'cloudflare' && config.ttl < 32400) throw Error('Cloudflare TURN TTL must cover the eight-hour session');
if (license.required && config.legacy) throw Error('Production requires ALLOW_LEGACY_ROOMS=false');
const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' }, maxHttpBufferSize: 64 * 1024 });
app.use(express.static(path.join(__dirname, 'public')));
const rooms = new Map();
io.on('connection', (socket) => {
  socket.emit('server-capabilities', { accessProtocol: 1, playLicenseRequired: license.required });
  let membership = null, pendingLicense = null, busy = false, revision = 0;
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
    revision++; pendingLicense = null;
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
    if (++attempts > 10 || busy) { socket.emit('join-error', { code: 'RETRY_LATER' }); return false; }
    return true;
  }
  socket.on('request-license-challenge', payload => {
    if (!license.required || !allowedAttempt()) return;
    if (!authorize(config, payload)) { socket.emit('join-error', { code: 'ACCESS_DENIED' }); return; }
    pendingLicense = challenge(payload, socket.id);
    socket.emit('license-challenge', { cloudProjectNumber: license.project, requestHash: pendingLicense.hash });
  });
  socket.on('join-room', async (payload) => {
    if (!allowedAttempt()) return;
    const operation = revision;
    if (license.required) {
      const pending = pendingLicense; pendingLicense = null; busy = true;
      const verified = await verifyLicense(license, pending, payload, socket.id);
      busy = false;
      if (!socket.connected || operation !== revision) return;
      if (!verified) { socket.emit('join-error', { code: 'PLAY_LICENSE_REQUIRED' }); return; }
    }
    const next = authorize(config, payload);
    if (!next) { socket.emit('join-error', { code: 'ACCESS_DENIED' }); return; }
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
    if (license.required) membership.expires = Math.min(membership.expires, Date.now() + 8 * 3600000);
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
  socket.on('signal', (payload) => {
    if (!payload || typeof payload !== 'object' || !active()) return;
    const { to, data } = payload;
    const target = io.sockets.sockets.get(to);
    if (typeof to !== 'string' || !rooms.get(membership.key)?.has(to) || !target?.data.isActive?.()) return;
    if (!data || typeof data !== 'object' || Array.isArray(data)) return;
    io.to(to).emit('signal', { from: socket.id, data });
  });
  socket.data.isActive = active;
  socket.on('talking', (isTalking) => {
    if (active() && typeof isTalking === 'boolean') socket.to(membership.key).emit('user-talking', { id: socket.id, talking: isTalking });
  });
  socket.on('disconnecting', leaveRoom);
  socket.on('disconnect', () => { cachedRtc = null; clearInterval(expiryTimer); });
});
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`サーバー起動: port ${PORT}`));
