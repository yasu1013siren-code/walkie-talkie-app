const { test } = require('node:test');
const assert = require('node:assert/strict');
const { loadLicenseConfig, challenge, validVerdict, verifyLicense } = require('../play-license');
const cert = 'A'.repeat(43);
const config = loadLicenseConfig({ NODE_ENV: 'production', PLAY_CLOUD_PROJECT_NUMBER: '123456', PLAY_CERTIFICATE_SHA256: cert });
const payload = { storeId: 'shop', roomId: 'main', name: 'staff', inviteCode: 'x'.repeat(32), integrityToken: 't'.repeat(40) };
function verdict(hash) { return {
  requestDetails: { requestPackageName: 'jp.es.staffintercom.preview', requestHash: hash, timestampMillis: String(Date.now()) },
  appIntegrity: { packageName: 'jp.es.staffintercom.preview', appRecognitionVerdict: 'PLAY_RECOGNIZED', certificateSha256Digest: [cert], versionCode: '18' },
  accountDetails: { appLicensingVerdict: 'LICENSED' }
}; }
test('production cannot disable licensing or omit project/certificates', () => {
  assert.throws(() => loadLicenseConfig({ NODE_ENV: 'production', REQUIRE_PLAY_LICENSE: 'false' }));
  assert.equal(loadLicenseConfig({ NODE_ENV: 'production', REQUIRE_PLAY_LICENSE: 'false', PLAY_CLOUD_PROJECT_NUMBER: '123456', PLAY_CERTIFICATE_SHA256: cert }).required, true);
  assert.throws(() => loadLicenseConfig({}));
  assert.equal(loadLicenseConfig({ NODE_ENV: 'development' }).required, false);
  assert.equal(loadLicenseConfig({ NODE_ENV: 'test' }).required, false);
});
test('only licensed recognized package, certificate, version, binding and fresh verdict accepted', () => {
  const original = verdict('hash'); assert.ok(validVerdict(original, 'hash', config));
  const rejected = [null, {}, ...['UNLICENSED', 'UNEVALUATED', undefined].map(x => ({ ...original, accountDetails: { appLicensingVerdict: x } })),
    { ...original, appIntegrity: { ...original.appIntegrity, certificateSha256Digest: ['wrong'] } },
    { ...original, appIntegrity: { ...original.appIntegrity, packageName: 'other' } },
    { ...original, appIntegrity: { ...original.appIntegrity, versionCode: '17' } },
    { ...original, appIntegrity: { ...original.appIntegrity, appRecognitionVerdict: 'UNRECOGNIZED_VERSION' } },
    ...[{ requestHash: 'other' }, { requestPackageName: 'other' }, { timestampMillis: '0' }, { timestampMillis: String(Date.now() + 20000) }].map(x => ({ ...original, requestDetails: { ...original.requestDetails, ...x } }))];
  for (const value of rejected) assert.equal(Boolean(validVerdict(value, 'hash', config)), false);
});
test('challenge binds socket and all join fields; expired, missing token, Google failure deny', async () => {
  const pending = challenge(payload, 'socket');
  const decode = async () => verdict(pending.hash);
  assert.equal(await verifyLicense(config, pending, payload, 'socket', decode), true);
  for (const [p, id] of [[{ ...payload, roomId: 'other' }, 'socket'], [{ ...payload, inviteCode: 'other' }, 'socket'], [payload, 'other'], [{ ...payload, integrityToken: '' }, 'socket'], [null, 'socket']]) {
    assert.equal(await verifyLicense(config, pending, p, id, decode), false);
  }
  assert.equal(await verifyLicense(config, { ...pending, expires: 0 }, payload, 'socket', decode), false);
  assert.equal(await verifyLicense(config, null, payload, 'socket', decode), false);
  assert.equal(await verifyLicense(config, pending, payload, 'socket', async () => { throw Error('private'); }), false);
});
test('production real socket denies tokenless admission and never delivers TURN/peers', async () => {
  const { spawn } = require('node:child_process');
  const { io } = require('socket.io-client');
  const crypto = require('node:crypto');
  const code = 'x'.repeat(32), port = 51000 + Math.floor(Math.random() * 5000);
  const policy = JSON.stringify({ shop: { rooms: { main: { maxParticipants: 3, invites: [{ sha256: crypto.createHash('sha256').update(code).digest('hex'), expiresAt: '2099-01-01T00:00:00Z' }] } } } });
  const server = spawn(process.execPath, ['server.js'], { env: { ...process.env, NODE_ENV: 'production', REQUIRE_PLAY_LICENSE: 'false', ALLOW_LEGACY_ROOMS: 'false', ACCESS_POLICY_JSON: policy, PLAY_CLOUD_PROJECT_NUMBER: '123456', PLAY_CERTIFICATE_SHA256: cert, PORT: String(port) } });
  const socket = io(`http://127.0.0.1:${port}`, { autoConnect: false, transports: ['websocket'], reconnectionDelay: 50 });
  const wait = name => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('socket event timeout')), 5000);
    socket.once(name, x => { clearTimeout(timer); resolve(x); });
  });
  try {
    let rtc = false, peers = false;
    socket.on('rtc-config', () => rtc = true); socket.on('existing-users', () => peers = true);
    const capabilities = wait('server-capabilities'); socket.connect();
    assert.equal((await capabilities).playLicenseRequired, true);
    let denied = wait('join-error'); socket.emit('join-room', payload);
    assert.equal((await denied).code, 'PLAY_LICENSE_REQUIRED');
    const issued = wait('license-challenge'); socket.emit('request-license-challenge', payload);
    assert.match((await issued).requestHash, /^[A-Za-z0-9_-]{43}$/);
    denied = wait('join-error'); socket.emit('join-room', { ...payload, integrityToken: '' });
    assert.equal((await denied).code, 'PLAY_LICENSE_REQUIRED');
    denied = wait('join-error'); socket.emit('join-room', payload);
    assert.equal((await denied).code, 'PLAY_LICENSE_REQUIRED');
    assert.equal(rtc, false); assert.equal(peers, false);
  } finally { socket.disconnect(); server.kill(); }
});
