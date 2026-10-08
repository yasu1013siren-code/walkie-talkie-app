const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { io } = require('socket.io-client');
const { loadConfig, authorize, rtcConfig } = require('../access-config');
const code = crypto.randomBytes(32).toString('base64url');
const digest = crypto.createHash('sha256').update(code).digest('hex');
function policy(expiresAt = '2099-01-01T00:00:00Z') {
  return JSON.stringify(Object.fromEntries(['shopA', 'shopB', 'legacy'].map(store => [store, { rooms: { main: { maxParticipants: 2, invites: [{ sha256: digest, expiresAt }] } } }])));
}
const payload = (storeId = 'shopA', inviteCode = code) => ({ storeId, roomId: 'main', inviteCode, name: 'staff' });
const environment = () => ({ ACCESS_POLICY_JSON: policy(), TURN_URLS: '["turn:relay.example:3478?transport=udp","turns:relay.example:5349?transport=tcp"]', TURN_SHARED_SECRET: crypto.randomBytes(32).toString('hex'), ALLOW_LEGACY_ROOMS: 'false' });
function event(socket, name) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.off(name, listener); reject(Error(`Timeout ${name}`)); }, 5000);
    const listener = data => { clearTimeout(timer); resolve(data); };
    socket.once(name, listener);
  });
}
async function absent(socket, name, action) {
  let received = false;
  const listener = () => { received = true; };
  socket.on(name, listener); action();
  await new Promise(resolve => setTimeout(resolve, 150));
  socket.off(name, listener); assert.equal(received, false);
}
test('configuration fails closed without echoing secrets; invites scoped and expiring; TURN HMAC matches', () => {
  const env = environment(); const config = loadConfig(env);
  assert.equal(authorize(config, { roomId: 'main' }), null);
  assert.equal(authorize(config, payload('unknown')), null);
  assert.equal(authorize(config, payload('shopA', 'x'.repeat(43))), null);
  assert.equal(authorize(config, { ...payload(), roomId: 'other' }), null);
  assert.equal(authorize(loadConfig({ ACCESS_POLICY_JSON: policy('2000-01-01T00:00:00Z') }), payload()), null);
  assert.ok(authorize(config, payload()));
  for (const bad of [{ ACCESS_POLICY_JSON: 'secret-bearing-invalid-json' }, { TURN_URLS: env.TURN_URLS }, { ...env, TURN_URLS: '["https://bad.example"]' }, { ...env, TURN_CREDENTIAL_TTL: 'NaN' }]) {
    assert.throws(() => loadConfig(bad), error => error.message === 'Invalid access/TURN configuration; check deployment settings');
  }
  const rtc = rtcConfig(config, 'client', 1000000);
  assert.equal(rtc.iceServers[2].username, '4600:client');
  assert.equal(rtc.iceServers[2].credential, crypto.createHmac('sha1', env.TURN_SHARED_SECRET).update('4600:client').digest('base64'));
  assert.ok(!JSON.stringify(rtc).includes(env.TURN_SHARED_SECRET));
  assert.equal(rtcConfig(loadConfig({}), 'client').iceServers.length, 2);
  assert.equal(authorize(loadConfig({}), { roomId: 'main' }), null);
  assert.throws(() => loadConfig({ ALLOW_LEGACY_ROOMS: 'true' }));
});

test('real sockets: admission, ICE before peers, capacity, tenant isolation, rejoin, refresh, limits', async () => {
  const env = environment(); const port = 40000 + Math.floor(Math.random() * 10000);
  const server = spawn(process.execPath, ['server.js'], { env: { ...process.env, NODE_ENV: 'test', ...env, PORT: String(port) } });
  let logs = ''; server.stderr.on('data', data => logs += data); server.stdout.on('data', data => logs += data);
  const clients = [];
  try {
    await new Promise((resolve, reject) => { server.stdout.once('data', resolve); server.once('error', reject); server.once('exit', () => reject(Error('server failed'))); });
    async function connect() { const socket = io(`http://localhost:${port}`, { transports: ['websocket'], reconnection: false }); clients.push(socket); await event(socket, 'connect'); return socket; }
    async function join(socket, value) { const users = event(socket, 'existing-users'); socket.emit('join-room', value); return users; }
    async function denied(socket, value, expected) { const failure = event(socket, 'join-error'); socket.emit('join-room', value); assert.equal((await failure).code, expected); }
    const a = await connect(), b = await connect(), c = await connect(), d = await connect();
    await absent(d, 'rtc-config', () => d.emit('request-rtc-config'));
    await denied(d, null, 'ACCESS_DENIED');
    await denied(d, payload('shopA', 'wrong'.repeat(10)), 'ACCESS_DENIED');
    await denied(d, { roomId: 'main' }, 'ACCESS_DENIED');
    await absent(d, 'rtc-config', () => d.emit('join-room', payload('shopA', 'z'.repeat(43))));
    const order = []; a.on('rtc-config', () => order.push('ice')); a.on('existing-users', () => order.push('users'));
    const rtc = event(a, 'rtc-config'); assert.deepEqual(await join(a, payload()), []);
    assert.deepEqual(order, ['ice', 'users']); assert.equal((await rtc).iceServers.length, 3);
    assert.equal((await join(b, payload()))[0].id, a.id);
    assert.deepEqual(await join(c, payload('shopB')), []);
    await denied(d, payload(), 'ROOM_FULL');
    const received = event(a, 'signal'); b.emit('signal', { to: a.id, data: { type: 'offer', sdp: 'test' } }); assert.equal((await received).from, b.id);
    await absent(c, 'signal', () => a.emit('signal', { to: c.id, data: { type: 'offer', sdp: 'test' } }));
    await absent(a, 'signal', () => d.emit('signal', { to: a.id, data: { type: 'offer', sdp: 'test' } }));
    await absent(a, 'signal', () => b.emit('signal', { to: a.id, data: { type: 'offer', sdp: 'x'.repeat(50000) } }));
    await absent(a, 'signal', () => b.emit('signal', { to: a.id, data: { type: 'offer', sdp: 'test', injected: true } }));
    const refresh = event(a, 'rtc-config'); a.emit('request-rtc-config'); assert.equal((await refresh).iceServers.length, 3);
    const left = event(a, 'user-left'); b.emit('leave-room'); await left;
    assert.equal((await join(b, payload()))[0].id, a.id);
    for (let i = 0; i < 5; i++) await denied(d, payload('unknown'), 'ACCESS_DENIED');
    await denied(d, payload('unknown'), 'RETRY_LATER');
    assert.ok(!logs.includes(code)); assert.ok(!logs.includes(env.TURN_SHARED_SECRET));
  } finally { clients.forEach(c => c.disconnect()); server.kill(); }
});

