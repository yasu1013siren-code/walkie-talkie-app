const socket = io();

let localStream = null;
let peers = {};        // id -> { pc, name }
let audioElements = {}; // id -> <audio>
let talking = false;
let joined = false;
let playLicenseRequired = false;
let wakeLock = null;
let lastHeadsetAction = 0;
const headsetActions = ['play', 'pause', 'togglemicrophone', 'stop', 'hangup'];

const roomInput = document.getElementById('roomInput');
const nameInput = document.getElementById('nameInput');
const joinBtn = document.getElementById('joinBtn');
const pttBtn = document.getElementById('pttBtn');
const leaveBtn = document.getElementById('leaveBtn');
const statusEl = document.getElementById('status');
const participantsEl = document.getElementById('participants');
const joinScreen = document.getElementById('joinScreen');
const talkScreen = document.getElementById('talkScreen');
const audioOutput = document.getElementById('audioOutput');
const enableAudio = document.getElementById('enableAudio');
const audioHelp = document.getElementById('audioHelp');
const headsetStatus = document.getElementById('headsetStatus');

function notifyNativeJoined() {
  window.IntercomNative?.setJoined(joined && accepted && socket.connected);
}

// Called by the Android wrapper when its MediaSession receives a headset button.
window.intercomNativeToggle = () => handleHeadsetAction('togglemicrophone');
window.intercomNativeStop = () => stopTalking();

function updateMediaSession() {
  if (!('mediaSession' in navigator)) return;
  // The browser decides whether hardware buttons are delivered to this page.
  navigator.mediaSession.playbackState = joined ? (talking ? 'playing' : 'paused') : 'none';
  try { navigator.mediaSession.setMicrophoneActive?.(joined && talking); } catch (_) { /* optional API */ }
}

function handleHeadsetAction(action) {
  if (!joined || !localStream || !socket.connected) return;
  const now = Date.now();
  if (now - lastHeadsetAction < 350) return; // Some devices send two actions for one press.
  lastHeadsetAction = now;
  if (action === 'stop' || action === 'hangup') stopTalking();
  else if (action === 'togglemicrophone') talking ? stopTalking() : startTalking();
  else if (action === 'play') talking ? stopTalking() : startTalking();
  else if (action === 'pause') stopTalking();
  headsetStatus.textContent = `イヤホン操作を検出: ${talking ? '送信中（もう一度押すと停止）' : '待機中'}`;
}

function registerHeadsetControls() {
  if (window.IntercomNative) {
    headsetStatus.textContent = 'Androidアプリでイヤホンボタンを待機中。押すと送信開始、もう一度押すと停止します（対応機種のみ）。';
    return;
  }
  if (!('mediaSession' in navigator)) {
    headsetStatus.textContent = 'このブラウザはイヤホンボタン操作に対応していません。';
    return;
  }
  if ('MediaMetadata' in window) {
    navigator.mediaSession.metadata = new MediaMetadata({ title: 'スタッフインカム', artist: `ルーム ${roomInput.value.trim()}` });
  }
  let supported = 0;
  for (const action of headsetActions) {
    try {
      navigator.mediaSession.setActionHandler(action, () => handleHeadsetAction(action));
      supported++;
    } catch (_) { /* Action unsupported on this browser. */ }
  }
  headsetStatus.textContent = supported
    ? 'イヤホンの再生/停止ボタン: 1回で送信開始、もう1回で停止（端末によっては非対応）'
    : 'このブラウザはイヤホンボタン操作に対応していません。';
  updateMediaSession();
}

function clearHeadsetControls() {
  if (!('mediaSession' in navigator)) return;
  for (const action of headsetActions) {
    try { navigator.mediaSession.setActionHandler(action, null); } catch (_) { /* optional action */ }
  }
  navigator.mediaSession.metadata = null;
  updateMediaSession();
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(console.warn);

async function requestWakeLock() {
  if (!joined || document.visibilityState !== 'visible' || !('wakeLock' in navigator)) return;
  try { wakeLock = await navigator.wakeLock.request('screen'); } catch (_) { /* OS may deny it */ }
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') requestWakeLock();
  else stopTalking();
});

