'use strict';
const crypto = require('node:crypto');
const ID = /^[a-zA-Z0-9_-]{1,32}$/;
const STUN = [{ urls: 'stun:stun.l.google.com:19302' }, { urls: 'stun:stun1.l.google.com:19302' }];
function loadConfig(env = process.env) {
  // Configuration errors must never echo secret-bearing environment values.
  try {
    if ((env.ACCESS_POLICY_JSON || '').length > 1024 * 1024) throw Error();
    const policies = JSON.parse(env.ACCESS_POLICY_JSON || '{}');
    if (!policies || Array.isArray(policies) || typeof policies !== 'object') throw Error();
    const rooms = new Map();
    for (const [storeId, store] of Object.entries(policies)) {
      if (!ID.test(storeId) || !store || typeof store.rooms !== 'object' || Array.isArray(store.rooms)) throw Error();
      for (const [roomId, rule] of Object.entries(store.rooms)) {
        if (!ID.test(roomId) || !rule || !Array.isArray(rule.invites) || !Number.isInteger(rule.maxParticipants) || rule.maxParticipants < 1 || rule.maxParticipants > 50) throw Error();
        for (const invite of rule.invites) {
          if (!invite || !/^[a-f0-9]{64}$/.test(invite.sha256) || typeof invite.expiresAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(invite.expiresAt) || !Number.isFinite(Date.parse(invite.expiresAt))) throw Error();
        }
        rooms.set(JSON.stringify([storeId, roomId]), rule);
      }
    }
    if (env.ALLOW_LEGACY_ROOMS && env.ALLOW_LEGACY_ROOMS !== 'false') throw Error();
    const urls = JSON.parse(env.TURN_URLS || '[]');
    if (!Array.isArray(urls) || urls.some(u => typeof u !== 'string' || !/^turns?:[^\s/@]+(?::\d+)?(?:\?transport=(?:udp|tcp))?$/.test(u))) throw Error();
    const secret = env.TURN_SHARED_SECRET || '';
    if (Boolean(urls.length) !== Boolean(secret) || (secret && secret.length < 32)) throw Error();
    const ttl = Number(env.TURN_CREDENTIAL_TTL || (env.TURN_PROVIDER === 'cloudflare' ? 32400 : 3600));
    if (!Number.isInteger(ttl) || ttl < 1200 || ttl > 86400) throw Error();
    return { rooms, urls, secret, ttl };
  } catch (_) { throw new Error('Invalid access/TURN configuration; check deployment settings'); }
}
function authorize(config, payload, now = Date.now()) {
  if (!payload || Array.isArray(payload) || typeof payload !== 'object' || !ID.test(payload.roomId || '') || typeof payload.roomId !== 'string') return null;
  if (payload.name !== undefined && (typeof payload.name !== 'string' || payload.name.length > 40)) return null;
  const { storeId, roomId, inviteCode } = payload;
  if (storeId === undefined || storeId === '') return null;
  if (typeof storeId !== 'string' || !ID.test(storeId) || typeof inviteCode !== 'string' || inviteCode.length < 32 || inviteCode.length > 256) return null;
  const key = JSON.stringify([storeId, roomId]);
  const rule = config.rooms.get(key);
  if (!rule) return null;
  const digest = crypto.createHash('sha256').update(inviteCode).digest();
  const invite = rule.invites.find(i => crypto.timingSafeEqual(digest, Buffer.from(i.sha256, 'hex')) && Date.parse(i.expiresAt) > now);
  return invite ? { key, expires: Date.parse(invite.expiresAt), max: rule.maxParticipants } : null;
}
function rtcConfig(config, socketId, now = Date.now()) {
  const iceServers = STUN.map(x => ({ ...x }));
  if (config.urls.length) {
    const username = `${Math.floor(now / 1000) + config.ttl}:${socketId}`;
    iceServers.push({ urls: config.urls, username, credential: crypto.createHmac('sha1', config.secret).update(username).digest('base64') });
  }
  return { iceServers };
}
module.exports = { loadConfig, authorize, rtcConfig };
