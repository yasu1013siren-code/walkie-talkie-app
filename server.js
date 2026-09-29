const express = require('express');
const http = require('http');
const path = require('path');
const { Server } = require('socket.io');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: '*' }
});

app.use(express.static(path.join(__dirname, 'public')));

// roomId -> { socketId: name }
const rooms = {};

io.on('connection', (socket) => {
  let currentRoom = null;

  function leaveRoom() {
    if (!currentRoom || !rooms[currentRoom]) return;
    delete rooms[currentRoom][socket.id];
    socket.to(currentRoom).emit('user-left', { id: socket.id });
    socket.leave(currentRoom);
    if (Object.keys(rooms[currentRoom]).length === 0) delete rooms[currentRoom];
    currentRoom = null;
  }

  socket.on('join-room', ({ roomId, name } = {}) => {
    if (typeof roomId !== 'string' || !/^[a-zA-Z0-9_-]{1,32}$/.test(roomId)) return;
    if (typeof name !== 'string' || name.length > 40) name = '';
    leaveRoom();
    currentRoom = roomId;
    const userName = (name || '').trim() || `ゲスト${socket.id.slice(0, 4)}`;

    socket.join(roomId);
    if (!rooms[roomId]) rooms[roomId] = {};

    // 新規参加者に、既に部屋にいるユーザー一覧を送る
    const existingUsers = Object.entries(rooms[roomId]).map(([id, n]) => ({ id, name: n }));
    socket.emit('existing-users', existingUsers);

    rooms[roomId][socket.id] = userName;

    // 既存メンバーに新規参加を通知
    socket.to(roomId).emit('user-joined', { id: socket.id, name: userName });
  });

  socket.on('leave-room', leaveRoom);

  // WebRTCのオファー/アンサー/ICE candidateを中継するだけ(音声データ自体は通らない)
  socket.on('signal', ({ to, data } = {}) => {
    if (!currentRoom || !rooms[currentRoom]?.[socket.id] || !rooms[currentRoom]?.[to]) return;
    io.to(to).emit('signal', { from: socket.id, data });
  });

  // 送信中(PTTボタンを押している)状態を他メンバーに通知(UI表示用)
  socket.on('talking', (isTalking) => {
    if (currentRoom) {
      socket.to(currentRoom).emit('user-talking', { id: socket.id, talking: isTalking });
    }
  });

  socket.on('disconnecting', leaveRoom);
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`サーバー起動: port ${PORT}`);
});
