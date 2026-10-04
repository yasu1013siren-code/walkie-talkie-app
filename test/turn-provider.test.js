const { test } = require('node:test');
const assert = require('node:assert/strict');
const { loadConfig } = require('../access-config');
const { loadTurnProvider, getRtcConfig } = require('../turn-provider');
test('Cloudflare provider uses backend authorization and returns short-lived ICE only', async () => {
  const provider = loadTurnProvider({ TURN_PROVIDER: 'cloudflare', CLOUDFLARE_TURN_KEY_ID: 'key', CLOUDFLARE_TURN_API_TOKEN: 'private-token' });
  const ice = { iceServers: [{ urls: ['turn:relay.example:3478?transport=udp', 'turns:relay.example:5349?transport=tcp'], username: 'temporary', credential: 'temporary-password' }] };
  const result = await getRtcConfig(loadConfig({}), provider, 'socket', async (url, options) => {
    assert.equal(url, 'https://rtc.live.cloudflare.com/v1/turn/keys/key/credentials/generate-ice-servers');
    assert.equal(options.headers.Authorization, 'Bearer private-token');
    assert.equal(JSON.parse(options.body).ttl, 3600);
    return { ok: true, json: async () => ice };
  });
  assert.deepEqual(result, ice); assert.ok(!JSON.stringify(result).includes(provider.token));
});
test('Cloudflare default TTL covers the bounded session; REST default remains unchanged', () => {
  assert.equal(loadConfig({ TURN_PROVIDER: 'cloudflare' }).ttl, 32400);
  assert.equal(loadConfig({}).ttl, 3600);
});
test('provider misconfiguration and unavailable or malformed credentials fail closed', async () => {
  assert.throws(() => loadTurnProvider({ TURN_PROVIDER: 'cloudflare' }));
  assert.throws(() => loadTurnProvider({ TURN_PROVIDER: 'unknown' }));
  const p = { provider: 'cloudflare', key: 'key', token: 'private' };
  for (const response of [{ ok: false }, { ok: true, json: async () => ({ iceServers: [] }) }, { ok: true, json: async () => ({ iceServers: [{ urls: 'https://bad' }] }) }, { ok: true, json: async () => ({ iceServers: [{ urls: 'turn:relay' }] }) }]) {
    await assert.rejects(getRtcConfig(loadConfig({}), p, 'socket', async () => response));
  }
  await assert.rejects(getRtcConfig(loadConfig({}), p, 'socket', async () => { throw Error('timeout'); }));
});
