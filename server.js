const express = require('express');
const http = require('http');
const path = require('path');
const { Server } = require('socket.io');
const { loadConfig, authorize, rtcConfig } = require('./access-config');
const config = loadConfig();
const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' }, maxHttpBufferSize: 64 * 1024 });
app.use(express.static(path.join(__dirname, 'public')));
const rooms = new Map();
io.on('connection', (socket) => {
  socket.emit('server-capabilities', { accessProtocol: 1 });
  let membership = null;
  let attempts = 0, windowStart = Date.now();
  function leaveRoom() {
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
  socket.on('join-room', (payload) => {
    if (Date.now() - windowStart >= 60000) { attempts = 0; windowStart = Date.now(); }
    if (++attempts > 10) { socket.emit('join-error', { code: 'RETRY_LATER' }); return; }
    const next = authorize(config, payload);
    if (!next) { socket.emit('join-error', { code: 'ACCESS_DENIED' }); return; }
    const members = rooms.get(next.key);
    if ((members?.size || 0) - (members?.has(socket.id) ? 1 : 0) >= next.max) {
      socket.emit('join-error', { code: 'ROOM_FULL' }); return;
    }
    leaveRoom(); membership = next;
    const userName = typeof payload.name === 'string' && payload.name.length <= 40 ? payload.name.trim() : '';
    socket.join(next.key);
    if (!rooms.has(next.key)) rooms.set(next.key, new Map());
    const users = rooms.get(next.key);
    // Ordered delivery is essential: configure TURN before creating any peer.
    socket.emit('rtc-config', rtcConfig(config, socket.id));
    socket.emit('existing-users', [...users].map(([id, name]) => ({ id, name })));
    users.set(socket.id, userName || `ゲスト${socket.id.slice(0, 4)}`);
    socket.to(next.key).emit('user-joined', { id: socket.id, name: users.get(socket.id) });
  });
  let lastRtcRequest = 0;
  socket.on('request-rtc-config', () => {
    if (active() && Date.now() - lastRtcRequest > 30000) {
      lastRtcRequest = Date.now(); socket.emit('rtc-config', rtcConfig(config, socket.id));
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
  socket.on('disconnect', () => clearInterval(expiryTimer));
});
const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`サーバー起動: port ${PORT}`));
