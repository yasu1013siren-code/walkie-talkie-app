package jp.es.staffintercom;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.telecom.*;
import java.util.*;

/** Optional S10 HFP controls: answer starts transmission, disconnect stops and re-arms. */
public final class IntercomCallService extends ConnectionService {
    interface Client {
        boolean transmit(boolean enabled);
        boolean isTransmitting();
        void status(String text);
        void restoreRoute();
        void interrupted();
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String CHANNEL = "intercom-s10-controls", TOKEN = "intercom-control-token";
    private static final int NOTICE = 2002;
    private static Context context;
    private static Client client;
    private static ControlConnection connection;
    private static boolean enabled, ready, interrupted;
    private static int serial, pendingToken;
    private static final Runnable ARM = IntercomCallService::arm;
    private static final Runnable TIMEOUT = () -> {
        if (pendingToken != 0) {
            closeConnection(DisconnectCause.ERROR);
            say("S10：登録が完了しません。ボタン操作を入れ直してください");
        }
    };
    static void enable(Context value, Client listener, boolean connected) {
        disable(); context = value.getApplicationContext(); client = listener; enabled = true; ready = connected; interrupted = false;
        if (ready) arm(); else say("S10：ルーム接続待ち");
    }
    static void disable() {
        enabled = false; ready = false; interrupted = false;
        MAIN.removeCallbacks(ARM); MAIN.removeCallbacks(TIMEOUT);
        closeConnection(DisconnectCause.LOCAL);
        client = null;
    }
    static void setConnected(boolean value) {
        if (!enabled || interrupted) return;
        ready = value;
        if (!ready) {
            MAIN.removeCallbacks(ARM); closeConnection(DisconnectCause.LOCAL); say("S10：再接続待ち");
        } else if (connection == null && pendingToken == 0) arm();
    }
    // Telecom takes audio focus for our own ringing/active control call. A normal
    // AudioManager loss during this period is not an unrelated telephone call.
    static boolean isInterrupted() { return enabled && interrupted; }
    static boolean ownsControlAudio() {
        return enabled && ready && !interrupted && (pendingToken != 0 || connection != null);
    }
    @Override public void onConnectionServiceFocusGained() {
        if (ownsControlAudio() && client != null) client.restoreRoute();
    }
    @Override public void onConnectionServiceFocusLost() {
        // A callback after our own disconnect is expected. A live call losing
        // Telecom focus is an actual interruption: do not re-arm or auto-send.
        if (ownsControlAudio()) {
            interrupted = true; ready = false;
            MAIN.removeCallbacks(ARM);
            closeConnection(DisconnectCause.LOCAL);
            if (client != null) client.interrupted();
            say("S10：別の通話で中断。通話終了後に操作をOFF→ONしてください");
        }
        connectionServiceFocusReleased();
    }
    static void syncTalking(boolean talking) {
        if (!enabled || !ready) return;
        if (talking) {
            if (connection != null && connection.getState() == Connection.STATE_RINGING) {
                connection.setActive(); say("S10：ボタンで送信停止できます"); cancelNotice();
            }
        } else if (connection != null && connection.getState() == Connection.STATE_ACTIVE) {
            closeConnection(DisconnectCause.LOCAL); scheduleArm();
        } else if (connection == null && pendingToken == 0) scheduleArm();
    }
    private static void say(String text) { if (client != null) client.status(text); }
    private static void scheduleArm() {
        if (!enabled || !ready) return;
        MAIN.removeCallbacks(ARM); MAIN.postDelayed(ARM, 900);
        say("S10：次の操作を準備中…");
    }
    private static void arm() {
        if (!enabled || !ready || client == null || connection != null || pendingToken != 0) return;
        try {
            NotificationManager notices = context.getSystemService(NotificationManager.class);
            if (!notices.areNotificationsEnabled()) { say("S10：通知を許可してから操作を有効にしてください"); return; }
            TelecomManager manager = context.getSystemService(TelecomManager.class);
            if (manager == null) { say("S10：この端末では通話操作を利用できません"); return; }
            PhoneAccountHandle handle = new PhoneAccountHandle(new ComponentName(context, IntercomCallService.class), "intercom-s10");
            manager.registerPhoneAccount(PhoneAccount.builder(handle, "スタッフインカム S10操作")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(Collections.singletonList(PhoneAccount.SCHEME_SIP)).build());
            if (!manager.isIncomingCallPermitted(handle)) {
                say("S10：通話操作を登録できません。他の通話終了後、操作を入れ直してください"); return;
            }
            pendingToken = ++serial;
            Bundle extras = new Bundle(); extras.putInt(TOKEN, pendingToken);
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.parse("sip:ptt@intercom.invalid"));
            MAIN.postDelayed(TIMEOUT, 15000);
            say("S10：ボタン操作を登録中…");
            manager.addNewIncomingCall(handle, extras);
        } catch (RuntimeException e) {
            closeConnection(DisconnectCause.ERROR); say("S10：登録できませんでした（" + e.getClass().getSimpleName() + "）");
            android.util.Log.w("IntercomS10", "Telecom registration failed", e);
        }
    }
    private static int token(ConnectionRequest request) {
        Bundle extras = request.getExtras();
        if (extras == null) return 0;
        if (extras.containsKey(TOKEN)) return extras.getInt(TOKEN);
        Bundle incoming = extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
        return incoming == null ? 0 : incoming.getInt(TOKEN);
    }
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        int id = token(request);
        if (!enabled || !ready || pendingToken == 0 || id != pendingToken || connection != null)
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.CANCELED));
        pendingToken = 0; MAIN.removeCallbacks(TIMEOUT);
        ControlConnection created = new ControlConnection(); connection = created;
        created.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        created.setConnectionCapabilities(Connection.CAPABILITY_MUTE);
        created.setAudioModeIsVoip(true);
        created.setAddress(Uri.parse("sip:ptt@intercom.invalid"), TelecomManager.PRESENTATION_ALLOWED);
        created.setCallerDisplayName("インカム送信待機", TelecomManager.PRESENTATION_ALLOWED);
        created.setInitializing(); created.setRinging();
        if (client.isTransmitting()) {
            created.setActive(); say("S10：ボタンで送信停止できます");
        } else say("S10：待機中（ボタンで送信開始）");
        return created;
    }
    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle account, ConnectionRequest request) {
        if (pendingToken != 0 && token(request) == pendingToken) {
            closeConnection(DisconnectCause.ERROR); say("S10：Androidが通話操作を拒否しました。操作を入れ直してください");
        }
    }
    private static void cancelNotice() { if (context != null) context.getSystemService(NotificationManager.class).cancel(NOTICE); }
    private static void closeConnection(int cause) {
        ++serial; pendingToken = 0; MAIN.removeCallbacks(TIMEOUT);
        ControlConnection old = connection; connection = null;
        if (old != null) { old.setDisconnected(new DisconnectCause(cause)); old.destroy(); }
        cancelNotice();
        Client owner = client;
        MAIN.postDelayed(() -> {
            if (!interrupted && client == owner && owner != null) owner.restoreRoute();
        }, 400);
    }
    private static void showIncoming() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "S10ボタン操作待機", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null); channel.enableVibration(false); manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(context, 10, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent transmit = PendingIntent.getService(context, 11, new Intent(context, IntercomService.class).setAction(IntercomService.TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("インカム送信待機").setContentText("S10の通話ボタンを押すと送信を開始します")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "送信開始", transmit).build()).build();
        manager.notify(NOTICE, notice);
    }
    private static final class ControlConnection extends Connection {
        private boolean bluetoothRequested;
        private boolean current() { return enabled && ready && connection == this && client != null; }
        @Override public void onShowIncomingCallUi() {
            if (!current()) return;
            try { showIncoming(); } catch (RuntimeException e) { closeConnection(DisconnectCause.ERROR); say("S10：待機通知を表示できません"); }
        }
        @Override public void onAnswer() { answer(); }
        @Override public void onAnswer(int videoState) { answer(); }
        private void answer() {
            if (!current() || getState() != STATE_RINGING) return;
            setActive(); cancelNotice();
            if (client.transmit(true)) say("S10：応答を受信・送信中（もう一度押すと停止）");
            else { closeConnection(DisconnectCause.LOCAL); say("S10：送信を開始できません。接続を確認してください"); scheduleArm(); }
        }
        @Override public void onDisconnect() { stop(DisconnectCause.LOCAL); }
        @Override public void onReject() { stop(DisconnectCause.REJECTED); }
        @Override public void onAbort() { stop(DisconnectCause.CANCELED); }
        private void stop(int cause) {
            if (!current()) return;
            Client owner = client;
            closeConnection(cause); owner.transmit(false);
            MAIN.postDelayed(() -> { if (enabled && client == owner) owner.restoreRoute(); }, 400);
            scheduleArm();
        }
        @Override public void onMuteStateChanged(boolean muted) { if (current() && muted) client.transmit(false); }
        @Override public void onAvailableCallEndpointsChanged(List<CallEndpoint> endpoints) {
            if (Build.VERSION.SDK_INT < 34 || !current() || bluetoothRequested) return;
            for (CallEndpoint endpoint : endpoints) {
                if (endpoint.getEndpointType() != CallEndpoint.TYPE_BLUETOOTH) continue;
                bluetoothRequested = true;
                requestCallEndpointChange(endpoint, MAIN::post, new OutcomeReceiver<Void, CallEndpointException>() {
                    @Override public void onResult(Void result) {}
                    @Override public void onError(CallEndpointException error) { bluetoothRequested = false; if (current()) say("S10：音声経路を確認してください"); }
                });
                break;
            }
        }
    }
}
