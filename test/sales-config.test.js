'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { checkSalesConfig } = require('../scripts/check-sales-config');
const now = Date.parse('2026-10-04T00:00:00Z');
function configured() {
  return {
    NODE_ENV: 'production', ALLOW_LEGACY_ROOMS: 'false', TURN_PROVIDER: 'cloudflare',
    CLOUDFLARE_TURN_KEY_ID: 'test-key', CLOUDFLARE_TURN_API_TOKEN: 'test-only-secret',
    PLAY_CLOUD_PROJECT_NUMBER: '1234', PLAY_CERTIFICATE_SHA256: 'A'.repeat(43),
    GOOGLE_APPLICATION_CREDENTIALS: __filename,
    ACCESS_POLICY_JSON: JSON.stringify({ store: { rooms: { room: { maxParticipants: 5,
      invites: [{ sha256: 'a'.repeat(64), expiresAt: '2026-10-05T00:00:00Z' }] } } } })
  };
}
test('sales preflight accepts complete settings without external calls', () => {
  assert.deepEqual(checkSalesConfig(configured(), now), []);
});
test('sales preflight rejects missing or expired invitations and nonproduction', () => {
  for (const change of [{ NODE_ENV: 'development' }, { ACCESS_POLICY_JSON: '{}' },
    { ALLOW_LEGACY_ROOMS: 'true' }, { GOOGLE_APPLICATION_CREDENTIALS: '/nonexistent/test-only' }]) {
    assert.ok(checkSalesConfig({ ...configured(), ...change }, now).length);
  }
  assert.ok(checkSalesConfig(configured(), now + 2 * 86400000).length);
});
test('sales preflight errors never expose secret values', () => {
  const marker = 'DO-NOT-PRINT-SECRET';
  const failures = checkSalesConfig({ ...configured(), ACCESS_POLICY_JSON: marker,
    PLAY_CERTIFICATE_SHA256: marker, CLOUDFLARE_TURN_KEY_ID: marker + '/',
    GOOGLE_APPLICATION_CREDENTIALS: marker }, now);
  assert.ok(failures.length >= 4);
  assert.ok(!JSON.stringify(failures).includes(marker));
});
