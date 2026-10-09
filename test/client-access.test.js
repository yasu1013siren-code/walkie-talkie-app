const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
function harness() {
  const elements = new Map(), events = {}, emitted = [], timers = new Map(), configs = [], alerts = [];
  let nextTimer = 0, stopped = false;
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '', textContent: '', options: [], classList: { add() {}, remove() {} },
      addEventListener(name, callback) { this.events ??= {}; this.events[name] = callback; },
      click() { this.events?.click?.(); }, replaceChildren() {}, appendChild() {}, add() {}
    });
    return elements.get(id);
  };
  const track = { enabled: false, stop() { stopped = true; } };
  const stream = { getAudioTracks: () => [track], getTracks: () => [track] };
  const socket = { connected: true, on(name, fn) { events[name] = fn; }, emit(name, value) { emitted.push({ name, value }); } };
  const context = {
    io: () => socket, document: { getElementById: element, createElement: () => element('new'), addEventListener() {}, visibilityState: 'visible' },
    window: { addEventListener() {} }, navigator: { mediaDevices: { getUserMedia: async () => stream, enumerateDevices: async () => [] } },
    HTMLMediaElement: function() {}, Option: class {}, Date, console,
    alert: message => alerts.push(message),
    setInterval() {}, setTimeout(fn) { timers.set(++nextTimer, fn); return nextTimer; }, clearTimeout(id) { timers.delete(id); },
    RTCPeerConnection: class {
      constructor(config) { configs.push(JSON.parse(JSON.stringify(config))); }
      addTrack() {} close() {} setConfiguration(config) { configs.push(JSON.parse(JSON.stringify(config))); }
      async createOffer() { return { type: 'offer', sdp: 'fake' }; }
      async setLocalDescription(offer) { this.localDescription = offer; }
    }
  };
  vm.runInNewContext(readFileSync('public/client.js', 'utf8'), context);
  element('roomInput').value = 'main'; element('storeInput').value = 'shopA'; element('inviteInput').value = 'x'.repeat(43);
  return { element, events, emitted, timers, configs, alerts, track, stopped: () => stopped, socket };
}
test('protected join waits for supported protocol, keeps invitation on reconnect, never sends it to old server', async () => {
  const h = harness(); await h.element('joinBtn').events.click();
  assert.equal(h.emitted.filter(x => x.name === 'join-room').length, 0);
  h.events['server-capabilities']({ accessProtocol: 1 });
  assert.equal(h.emitted.find(x => x.name === 'join-room').value.inviteCode, 'x'.repeat(43));
  h.events.disconnect(); h.events.connect();
  assert.equal(h.emitted.filter(x => x.name === 'join-room').length, 1);
  h.events['server-capabilities']({ accessProtocol: 1 });
  assert.equal(h.emitted.filter(x => x.name === 'join-room').length, 2);
  const old = harness(); await old.element('joinBtn').events.click();
  [...old.timers.values()][0]();
  assert.equal(old.emitted.filter(x => x.name === 'join-room').length, 0);
  assert.equal(old.stopped(), true); assert.equal(old.element('inviteInput').value, '');
});
test('authenticated ICE reaches new and existing peers; denial cleans up capture and invitation', async () => {
  const h = harness(); h.events['server-capabilities']({ accessProtocol: 1 }); await h.element('joinBtn').events.click();
  const config = { iceServers: [{ urls: ['turns:relay.example:5349'], username: 'temporary', credential: 'temporary' }] };
  h.events['rtc-config'](config);
  await h.events['existing-users']([{ id: 'peer', name: 'Peer' }]);
  assert.deepEqual(h.configs[0], config);
  const renewed = { iceServers: [{ urls: 'turn:relay.example:3478', username: 'renewed', credential: 'renewed' }] };
  h.events['rtc-config'](renewed); assert.deepEqual(h.configs[1], renewed);
  h.events['join-error']({ code: 'ACCESS_DENIED' });
  assert.equal(h.stopped(), true); assert.equal(h.track.enabled, false); assert.equal(h.element('inviteInput').value, '');
  assert.ok(h.alerts.length);
});

test('missing invite is refused before microphone acquisition; empty store never sends key', async () => {
  const h = harness(); h.element('inviteInput').value = '';
  await h.element('joinBtn').events.click();
  assert.equal(h.emitted.filter(x => x.name === 'join-room').length, 0);
  assert.ok(h.alerts.length);
  const other = harness(); other.element('storeInput').value = '';
  other.events['server-capabilities']({ accessProtocol: 1 });
  await other.element('joinBtn').events.click();
  assert.equal(other.emitted.filter(x => x.name === 'join-room').length, 0);
});