async function refreshAudioOutputs() {
  if (!navigator.mediaDevices?.enumerateDevices) return;
  const outputs = (await navigator.mediaDevices.enumerateDevices()).filter(d => d.kind === 'audiooutput');
  const previous = audioOutput.value;
  audioOutput.replaceChildren(new Option('端末の設定に従う', ''));
  outputs.forEach((d, i) => audioOutput.add(new Option(d.label || `音声出力 ${i + 1}`, d.deviceId)));
  if ([...audioOutput.options].some(o => o.value === previous)) audioOutput.value = previous;
  audioOutput.disabled = !HTMLMediaElement.prototype.setSinkId;
  audioHelp.textContent = audioOutput.disabled
    ? '音声の出力先はスマホの設定でBluetoothイヤホンを接続・選択してください。'
    : 'Bluetoothイヤホンをスマホに接続してから選んでください。';
}

async function enablePlayback() {
  try {
    await Promise.all(Object.values(audioElements).map(a => a.play()));
    enableAudio.textContent = '音声再生中';
  } catch (_) {
    enableAudio.textContent = '音声を再生する';
    audioHelp.textContent = '音声が聞こえない場合は、ここをタップしてください。';
  }
}

audioOutput.addEventListener('change', async () => {
  if (!HTMLMediaElement.prototype.setSinkId) return;
  try {
    await Promise.all(Object.values(audioElements).map(a => a.setSinkId(audioOutput.value)));
  } catch (_) { audioHelp.textContent = '出力先を変更できません。スマホの設定で選択してください。'; }
});
enableAudio.addEventListener('click', enablePlayback);
navigator.mediaDevices?.addEventListener?.('devicechange', () => refreshAudioOutputs().catch(console.warn));

let accepted = false;
let accessProtocol = false, joinSent = false, admissionTimer;
function submitJoin() {
  if (!joined || joinSent || !socket.connected) return;
  if (storeInput.value.trim() && !accessProtocol) {
    clearTimeout(admissionTimer);
    admissionTimer = setTimeout(() => {
      if (joined && !joinSent) { leaveBtn.click(); alert('このサーバーは店舗参加に対応していません。管理者に確認してください'); }
    }, 5000);
    return;
  }
  clearTimeout(admissionTimer); joinSent = true;
  socket.emit('join-room', joinPayload());
}
const storeInput = document.getElementById('storeInput');
const inviteInput = document.getElementById('inviteInput');
const joinPayload = () => ({ roomId: roomInput.value.trim(), name: nameInput.value.trim(), storeId: storeInput.value.trim(), inviteCode: inviteInput.value });
const defaultIceServers = () => [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ];
const configuration = { iceServers: defaultIceServers() };

joinBtn.addEventListener('click', async () => {
  if (playLicenseRequired) { alert('販売用ルームはGoogle Play購入済みAndroidアプリから参加してください'); return; }
  const roomId = roomInput.value.trim();
  const name = nameInput.value.trim();
  if (!roomId) {
    alert('ルームIDを入力してください');
    return;
  }
  if (!/^[a-zA-Z0-9_-]{1,32}$/.test(roomId)) {
    alert('ルームIDは半角英数字・_・- の32文字以内にしてください');
    return;
  }

  try {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // 初期状態はミュート(PTTボタンを押した時だけ送信)
    localStream.getAudioTracks().forEach(track => (track.enabled = false));
  } catch (err) {
    const nativeDetails = window.IntercomNative?.getMicrophoneDiagnostics?.();
    alert('マイクへのアクセスが許可されませんでした。\n(' + err.message + ')' +
      (nativeDetails ? '\n診断: ' + nativeDetails : '\nブラウザの設定を確認してください。'));
    return;
  }

  joined = true;
  notifyNativeJoined();
  registerHeadsetControls();
  await refreshAudioOutputs().catch(console.warn);
  requestWakeLock();

  joinScreen.classList.add('hidden');
  talkScreen.classList.remove('hidden');
  statusEl.textContent = `ルーム「${roomId}」に接続中...`;

  accepted = false;
  submitJoin();
});

