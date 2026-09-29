const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');

test('headset media actions turn transmission on and off, and leaving clears handlers', async () => {
  const handlers = {};
  const elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      value: '', textContent: '', options: [], disabled: false,
      classList: { add() {}, remove() {} },
      addEventListener(name, callback) { this.events ??= {}; this.events[name] = callback; },
      replaceChildren(...items) { this.options = items; },
      add(item) { this.options.push(item); }
    });
    return elements.get(id);
  };
  const microphone = { enabled: false, stop() {} };
  const stream = { getAudioTracks: () => [microphone], getTracks: () => [microphone] };
  const socket = { connected: true, on() {}, emit() {} };
  let now = 1000;
  const context = {
    io: () => socket,
    document: { getElementById: element, addEventListener() {}, visibilityState: 'visible' },
    window: { addEventListener() {}, MediaMetadata: class {} },
    navigator: {
      mediaDevices: { getUserMedia: async () => stream, enumerateDevices: async () => [] },
      mediaSession: { setActionHandler(action, callback) { handlers[action] = callback; } }
    },
    HTMLMediaElement: function () {},
    MediaMetadata: class {},
    Option: class { constructor(label, value) { this.label = label; this.value = value; } },
    Date: { now: () => (now += 500) },
    console
  };
  vm.runInNewContext(readFileSync('public/client.js', 'utf8'), context);
  element('roomInput').value = 'es';
  await element('joinBtn').events.click();
  assert.equal(microphone.enabled, false);
  handlers.play();
  assert.equal(microphone.enabled, true);
  handlers.pause();
  assert.equal(microphone.enabled, false);
  handlers.togglemicrophone();
  assert.equal(microphone.enabled, true);
  handlers.togglemicrophone();
  assert.equal(microphone.enabled, false);
  element('leaveBtn').events.click();
  assert.equal(handlers.play, null);
  assert.equal(microphone.enabled, false);
});
