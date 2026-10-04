'use strict';
const crypto = require('node:crypto');
const PACKAGE = 'jp.es.staffintercom.preview';
function loadLicenseConfig(env = process.env) {
  const required = !['development', 'test'].includes(env.NODE_ENV) || env.REQUIRE_PLAY_LICENSE === 'true';
  const project = Number(env.PLAY_CLOUD_PROJECT_NUMBER || 0);
  const certificates = (env.PLAY_CERTIFICATE_SHA256 || '').split(',').filter(Boolean);
  if ((env.REQUIRE_PLAY_LICENSE && !['true', 'false'].includes(env.REQUIRE_PLAY_LICENSE)) ||
      (required && (!Number.isSafeInteger(project) || project <= 0 || !certificates.length ||
        certificates.some(c => !/^[A-Za-z0-9_-]{43}$/.test(c))))) {
    throw Error('Invalid Play licensing configuration');
  }
  return { required, project, certificates };
}
function binding(payload, nonce, socketId) {
  return crypto.createHash('sha256').update(JSON.stringify([nonce, socketId, PACKAGE,
    payload.storeId || '', payload.roomId, payload.name || '', payload.inviteCode || ''])).digest('base64url');
}
function challenge(payload, socketId, now = Date.now()) {
  const nonce = crypto.randomBytes(32).toString('base64url');
  return { nonce, hash: binding(payload, nonce, socketId), expires: now + 120000 };
}
function validVerdict(verdict, expectedHash, config, now = Date.now()) {
  const request = verdict?.requestDetails, app = verdict?.appIntegrity;
  const timestamp = Number(request?.timestampMillis);
  return request?.requestPackageName === PACKAGE && request.requestHash === expectedHash &&
    Number.isFinite(timestamp) && timestamp <= now + 10000 && timestamp >= now - 120000 &&
    app?.packageName === PACKAGE && app.appRecognitionVerdict === 'PLAY_RECOGNIZED' &&
    Array.isArray(app.certificateSha256Digest) && app.certificateSha256Digest.some(c => config.certificates.includes(c)) &&
    /^\d+$/.test(String(app.versionCode)) && Number(app.versionCode) >= 18 &&
    verdict?.accountDetails?.appLicensingVerdict === 'LICENSED';
}
let auth;
async function decodeToken(token) {
  const { GoogleAuth } = require('google-auth-library');
  auth ||= new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/playintegrity'] });
  const client = await auth.getClient();
  const response = await client.request({
    url: `https://playintegrity.googleapis.com/v1/${PACKAGE}:decodeIntegrityToken`,
    method: 'POST', data: { integrity_token: token }, timeout: 15000, retry: false
  });
  return response.data.tokenPayloadExternal;
}
async function verifyLicense(config, pending, payload, socketId, decode = decodeToken) {
  if (!pending || pending.expires <= Date.now() || typeof payload?.integrityToken !== 'string' ||
      payload.integrityToken.length < 20 || payload.integrityToken.length > 16000 ||
      binding(payload, pending.nonce, socketId) !== pending.hash) return false;
  try {
    const verdict = await decode(payload.integrityToken);
    return pending.expires > Date.now() && validVerdict(verdict, pending.hash, config);
  } catch (_) { return false; } // Never log token, credentials, Google errors or response bodies.
}
module.exports = { loadLicenseConfig, challenge, binding, validVerdict, verifyLicense };