leaveBtn.addEventListener('click', () => {
  stopTalking();
  joined = false;
  accepted = false; joinSent = false; clearTimeout(admissionTimer);
  inviteInput.value = "";
  configuration.iceServers = defaultIceServers();
  participantsEl.replaceChildren();
  notifyNativeJoined();
  clearHeadsetControls();
  wakeLock?.release().catch(() => {});
  Object.values(peers).forEach(({ pc }) => pc.close());
  Object.values(audioElements).forEach(audio => audio.remove());
  peers = {};
  audioElements = {};
  localStream?.getTracks().forEach(track => track.stop());
  localStream = null;
  socket.emit('leave-room');
  talkScreen.classList.add('hidden');
  joinScreen.classList.remove('hidden');
});

socket.on('disconnect', () => {
  accepted = false; joinSent = false; accessProtocol = false; clearTimeout(admissionTimer);
  configuration.iceServers = defaultIceServers();
  stopTalking();
  notifyNativeJoined();
  headsetStatus.textContent = '通信が切れました。イヤホンボタンで送信できません。';
  if (joined) {
    Object.values(peers).forEach(({ pc }) => pc.close());
    Object.values(audioElements).forEach(audio => audio.remove());
    peers = {};
    audioElements = {};
    participantsEl.replaceChildren();
    statusEl.textContent = '通信が切れました。再接続しています...';
  }
});
socket.on('connect', () => {
  joinSent = false; accessProtocol = false;
  if (joined) submitJoin();
  if (joined) registerHeadsetControls();
  notifyNativeJoined();
});

socket.on('existing-users', async (users) => {
  if (!joined) return;
  accepted = true;
  notifyNativeJoined();
  statusEl.textContent = `接続完了(参加者 ${users.length + 1}人)`;
  for (const user of users) {
    await createPeerConnection(user.id, user.name, true);
  }
});

socket.on('user-joined', ({ id, name }) => {
  if (!joined) return;
  createPeerConnection(id, name, false);
  updateStatusCount();
});

socket.on('signal', async ({ from, data }) => {
  if (!joined) return;
  let entry = peers[from];
  let pc = entry ? entry.pc : null;
  if (!pc) {
    pc = await createPeerConnection(from, 'ゲスト', false);
  }

  if (data.type === 'offer') {
    await pc.setRemoteDescription(new RTCSessionDescription(data));
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    socket.emit('signal', { to: from, data: pc.localDescription });
  } else if (data.type === 'answer') {
    await pc.setRemoteDescription(new RTCSessionDescription(data));
  } else if (data.candidate !== undefined) {
    try {
      await pc.addIceCandidate(new RTCIceCandidate(data));
    } catch (e) {
      console.error('ICE candidate エラー');
    }
  }
});

socket.on('user-left', ({ id }) => {
  if (peers[id]) {
    peers[id].pc.close();
    delete peers[id];
  }
  if (audioElements[id]) {
    audioElements[id].remove();
    delete audioElements[id];
  }
  removeParticipant(id);
  updateStatusCount();
});

socket.on('user-talking', ({ id, talking: isTalking }) => {
  const el = document.getElementById(`participant-${id}`);
  if (el) el.classList.toggle('talking', isTalking);
});

