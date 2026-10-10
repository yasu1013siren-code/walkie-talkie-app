package jp.es.staffintercom;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.session.*;
import android.os.*;
import android.view.KeyEvent;
import org.json.*;
import org.webrtc.*;
import org.webrtc.AudioTrack;
import org.webrtc.audio.JavaAudioDeviceModule;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URI;
import java.util.*;

/** Native audio and signaling owner; no WebView or Activity is needed for a joined session. */
public final class IntercomService extends Service {
    static final String JOIN = "jp.es.staffintercom.JOIN", TOGGLE = "jp.es.staffintercom.TOGGLE", STOP = "jp.es.staffintercom.STOP";
    private static final String SITE = BuildConfig.SERVER_URL;
    private static final String CHANNEL = "intercom-session";
    private static final int NOTICE = 2001;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private Socket socket;
    private OfflineSpeech speech;
    private ConversationStore conversation;
    private boolean transcribeEnabled, saveConversation, voiceCommands;
    private String speechState = "文字起こしOFF", startWord = "インカム開始", stopWord = "インカム停止";
    private final Map<String,String> partialText = new LinkedHashMap<>();
    private final ArrayDeque<String> recentText = new ArrayDeque<>();
    private long textSequence, lastVoiceCommand;
    private final Runnable voiceTimeout = () -> setTalking(false);
    void clearRecentConversation() { recentText.clear(); partialText.clear(); }
    String getSpeechState() {
        int open=0; for(Peer p:peers.values())if(p.textChannel!=null && p.textChannel.state()==DataChannel.State.OPEN)open++;
        return speechState + "\n文字通信：" + open + "/" + peers.size() + "台" + (speech==null?"":"\n"+speech.diagnostic());
    }
    String getConversation() {
        StringBuilder out = new StringBuilder(saveConversation ? conversation.text(100) : String.join("\n\n", recentText));
        for (String line : partialText.values()) out.append("\n").append(line).append(" …\n");
        return out.length() == 0 ? "まだ会話はありません" : out.toString();
    }
    void configureSpeech(boolean transcription, boolean save, boolean commands, String start, String stop) {
        transcribeEnabled = transcription; saveConversation = save; voiceCommands = commands;
        startWord = start; stopWord = stop;
        if (!transcription) partialText.clear();
        if (!commands) main.removeCallbacks(voiceTimeout);
        if (speech != null) speech.enable(transcription || commands);
    }
    private void ownText(String text, boolean complete, boolean sent, int epoch) {
        if (!joined || speech == null) return;
        int command = SpeechRules.command(text, startWord, stopWord);
        if (voiceCommands && speech.current(epoch) && command != 0) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastVoiceCommand >= 1500) {
                lastVoiceCommand = now;
                setTalking(command > 0);
                if (command > 0 && talking) { main.removeCallbacks(voiceTimeout); main.postDelayed(voiceTimeout, 30000); speechState = "音声操作：送信開始（30秒で自動停止）"; }
                else if (command < 0) speechState = "音声操作：送信停止";
            }
            return;
        }
        if (!transcribeEnabled || !sent || (voiceCommands && command != 0)) return;
        showText("self", userName.isEmpty() ? "自分" : userName, text, complete);
        JSONObject packet = json("v", 1, "seq", ++textSequence, "text", ConversationStore.clean(text,2000), "final", complete);
        byte[] bytes = packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (Peer peer : peers.values()) if (peer.textChannel != null && peer.textChannel.state() == DataChannel.State.OPEN && peer.textChannel.bufferedAmount() < 32768)
            peer.textChannel.send(new DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), false));
    }
    private void showText(String id, String speaker, String text, boolean complete) {
        text = ConversationStore.clean(text,2000); speaker = ConversationStore.clean(speaker,40);
        if (!complete) { if (text.isEmpty()) partialText.remove(id); else partialText.put(id, speaker + "：" + text); return; }
        partialText.remove(id); if (text.isEmpty()) return;
        recentText.addLast(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN).format(new java.util.Date()) + " " + speaker + "\n" + text);
        while (recentText.size() > 100) recentText.removeFirst();
        if (saveConversation) conversation.add(speaker,text);
    }
    private void attachText(Peer peer, DataChannel channel) {
        if (channel == null) return;
        if (!peer.live() || !"intercom-text-v1".equals(channel.label())) { channel.close(); channel.dispose(); return; }
        if (peer.textChannel != null) { channel.close(); channel.dispose(); return; }
        peer.textChannel = channel;
        channel.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long previous) {}
            public void onStateChange() {}
            public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary || buffer.data.remaining() > 8192) return;
                byte[] bytes = new byte[buffer.data.remaining()]; buffer.data.get(bytes);
                main.post(() -> {
                    if (!peer.live() || !transcribeEnabled) return;
                    long now=SystemClock.elapsedRealtime(); if(now-peer.textWindow>=1000){peer.textWindow=now;peer.textCount=0;} if(++peer.textCount>12)return;
                    try {
                        JSONObject data=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                        if(data.optInt("v")!=1 || !(data.opt("text") instanceof String) || !(data.opt("final") instanceof Boolean))return;
                        long seq=data.optLong("seq",-1); if(seq<=peer.lastTextSequence)return;
                        String text=data.getString("text"); if(text.length()>2000)return; peer.lastTextSequence=seq;
                        showText(peer.id,peer.name,text,data.getBoolean("final"));
                    } catch(JSONException ignored) {}
                });
            }
        });
    }
    private String storeId = "", inviteCode = "";
    private boolean joinSent;
    private List<PeerConnection.IceServer> iceServers = RtcSettings.defaults();
    private final Runnable refreshIce = new Runnable() {
        public void run() {
            if (!joined) return;
            if (connected && socket != null) socket.emit("request-rtc-config");
            main.postDelayed(this, 600000);
        }
    };
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audioModule;
    private AudioSource source;
    private AudioTrack track;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private boolean hasFocus, joined, connected, talking, initialized, headsetCalls;
    private float receiveGain = 2f;
    private String headsetStatus = "S10：ボタン操作OFF";
    private String room = "", userName = "", status = "未参加", route = "音声出力：未接続";
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private long lastButton;
    private int generation;
    public final class LocalBinder extends Binder { IntercomService getService() { return IntercomService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    boolean isJoined() { return joined; }
    boolean isConnected() { return connected; }
    boolean isTalking() { return talking; }
    String getStatus() { return status; }
    String getRoute() { return route; }
    String getHeadsetStatus() { return headsetStatus; }
    boolean usesHeadsetCalls() { return headsetCalls; }
    float getReceiveGain() { return receiveGain; }
    void setReceiveGain(float value) {
        receiveGain = normalizeReceiveGain(value);
        getSharedPreferences("intercom", MODE_PRIVATE).edit().putFloat("receiveGain", receiveGain).apply();
        for (Peer peer : peers.values()) if (peer.remoteAudio != null) peer.remoteAudio.setVolume(receiveGain);
    }
    static float normalizeReceiveGain(float value) {
        return Float.isFinite(value) ? Math.max(1f, Math.min(3f, value)) : 2f;
    }
    private final IntercomCallService.Client callClient = new IntercomCallService.Client() {
        public boolean transmit(boolean value) { setTalking(value); return talking == value; }
        public boolean isTransmitting() { return talking; }
        public void status(String value) { headsetStatus = value; }
        public void restoreRoute() { if (joined) selectAudioRoute(); }
        public void interrupted() { interruptAudio(); }
    };
    void setHeadsetCalls(boolean value) {
        headsetCalls = value;
        if (joined && Build.VERSION.SDK_INT >= 30) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (value) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
            startForeground(NOTICE, notification(), types);
        }
        if (joined && value) IntercomCallService.enable(this, callClient, connected);
        else {
            IntercomCallService.disable(); headsetStatus = "S10：ボタン操作OFF";
            if (joined) { setTalking(false); main.postDelayed(callClient::restoreRoute, 400); }
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        receiveGain = normalizeReceiveGain(getSharedPreferences("intercom", MODE_PRIVATE).getFloat("receiveGain", 2f));
        conversation = new ConversationStore(this);
        android.content.SharedPreferences prefs=getSharedPreferences("intercom",MODE_PRIVATE);
        transcribeEnabled=prefs.getBoolean("transcribe",true);saveConversation=prefs.getBoolean("saveConversation",true);voiceCommands=prefs.getBoolean("voiceCommands",false);
        startWord=prefs.getString("startWord","インカム開始");stopWord=prefs.getString("stopWord","インカム停止");
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "インカム通話", NotificationManager.IMPORTANCE_LOW));
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(this::onAudioFocusChanged, main).build();
        mediaSession = new MediaSession(this, "StaffIntercomNative");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (key == null || !toggleKey(key.getKeyCode())) return super.onMediaButtonEvent(intent);
                if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) headsetToggle();
                return true;
            }
            @Override public void onPlay() { headsetToggle(); }
            @Override public void onPause() { headsetToggle(); }
            @Override public void onStop() { setTalking(false); }
        }, main);
        mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "スタッフインカム").build());
        audio.registerAudioDeviceCallback(deviceCallback, main);
    }
    boolean canUseAudio() { return !IntercomCallService.isInterrupted() && (hasFocus || IntercomCallService.ownsControlAudio()); }
    void onAudioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            hasFocus = false;
            if (!IntercomCallService.ownsControlAudio()) interruptAudio();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true;
            if (joined) { selectAudioRoute(); updateStatus(); }
        }
    }
    private void interruptAudio() {
        hasFocus = false; setTalking(false);
        if (joined) {
            status = "音声が他の通話に切り替わりました。通話終了後に接続を再確認してください";
            updateNotification();
        }
    }
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { if (joined) main.postDelayed(() -> { if (joined) selectAudioRoute(); }, 300); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { if (joined) { setTalking(false); main.postDelayed(() -> { if (joined) selectAudioRoute(); }, 300); } }
    };
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { leave(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { leave(); return START_NOT_STICKY; }
        if (TOGGLE.equals(intent.getAction())) { toggleTalking(); return START_NOT_STICKY; }
        if (JOIN.equals(intent.getAction()) && !joined) {
            String nextRoom = intent.getStringExtra("room");
            if (nextRoom == null || !nextRoom.matches("[a-zA-Z0-9_-]{1,32}") || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY; }
            storeId = intent.getStringExtra("storeId"); inviteCode = intent.getStringExtra("inviteCode");
            if (storeId == null || !storeId.matches("[a-zA-Z0-9_-]{1,32}") || inviteCode == null || inviteCode.length() < 32 || inviteCode.length() > 256) { storeId = ""; inviteCode = ""; stopSelf(); return START_NOT_STICKY; }
            room = nextRoom; userName = intent.getStringExtra("name");
            if (userName == null || userName.length() > 40) userName = "";
            status = "接続中…";
            try {
                if (Build.VERSION.SDK_INT >= 30) startForeground(NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NOTICE, notification());
                joined = true;
                wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StaffIntercom:session");
                wakeLock.acquire(); // Released on explicit leave, failure and service destruction.
                selectAudioRoute();
                initializeAudio(); setHeadsetCalls(intent.getBooleanExtra("headsetCalls", false)); connect(); updateNotification();
            } catch (Throwable error) {
                android.util.Log.e("StaffIntercom", "Session start failed");
                leave(); status = "参加できませんでした：" + error.getClass().getSimpleName();
            }
        }
        return START_NOT_STICKY; // Never restart a microphone without a fresh visible join action.
    }
    private void initializeAudio() {
        if (initialized) return;
        // Fail in Java before NetworkMonitor is called from JNI; a pending SecurityException
        // from a native callback can otherwise abort the process when the first peer joins.
        if (checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.CHANGE_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Native WebRTC requires network-state permissions");
        }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
        // Native ICE logs can include temporary credentials and connection addresses.
        Logging.enableLogToDebugOutput(Logging.Severity.LS_NONE);
        speech = new OfflineSpeech(this, new OfflineSpeech.Listener() {
            public void text(String text, boolean complete, boolean sent, int epoch) { main.post(() -> ownText(text,complete,sent,epoch)); }
            public void state(String text) { main.post(() -> speechState=text); }
        });
        speech.enable(transcribeEnabled || voiceCommands);
        audioModule = JavaAudioDeviceModule.builder(this).setSamplesReadyCallback(samples -> { OfflineSpeech current=speech; if(current!=null)current.samples(samples); }).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule();
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule).createPeerConnectionFactory();
        source = factory.createAudioSource(new MediaConstraints());
        track = factory.createAudioTrack("intercom-audio", source); track.setEnabled(false); initialized = true;
    }
    private void connect() {
        final int session = ++generation;
        IO.Options options = new IO.Options(); options.transports = new String[]{"websocket"}; options.reconnection = true;
        options.reconnectionDelay = 1000; options.reconnectionDelayMax = 5000; options.timeout = 20000;
        final Socket current = IO.socket(URI.create(SITE), options); socket = current;
        listen(current, session, Socket.EVENT_CONNECT, args -> { connected = false; closePeers(); setTalking(false); iceServers = RtcSettings.defaults(); joinSent = false;
            main.postDelayed(() -> {
                if (joined && socket == current && generation == session && current.connected() && !connected) { leave(); status = "参加確認がタイムアウトしました。設定と通信状態を確認してください"; }
            }, 90000); IntercomCallService.setConnected(false); status = "参加を確認中…"; updateNotification(); });
        listen(current, session, Socket.EVENT_DISCONNECT, args -> { connected = false; IntercomCallService.setConnected(false); setTalking(false); closePeers(); status = "通信が切れました。再接続中…"; updateNotification(); });
        listen(current, session, Socket.EVENT_CONNECT_ERROR, args -> { connected = false; IntercomCallService.setConnected(false); setTalking(false); status = "サーバー接続を再試行中…"; updateNotification(); });
        listen(current, session, "server-capabilities", args -> {
            JSONObject capabilities = (JSONObject) args[0];
            if (capabilities.optInt("accessProtocol") == 1 && !joinSent) emitJoin(current);
        });
        listen(current, session, "join-error", args -> {
            String code = ((JSONObject) args[0]).optString("code");
            leave(); status = "TURN_UNAVAILABLE".equals(code) ? "音声中継の準備に失敗しました。再参加してください" : "ROOM_FULL".equals(code) ? "ルームが満員です" : "RETRY_LATER".equals(code) ? "少し待ってから参加してください" : "参加できません。店舗ID・招待コード・有効期限を確認してください";
        });
        listen(current, session, "rtc-config", args -> {
            try {
                iceServers = RtcSettings.parse((JSONObject) args[0]);
                for (Peer peer : peers.values()) if (peer.pc != null) {
                    PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
                    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                    if (!peer.pc.setConfiguration(config)) { leave(); status = "接続設定を更新できません。再参加してください"; return; }
                }
            } catch (JSONException invalid) { leave(); status = "接続設定が不正です。管理者に確認してください"; }
        });
        listen(current, session, "existing-users", args -> {
            connected = true; IntercomCallService.setConnected(true);
            main.removeCallbacks(refreshIce); main.postDelayed(refreshIce, 600000);
            JSONArray users = (JSONArray) args[0];
            for (int i = 0; i < users.length(); i++) { JSONObject user = users.optJSONObject(i); if (user != null) createPeer(user.optString("id"), user.optString("name"), true); }
            updateStatus();
        });
        listen(current, session, "user-joined", args -> { JSONObject user = (JSONObject) args[0]; createPeer(user.optString("id"), user.optString("name"), false); updateStatus(); });
        listen(current, session, "user-left", args -> { Peer peer = peers.remove(((JSONObject) args[0]).optString("id")); if (peer != null) peer.close(); updateStatus(); });
        listen(current, session, "signal", args -> receiveSignal((JSONObject) args[0]));
        current.connect();
    }
    private void emitJoin(Socket current) {
        joinSent = true;
        current.emit("join-room", joinPayload());
    }
    private JSONObject joinPayload() {
        return json("roomId", room, "name", userName, "storeId", storeId, "inviteCode", inviteCode);
    }
    private interface Event { void accept(Object[] args); }
    private void listen(Socket current, int session, String event, Event callback) {
        current.on(event, args -> main.post(() -> {
            if (!joined || socket != current || generation != session) return;
            try { callback.accept(args); } catch (RuntimeException e) { android.util.Log.w("StaffIntercom", "Invalid signaling event"); }
        }));
    }
    private Peer createPeer(String id, String name, boolean offer) {
        if (id.isEmpty()) return null;
        if (peers.containsKey(id)) return peers.get(id);
        Peer p = new Peer(id, name); peers.put(id, p);
        List<PeerConnection.IceServer> ice = iceServers;
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        p.pc = factory.createPeerConnection(config, p);
        if (p.pc == null) { peers.remove(id); status = "音声接続を作成できませんでした"; updateNotification(); return null; }
        p.pc.addTrack(track, Collections.singletonList("intercom"));
        if (offer) attachText(p, p.pc.createDataChannel("intercom-text-v1", new DataChannel.Init()));
        if (offer) p.pc.createOffer(new SdpAdapter(p) {
            @Override void created(SessionDescription sdp) { setLocal(p, sdp); }
        }, new MediaConstraints());
        return p;
    }
    private void setLocal(Peer p, SessionDescription sdp) {
        p.pc.setLocalDescription(new SdpAdapter(p) {
            @Override void set() { sendSignal(p, json("type", sdp.type.canonicalForm(), "sdp", sdp.description)); }
        }, sdp);
    }
    private void sendSignal(Peer p, JSONObject data) { if (p.live() && connected) socket.emit("signal", json("to", p.id, "data", data)); }
    private void receiveSignal(JSONObject message) {
        String id = message.optString("from"); JSONObject data = message.optJSONObject("data"); if (data == null) return;
        Peer p = peers.get(id); if (p == null) p = createPeer(id, "ゲスト", false); if (p == null) return;
        final Peer peer = p;
        String type = data.optString("type");
        if ("offer".equals(type) || "answer".equals(type)) {
            SessionDescription sdp = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), data.optString("sdp"));
            peer.pc.setRemoteDescription(new SdpAdapter(peer) {
                @Override void set() {
                    peer.remoteReady = true; for (IceCandidate candidate : peer.pending) peer.pc.addIceCandidate(candidate); peer.pending.clear();
                    if ("offer".equals(type)) peer.pc.createAnswer(new SdpAdapter(peer) {
                        @Override void created(SessionDescription answer) { setLocal(peer, answer); }
                    }, new MediaConstraints());
                }
            }, sdp);
        } else if (data.has("candidate") && !data.isNull("candidate")) {
            IceCandidate candidate = new IceCandidate(data.optString("sdpMid", "0"), data.optInt("sdpMLineIndex", 0), data.optString("candidate"));
            if (peer.remoteReady) peer.pc.addIceCandidate(candidate); else if (peer.pending.size() < 256) peer.pending.add(candidate);
        }
    }
    private class SdpAdapter implements SdpObserver {
        final Peer peer; SdpAdapter(Peer p) { peer = p; }
        void created(SessionDescription sdp) {} void set() {}
        public void onCreateSuccess(SessionDescription sdp) { main.post(() -> { if (peer.live()) created(sdp); }); }
        public void onSetSuccess() { main.post(() -> { if (peer.live()) set(); }); }
        public void onCreateFailure(String error) { failed(error); }
        public void onSetFailure(String error) { failed(error); }
        void failed(String error) { main.post(() -> { if (peer.live()) { status = "音声接続に失敗しました。退出して再参加してください"; updateNotification(); android.util.Log.w("StaffIntercom", "Peer negotiation failed"); } }); }
    }
    private final class Peer implements PeerConnection.Observer {
        final String id, name; DataChannel textChannel; long lastTextSequence=-1,textWindow; int textCount; AudioTrack remoteAudio; PeerConnection pc; boolean remoteReady; final List<IceCandidate> pending = new ArrayList<>();
        Peer(String id, String name) { this.id = id; this.name = name; }
        boolean live() { return joined && peers.get(id) == this && pc != null; }
        void close() { partialText.remove(id); if(textChannel!=null){textChannel.unregisterObserver();textChannel.close();textChannel.dispose();textChannel=null;} remoteAudio = null; if (pc != null) { pc.close(); pc.dispose(); pc = null; } pending.clear(); }
        public void onIceCandidate(IceCandidate candidate) { main.post(() -> sendSignal(this, json("candidate", candidate.sdp, "sdpMid", candidate.sdpMid, "sdpMLineIndex", candidate.sdpMLineIndex))); }
        public void onConnectionChange(PeerConnection.PeerConnectionState state) {
            main.post(() -> { if (live() && state == PeerConnection.PeerConnectionState.FAILED) { status = "音声接続に失敗しました。ネットワークを確認し、再参加してください"; updateNotification(); } });
        }
        public void onSignalingChange(PeerConnection.SignalingState state) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
        public void onIceConnectionReceivingChange(boolean receiving) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        public void onAddStream(MediaStream stream) {}
        public void onRemoveStream(MediaStream stream) {}
        public void onDataChannel(DataChannel channel) { main.post(() -> attachText(this,channel)); }
        public void onRenegotiationNeeded() {}
        public void onTrack(RtpTransceiver transceiver) { onAddTrack(transceiver.getReceiver(), new MediaStream[0]); }
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            main.post(() -> {
                if (!live()) return;
                MediaStreamTrack received = receiver.track();
                if (received instanceof AudioTrack) {
                    remoteAudio = (AudioTrack) received;
                    remoteAudio.setVolume(receiveGain);
                }
            });
        }
    }
    void selectAudioRoute() {
        if (!joined || IntercomCallService.isInterrupted()) return;
        if (!canUseAudio()) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!canUseAudio()) { route = "音声を使用できません。他の通話が終了してから再確認してください"; updateNotification(); return; }
        try {
            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo selected = null;
                for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET) { selected = device; break; }
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET) selected = device;
                }
                if (selected == null) {
                    for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) selected = device;
                }
                boolean accepted = selected != null && audio.setCommunicationDevice(selected);
                AudioDeviceInfo actual = audio.getCommunicationDevice();
                route = "音声出力：" + (actual == null ? "切替待ち" : actual.getProductName()) + (accepted ? "" : "（切替を確認してください）");
            } else {
                boolean bluetooth = false;
                for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) bluetooth = true;
                if (bluetooth) { audio.startBluetoothSco(); audio.setBluetoothScoOn(true); audio.setSpeakerphoneOn(false); }
                else audio.setSpeakerphoneOn(true);
                route = bluetooth ? "音声出力：Bluetooth接続要求中" : "音声出力：端末スピーカー／有線イヤホン";
            }
        } catch (SecurityException e) { route = "Bluetoothの権限を許可し、接続を再確認してください"; }
        updateNotification();
    }
    void toggleTalking() { setTalking(!talking); }
    void setTalking(boolean value) {
        boolean next = value && joined && connected && track != null && canUseAudio();
        if (talking == next) return;
        talking = next; if(speech!=null)speech.transmission(next); if(!next)main.removeCallbacks(voiceTimeout); track.setEnabled(next);
        IntercomCallService.syncTalking(next);
        if (socket != null && connected) socket.emit("talking", next);
        updateStatus();
    }
    private void headsetToggle() { long now = SystemClock.elapsedRealtime(); if (now - lastButton < 350) return; lastButton = now; toggleTalking(); }
    private static boolean toggleKey(int code) { return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE; }
    private void updateStatus() { status = !joined ? "未参加" : !connected ? "再接続中…" : (talking ? "● 送信中" : "受信待機中") + "　ルーム " + room + "（" + (peers.size() + 1) + "人）"; updateNotification(); }
    private PendingIntent action(String action, int code) { return PendingIntent.getService(this, code, new Intent(this, IntercomService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle("スタッフインカム：" + room)
            .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_btn_speak_now, talking ? "送信を停止" : "送信を開始", action(TOGGLE, 1)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "退出", action(STOP, 2)).build()).build();
    }
    private void updateNotification() {
        if (mediaSession != null) {
            mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                .setState(joined ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED, 0, 1f).build()); mediaSession.setActive(joined);
        }
        if (joined) getSystemService(NotificationManager.class).notify(NOTICE, notification());
    }
    private static JSONObject json(Object... pairs) { JSONObject out = new JSONObject(); try { for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]); } catch (JSONException e) { throw new IllegalArgumentException(e); } return out; }
    private void closePeers() { List<Peer> old = new ArrayList<>(peers.values()); peers.clear(); for (Peer peer : old) peer.close(); }
    void leave() {
        main.removeCallbacks(refreshIce); main.removeCallbacks(voiceTimeout); if(speech!=null){speech.close();speech=null;} partialText.clear(); inviteCode = ""; storeId = ""; iceServers = RtcSettings.defaults();
        IntercomCallService.disable(); headsetStatus = "S10：ボタン操作OFF";
        setTalking(false); joined = false; connected = false; generation++;
        if (socket != null) { socket.emit("leave-room"); socket.off(); socket.disconnect(); socket = null; }
        closePeers();
        if (track != null) { track.dispose(); track = null; }
        if (source != null) { source.dispose(); source = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (audioModule != null) { audioModule.release(); audioModule = null; }
        initialized = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); wakeLock = null;
        audio.abandonAudioFocusRequest(focus); hasFocus = false;
        try { if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice(); else { audio.stopBluetoothSco(); audio.setBluetoothScoOn(false); } audio.setMode(AudioManager.MODE_NORMAL); } catch (RuntimeException ignored) {}
        status = "未参加"; route = "音声出力：未接続"; updateNotification(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() { leave(); audio.unregisterAudioDeviceCallback(deviceCallback); main.removeCallbacksAndMessages(null); mediaSession.release(); if(conversation!=null)conversation.close(); super.onDestroy(); }
}
