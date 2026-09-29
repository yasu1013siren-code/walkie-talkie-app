const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { io } = require('socket.io-client');

function event(socket, name) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timeout: ${name}`)), 3000);
    socket.once(name, value => { clearTimeout(timer); resolve(value); });
  });
}

test('room membership, leave and signal isolation', async () => {
  const port = 20000 + Math.floor(Math.random() * 20000);
  const server = spawn(process.execPath, ['server.js'], { env: { ...process.env, PORT: String(port) } });
  const clients = [];
  try {
    await new Promise((resolve, reject) => {
      server.stdout.once('data', resolve);
      server.once('error', reject);
      server.once('exit', code => reject(new Error(`server exit ${code}`)));
    });
    const connect = async () => {
      const socket = io(`http://localhost:${port}`, { transports: ['websocket'] });
      clients.push(socket);
      await event(socket, 'connect');
      return socket;
    };
    const a = await connect();
    const b = await connect();
    const c = await connect();
    a.emit('join-room', { roomId: 'es', name: 'A' });
    assert.deepEqual(await event(a, 'existing-users'), []);
    b.emit('join-room', { roomId: 'other', name: 'B' });
    assert.deepEqual(await event(b, 'existing-users'), []);
    c.emit('join-room', { roomId: 'es', name: 'C' });
    assert.equal((await event(c, 'existing-users'))[0].id, a.id);
    const received = event(a, 'signal');
    c.emit('signal', { to: a.id, data: { type: 'offer' } });
    assert.equal((await received).from, c.id);
    c.emit('leave-room');
    await event(a, 'user-left');
    c.emit('signal', { to: a.id, data: { type: 'offer' } });
    const noSignal = await Promise.race([event(a, 'signal').then(() => false), new Promise(r => setTimeout(() => r(true), 200))]);
    assert.equal(noSignal, true);
    const noCrossRoom = await Promise.race([event(b, 'signal').then(() => false), new Promise(r => setTimeout(() => r(true), 200))]);
    assert.equal(noCrossRoom, true);
  } finally {
    clients.forEach(client => client.disconnect());
    server.kill();
  }
});
