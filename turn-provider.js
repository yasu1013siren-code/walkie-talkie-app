'use strict';
const { rtcConfig } = require('./access-config');
function loadTurnProvider(env = process.env) {
  const provider = env.TURN_PROVIDER || 'rest';
  const key = env.CLOUDFLARE_TURN_KEY_ID || '', token = env.CLOUDFLARE_TURN_API_TOKEN || '';
  if (!['rest', 'cloudflare'].includes(provider) || (provider === 'cloudflare' &&
      (!/^[a-zA-Z0-9_-]{1,128}$/.test(key) || !token || env.TURN_SHARED_SECRET || env.TURN_URLS))) {
    throw Error('Invalid TURN provider configuration');
  }
  return { provider, key, token };
}
async function getRtcConfig(config, provider, socketId, request = fetch) {
  if (provider.provider === 'rest') return rtcConfig(config, socketId);
  const response = await request(`https://rtc.live.cloudflare.com/v1/turn/keys/${provider.key}/credentials/generate-ice-servers`, {
    method: 'POST', headers: { Authorization: `Bearer ${provider.token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ ttl: config.ttl }), signal: AbortSignal.timeout(10000)
  });
  if (!response.ok) throw Error('TURN unavailable');
  const result = await response.json();
  if (!Array.isArray(result.iceServers) || !result.iceServers.length || result.iceServers.length > 16) throw Error('TURN unavailable');
  let hasTurn = false;
  for (const server of result.iceServers) {
    const urls = typeof server.urls === 'string' ? [server.urls] : server.urls;
    if (!Array.isArray(urls) || !urls.length || urls.some(u => typeof u !== 'string' || !/^(stun|stuns|turn|turns):[^\s/@]+$/.test(u))) throw Error('TURN unavailable');
    if (urls.some(u => /^turns?:/.test(u))) {
      hasTurn = true;
      if (typeof server.username !== 'string' || !server.username || typeof server.credential !== 'string' || !server.credential) throw Error('TURN unavailable');
    }
  }
  if (!hasTurn) throw Error('TURN unavailable');
  return { iceServers: result.iceServers };
}
module.exports = { loadTurnProvider, getRtcConfig };
