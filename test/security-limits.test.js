'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { RateLimiter, validSignal } = require('../security-limits');
const { loadConfig, authorize } = require('../access-config');
test('process counters survive reconnect, expire, and deny overflowing storage', () => {
  const r = new RateLimiter({ limit: 2, globalLimit: 20, maxKeys: 1, windowMs: 1000 });
  assert.ok(r.take('ip', 1000)); assert.ok(r.take('ip', 1001));
  assert.equal(r.take('ip', 1002), false); // a new socket uses the same address
  assert.equal(r.take('other', 1003), false);
  assert.ok(r.take('other', 2001));
  const global = new RateLimiter({ globalLimit: 2 });
  assert.ok(global.take('one')); assert.ok(global.take('two')); assert.equal(global.take('three'), false);
});
test('strict signal schemas bound SDP/ICE and do not relay arbitrary objects', () => {
  assert.ok(validSignal({ type: 'offer', sdp: 'v=0' }));
  assert.ok(validSignal({ candidate: 'candidate:test', sdpMid: '0', sdpMLineIndex: 0, usernameFragment: 'test' }));
  for (const v of [null, [], {}, { type: 'offer' }, { type: 'offer', sdp: 'x'.repeat(50000) }, { candidate: 'x'.repeat(4097) }, { candidate: 'x', sdpMLineIndex: -1 }, { type: 'answer', sdp: 'test', key: 'private' }]) assert.equal(validSignal(v), false);
});
test('default/test modes cannot bypass access; oversized and malformed join fields deny', () => {
  for (const env of [{}, { NODE_ENV: 'test' }, { NODE_ENV: 'development' }]) assert.equal(authorize(loadConfig(env), { roomId: 'main' }), null);
  for (const p of [[], { roomId: 1 }, { roomId: 'x'.repeat(33) }, { roomId: 'main', storeId: {}, inviteCode: 'x'.repeat(43) }, { roomId: 'main', storeId: 'shop', inviteCode: 'x'.repeat(257) }]) assert.equal(authorize(loadConfig({}), p), null);
});
