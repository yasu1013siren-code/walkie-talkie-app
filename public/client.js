const socket = io();

let localStream = null;
let peers = {};        // id -> { pc, name }
let audioElements = {}; // id -> <audio>
let talking = false;

const roomInput = document.getElementById('roomInput');
const nameInput = document.getElementById('nameInput');
const joinBtn = document.getElementById('joinBtn');
const pttBtn = document.getElementById('pttBtn');
const leaveBtn = document.getElementById('leaveBtn');
const statusEl = document.getElementById('status');
const participantsEl = document.getElementById('participants');
const joinScreen = document.getElementById('joinScreen');
const talkScreen = document.getElementById('talkScreen');

const configuration = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ]
};

joinBtn.addEventListener('click', async () => {
  const roomId = roomInput.value.trim();
  const name = nameInput.value.trim();
  if (!roomId) {
    alert('ルームIDを入力してください');
    return;
  }

  try {
    localStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // 初期状態はミュート(PTTボタンを押した時だけ送信)
    localStream.getAudioTracks().forEach(track => (track.enabled = false));
  } catch (err) {
    alert('マイクへのアクセスが許可されませんでした。ブラウザの設定を確認してください。\n(' + err.message + ')');
    return;
  }

  joinScreen.classList.add('hidden');
  talkScreen.classList.remove('hidden');
  statusEl.textContent = `ルーム「${roomId}」に接続中...`;

  socket.emit('join-room', { roomId, name });
});

leaveBtn.addEventListener('click', () => {
  window.location.reload();
});

socket.on('existing-users', async (users) => {
  statusEl.textContent = `接続完了(参加者 ${users.length + 1}人)`;
  for (const user of users) {
    await createPeerConnection(user.id, user.name, true);
  }
});

socket.on('user-joined', ({ id, name }) => {
  createPeerConnection(id, name, false);
  updateStatusCount();
});

socket.on('signal', async ({ from, data }) => {
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
      console.error('ICE candidate エラー', e);
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
  };

  pc.onicecandidate = (event) => {
    if (event.candidate) {
      socket.emit('signal', { to: id, data: event.candidate });
    }
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
  if (!localStream || talking) return;
  talking = true;
  localStream.getAudioTracks().forEach(track => (track.enabled = true));
  pttBtn.classList.add('active');
  pttBtn.textContent = '🔴 送信中...';
  if (navigator.vibrate) navigator.vibrate(30);
  socket.emit('talking', true);
}

function stopTalking() {
  if (!localStream || !talking) return;
  talking = false;
  localStream.getAudioTracks().forEach(track => (track.enabled = false));
  pttBtn.classList.remove('active');
  pttBtn.textContent = '押しながら話す';
  socket.emit('talking', false);
}

pttBtn.addEventListener('mousedown', startTalking);
pttBtn.addEventListener('mouseup', stopTalking);
pttBtn.addEventListener('mouseleave', stopTalking);
pttBtn.addEventListener('touchstart', (e) => { e.preventDefault(); startTalking(); });
pttBtn.addEventListener('touchend', (e) => { e.preventDefault(); stopTalking(); });
pttBtn.addEventListener('touchcancel', (e) => { e.preventDefault(); stopTalking(); });
