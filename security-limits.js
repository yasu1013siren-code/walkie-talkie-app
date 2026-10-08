'use strict';
// Bounded process-wide counters survive Socket.IO reconnects. They deliberately
// do not use untrusted forwarding headers. Limits reset on restart; use edge
// quotas/shared storage before scaling to multiple instances.
class RateLimiter {
  constructor({ limit = 60, globalLimit = 300, windowMs = 60000, maxKeys = 4096 } = {}) {
    Object.assign(this, { limit, globalLimit, windowMs, maxKeys });
    this.entries = new Map(); this.globalStart = 0; this.globalCount = 0;
  }
  take(key, now = Date.now()) {
    if (now - this.globalStart >= this.windowMs) { this.globalStart = now; this.globalCount = 0; }
    if (++this.globalCount > this.globalLimit) return false;
    let entry = this.entries.get(key);
    if (!entry || now - entry.start >= this.windowMs) {
      for (const [id, value] of this.entries) if (now - value.start >= this.windowMs) this.entries.delete(id);
      if (!this.entries.has(key) && this.entries.size >= this.maxKeys) return false;
      entry = { start: now, count: 0 }; this.entries.set(key, entry);
    }
    return ++entry.count <= this.limit;
  }
}
function validSignal(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data)) return false;
  if (data.type === 'offer' || data.type === 'answer') {
    return Object.keys(data).every(k => ['type', 'sdp'].includes(k)) && typeof data.sdp === 'string' && data.sdp.length > 0 && Buffer.byteLength(data.sdp) <= 48 * 1024;
  }
  return Object.keys(data).every(k => ['candidate', 'sdpMid', 'sdpMLineIndex', 'usernameFragment'].includes(k)) &&
    (data.candidate === null || (typeof data.candidate === 'string' && data.candidate.length <= 4096)) &&
    (data.sdpMid == null || (typeof data.sdpMid === 'string' && data.sdpMid.length <= 256)) &&
    (data.sdpMLineIndex == null || (Number.isInteger(data.sdpMLineIndex) && data.sdpMLineIndex >= 0 && data.sdpMLineIndex <= 256)) &&
    (data.usernameFragment == null || (typeof data.usernameFragment === 'string' && data.usernameFragment.length <= 256));
}
module.exports = { RateLimiter, validSignal };