async function createPeerConnection(id, name, isInitiator) {
  const pc = new RTCPeerConnection(configuration);
  peers[id] = { pc, name };

  localStream.getTracks().forEach(track => pc.addTrack(track, localStream));

  pc.ontrack = (event) => {
    let audio = audioElements[id];
    if (!audio) {
      audio = document.createElement('audio');
      audio.autoplay = true;
      audio.playsInline = true;
      document.body.appendChild(audio);
      audioElements[id] = audio;
    }
    audio.srcObject = event.streams[0];
    if (audioOutput.value && audio.setSinkId) audio.setSinkId(audioOutput.value).catch(console.warn);
    audio.play().catch(() => {
      enableAudio.textContent = '音声を再生する';
      audioHelp.textContent = '音声の再生には「音声を再生する」をタップしてください。';
    });
  };

  pc.onicecandidate = (event) => {
    if (event.candidate) {
      socket.emit('signal', { to: id, data: event.candidate });
    }
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'failed') statusEl.textContent = '音声接続に失敗しました。ネットワークを確認してください。';
  };

  if (isInitiator) {
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    socket.emit('signal', { to: id, data: pc.localDescription });
  }

  addParticipant(id, name);
  return pc;
}

function addParticipant(id, name) {
  if (document.getElementById(`participant-${id}`)) return;
  const div = document.createElement('div');
  div.id = `participant-${id}`;
  div.className = 'participant';
  div.textContent = name;
  participantsEl.appendChild(div);
}

function removeParticipant(id) {
  const el = document.getElementById(`participant-${id}`);
  if (el) el.remove();
}

function updateStatusCount() {
  const count = Object.keys(peers).length + 1;
  statusEl.textContent = `接続完了(参加者 ${count}人)`;
}

function startTalking() {
  if (!joined || !accepted || !socket.connected || !localStream || talking) return;
  talking = true;
  localStream.getAudioTracks().forEach(track => (track.enabled = true));
  pttBtn.classList.add('active');
  pttBtn.textContent = '🔴 送信中...';
  if (navigator.vibrate) navigator.vibrate(30);
  socket.emit('talking', true);
  window.IntercomNative?.setTalking(true);
  updateMediaSession();
  headsetStatus.textContent = '送信中。停止するにはイヤホンボタンをもう一度押すか、画面のボタンを押してください。';
}

function stopTalking() {
  if (!localStream || !talking) return;
  talking = false;
  localStream.getAudioTracks().forEach(track => (track.enabled = false));
  pttBtn.classList.remove('active');
  pttBtn.textContent = '押しながら話す';
  socket.emit('talking', false);
  window.IntercomNative?.setTalking(false);
  updateMediaSession();
  headsetStatus.textContent = '待機中。イヤホンボタンで送信を開始できます（対応端末のみ）。';
}

pttBtn.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  pttBtn.setPointerCapture(e.pointerId);
  startTalking();
});
pttBtn.addEventListener('pointerup', stopTalking);
pttBtn.addEventListener('pointercancel', stopTalking);
pttBtn.addEventListener('lostpointercapture', stopTalking);
window.addEventListener('blur', stopTalking);

// Credentials stay in memory and are never cached by the service worker.
socket.on('rtc-config', config => {
  if (!joined || !Array.isArray(config?.iceServers)) return;
  configuration.iceServers = config.iceServers;
  try { Object.values(peers).forEach(({ pc }) => pc.setConfiguration(configuration)); }
  catch (_) { leaveBtn.click(); alert('音声接続設定を更新できませんでした。再参加してください'); }
});
socket.on('join-error', error => {
  leaveBtn.click();
  alert(error?.code === 'ROOM_FULL' ? 'ルームが満員です' : error?.code === 'RETRY_LATER' ? '少し待ってから参加してください' : '参加できません。店舗ID・ルームID・招待コードや有効期限を確認してください');
});
setInterval(() => { if (joined && accepted && socket.connected) socket.emit('request-rtc-config'); }, 600000);

socket.on('server-capabilities', capability => {
  playLicenseRequired = !!capability.playLicenseRequired;
  if (playLicenseRequired) {
    leaveBtn.click();
    alert('販売用ルームはGoogle Play購入済みAndroidアプリから参加してください');
    return;
  }
  accessProtocol = capability?.accessProtocol === 1;
  if (joined) submitJoin();
});
