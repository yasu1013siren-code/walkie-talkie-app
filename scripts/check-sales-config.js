'use strict';
const fs = require('node:fs');
const { loadConfig } = require('../access-config');
const { loadLicenseConfig } = require('../play-license');
const { loadTurnProvider } = require('../turn-provider');

// Configuration only: do not print secrets or claim external services were tested.
function checkSalesConfig(env = process.env, now = Date.now()) {
  const failures = [];
  if (env.NODE_ENV !== 'production') failures.push('NODE_ENV must be production');
  let access;
  try {
    access = loadConfig(env);
    if (access.legacy) failures.push('ALLOW_LEGACY_ROOMS must be false');
    const usable = [...access.rooms.values()].some(rule => rule.invites.some(invite => Date.parse(invite.expiresAt) > now));
    if (!usable) failures.push('ACCESS_POLICY_JSON needs a room with an unexpired invitation');
  } catch (_) { failures.push('Access configuration is invalid'); }
  try { if (!loadLicenseConfig(env).required) failures.push('Play licensing must be required'); }
  catch (_) { failures.push('Play project number or certificate configuration is invalid'); }
  try {
    const turn = loadTurnProvider(env);
    if (turn.provider !== 'cloudflare') failures.push('Low-cost deployment requires TURN_PROVIDER=cloudflare');
    if (access && access.ttl < 32400) failures.push('TURN credentials must cover the eight-hour session');
  } catch (_) { failures.push('Cloudflare TURN configuration is invalid'); }
  if (!env.GOOGLE_APPLICATION_CREDENTIALS || !fs.existsSync(env.GOOGLE_APPLICATION_CREDENTIALS)) {
    failures.push('Google credentials secret file is missing');
  }
  return failures;
}
if (require.main === module) {
  const failures = checkSalesConfig();
  if (failures.length) { failures.forEach(f => console.error(f)); process.exitCode = 1; }
  else console.log('Sales configuration checks passed; real purchase, relay and device tests still required.');
}
module.exports = { checkSalesConfig };