test('active membership is removed when the invite expires', async () => {
  const port = 50000 + Math.floor(Math.random() * 5000);
  const server = spawn(process.execPath, ['server.js'], { env: { ...process.env, NODE_ENV: 'test', PORT: String(port), ACCESS_POLICY_JSON: policy(new Date(Date.now() + 2200).toISOString()), ALLOW_LEGACY_ROOMS: 'false', TURN_URLS: '[]', TURN_SHARED_SECRET: '' } });
  let socket;
  try {
    await new Promise((resolve, reject) => { server.stdout.once('data', resolve); server.once('error', reject); server.once('exit', () => reject(Error('server failed'))); });
    socket = io(`http://localhost:${port}`, { transports: ['websocket'] }); await event(socket, 'connect');
    const users = event(socket, 'existing-users'); socket.emit('join-room', payload()); await users;
    const denied = await event(socket, 'join-error'); assert.equal(denied.code, 'ACCESS_DENIED');
    await absent(socket, 'rtc-config', () => socket.emit('request-rtc-config'));
  } finally { socket?.disconnect(); server.kill(); }
});

test('unconfigured deployment denies admission/TURN and serves protection headers; reconnect cannot reset rate limits', async () => {
  const port = 55000 + Math.floor(Math.random() * 5000);
  const server = spawn(process.execPath, ['server.js'], { env: { ...process.env, PORT: String(port), ACCESS_POLICY_JSON: '{}', TURN_URLS: '[]', TURN_SHARED_SECRET: '', ALLOW_LEGACY_ROOMS: 'false' } });
  const clients = [];
  try {
    await new Promise((resolve, reject) => { server.stdout.once('data', resolve); server.once('error', reject); server.once('exit', () => reject(Error('server failed'))); });
    const res = await fetch(`http://127.0.0.1:${port}/`);
    assert.equal(res.status, 200); assert.equal(res.headers.get('x-content-type-options'), 'nosniff');
    assert.ok(res.headers.get('content-security-policy').includes("frame-ancestors 'none'"));
    assert.equal(res.headers.get('x-powered-by'), null);
    let configs = 0, peers = 0;
    for (let j = 0; j < 6; j++) {
      const socket = io(`http://127.0.0.1:${port}`, { transports: ['websocket'], reconnection: false }); clients.push(socket); await event(socket, 'connect');
      socket.on('rtc-config', () => configs++); socket.on('existing-users', () => peers++);
      for (let i = 0; i < 10; i++) {
        const denied = event(socket, 'join-error'); socket.emit('join-room', payload());
        assert.equal((await denied).code, 'ACCESS_DENIED');
      }
      socket.disconnect();
    }
    const next = io(`http://127.0.0.1:${port}`, { transports: ['websocket'], reconnection: false }); clients.push(next); await event(next, 'connect');
    const denied = event(next, 'join-error'); next.emit('join-room', payload());
    assert.equal((await denied).code, 'RETRY_LATER');
    await absent(next, 'rtc-config', () => next.emit('request-rtc-config'));
    assert.equal(configs, 0); assert.equal(peers, 0);
    const badOrigin = io(`http://127.0.0.1:${port}`, { transports: ['websocket'], reconnection: false, extraHeaders: { Origin: 'https://untrusted.example' } }); clients.push(badOrigin);
    await event(badOrigin, 'connect_error'); assert.equal(badOrigin.connected, false);
  } finally { clients.forEach(c => c.disconnect()); server.kill(); }
});
