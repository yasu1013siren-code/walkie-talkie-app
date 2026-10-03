'use strict';
// Explicitly write secrets outside the repository; never put them on stdout.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const [destination, expiresAt] = process.argv.slice(2);
const root = path.resolve(__dirname, '..');
if (!destination || !expiresAt || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(expiresAt) || !(Date.parse(expiresAt) > Date.now())) {
  console.error('Usage: node scripts/create-invite.js /private/path/invite.json FUTURE-UTC-TIMESTAMP'); process.exit(1);
}
const target = path.resolve(destination);
if (target === root || target.startsWith(root + path.sep)) {
  console.error('Destination must be outside the repository'); process.exit(1);
}
const inviteCode = crypto.randomBytes(32).toString('base64url');
try {
  fs.writeFileSync(target, JSON.stringify({ inviteCode, rule: { sha256: crypto.createHash('sha256').update(inviteCode).digest('hex'), expiresAt } }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  console.log('Private invite file created; code was not printed.');
} catch (_) { console.error('Cannot create private invite file (existing files are never overwritten)'); process.exit(1); }
