package jp.es.staffintercom;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import java.util.function.Consumer;

/** Isolated local Telecom test. No network call or microphone capture is started. */
public final class TestCallService extends ConnectionService {
    private static final String CHANNEL = "intercom-telecom-test";
    private static final int NOTIFICATION = 1901;
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static TestConnection connection;
    private static boolean pending;
    private static Context appContext;
    private static Consumer<String> listener;
    static String status = "未開始";
    static int answers;
    static int disconnects;
    static int muteCallbacks;
    static int muteChanges;
    static Boolean muted;
    static String lastMuteChange = "未観測";
    static String callState() {
        if (pending) return "作成待ち";
        if (connection == null) return "終了/未開始";
        return Connection.stateToString(connection.getState());
    }
    private static void observeMute(boolean value, String source) {
        muteCallbacks++;
        Boolean previous = muted;
        muted = value;
        if (previous == null) {
            log("ミュート初期状態=" + (value ? "ON" : "OFF") + " / " + source);
        } else if (previous.booleanValue() != value) {
            muteChanges++;
            lastMuteChange = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
                .format(new java.util.Date()) + " " + (value ? "ON" : "OFF") + " / " + source;
            log("ミュート変更=" + (value ? "ON" : "OFF") + " / 通話状態=" + callState() +
                " / " + source + "（操作元は特定できません）");
        }
    }
    private static final Runnable TIMEOUT = () -> finish("180秒のテスト終了", DisconnectCause.LOCAL);

    static void setListener(Consumer<String> value) { listener = value; }
    private static void log(String event) {
        status = event;
        if (listener != null) listener.accept("Telecom: " + event);
    }
    static boolean isTesting() { return pending || connection != null; }
    static void start(Context context) {
        if (isTesting()) { log("テスト着信は既に存在します"); return; }
        answers = 0; disconnects = 0; muteCallbacks = 0; muteChanges = 0;
        muted = null; lastMuteChange = "未観測";
        appContext = context.getApplicationContext();
        try {
            TelecomManager manager = context.getSystemService(TelecomManager.class);
            if (manager == null) { log("Telecomが利用できません"); return; }
            PhoneAccountHandle handle = new PhoneAccountHandle(
                new ComponentName(context, TestCallService.class), "s10-diagnostic");
            manager.registerPhoneAccount(PhoneAccount.builder(handle, "インカム操作テスト")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setSupportedUriSchemes(java.util.Collections.singletonList(PhoneAccount.SCHEME_SIP))
                .build());
            if (!manager.isIncomingCallPermitted(handle)) {
                log("Androidがテスト着信を許可しません（他の通話等を確認）"); return;
            }
            Bundle extras = new Bundle();
            extras.putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.parse("sip:s10-test@intercom.invalid"));
            pending = true;
            HANDLER.postDelayed(TIMEOUT, 180000);
            log("着信登録要求 / Connection作成待ち");
            manager.addNewIncomingCall(handle, extras);
        } catch (RuntimeException error) {
            pending = false;
            HANDLER.removeCallbacks(TIMEOUT);
            log("登録失敗: " + error.getClass().getSimpleName() + " " + error.getMessage());
        }
    }
    static void answerFromScreen() {
        if (connection == null || connection.getState() != Connection.STATE_RINGING) {
            log("画面応答を見送り: 着信待機ではありません"); return;
        }
        connection.activate("画面/通知から応答");
    }
    static void finish(String origin, int cause) {
        pending = false;
        HANDLER.removeCallbacks(TIMEOUT);
        TestConnection current = connection;
        connection = null;
        if (current != null) {
            current.setDisconnected(new DisconnectCause(cause));
            current.destroy();
        }
        if (appContext != null) {
            try { appContext.getSystemService(NotificationManager.class).cancel(NOTIFICATION); }
            catch (RuntimeException error) { android.util.Log.w("IntercomTelecom", "Cancel notification", error); }
        }
        log(origin);
    }
    @Override public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }
    @Override public Connection onCreateIncomingConnection(PhoneAccountHandle account, ConnectionRequest request) {
        try { return createIncoming(account, request); }
        catch (RuntimeException error) {
            finish("着信作成例外: " + error.getClass().getSimpleName() + " " + error.getMessage(), DisconnectCause.ERROR);
            return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.ERROR));
        }
    }
    private Connection createIncoming(PhoneAccountHandle account, ConnectionRequest request) {
        if (!pending || connection != null) return Connection.createFailedConnection(new DisconnectCause(DisconnectCause.CANCELED));
        pending = false;
        TestConnection created = new TestConnection();
        connection = created;
        created.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        created.setConnectionCapabilities(Connection.CAPABILITY_MUTE);
        created.setAudioModeIsVoip(true);
        created.setAddress(Uri.parse("sip:s10-test@intercom.invalid"), TelecomManager.PRESENTATION_ALLOWED);
        created.setCallerDisplayName("S10操作テスト", TelecomManager.PRESENTATION_ALLOWED);
        created.setInitializing();
        created.setRinging();
        log("Connection作成 / 着信待機（S10のボタンを押してください）");
        return created;
    }
    @Override public void onCreateIncomingConnectionFailed(PhoneAccountHandle account, ConnectionRequest request) {
        finish("着信作成失敗（Androidから拒否）", DisconnectCause.ERROR);
    }
    private static PendingIntent action(String action) {
        Intent intent = new Intent(appContext, TestCallReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(appContext, action.equals("answer") ? 1 : 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void notification(boolean ringing) {
        try {
            postNotification(ringing);
        } catch (RuntimeException error) {
            finish("通知失敗: " + error.getClass().getSimpleName() + " " + error.getMessage(), DisconnectCause.ERROR);
        }
    }
    private static void postNotification(boolean ringing) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "インカム通話操作テスト", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(appContext, 3,
            new Intent(appContext, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("S10操作テスト")
            .setContentText(ringing ? "イヤホンボタンで応答を確認" : "通話中のミュート操作を確認（音声通話なし）")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setContentIntent(open);
        // The test has no foreground audio service. Use a regular actionable notification
        // rather than CallStyle, which may be rejected by NotificationManager.
        if (ringing) builder.addAction(new Notification.Action.Builder(null, "応答", action("answer")).build());
        builder.addAction(new Notification.Action.Builder(null, "終了", action("end")).build());
        manager.notify(NOTIFICATION, builder.build());
    }
    private static final class TestConnection extends Connection {
        void activate(String origin) {
            setActive();
            log(origin + " / 通話状態=ACTIVE");
            notification(false);
        }
        @Override public void onShowIncomingCallUi() {
            log("着信UI要求を受信 / S10ボタンで応答待ち");
            notification(true);
        }
        @Override public void onAnswer() { receivedAnswer(); }
        @Override public void onAnswer(int videoState) { receivedAnswer(); }
        private void receivedAnswer() {
            answers++;
            activate("Telecom応答コールバック受信（外部操作）");
        }
        @Override public void onDisconnect() {
            disconnects++;
            finish("Telecom切断コールバック受信（外部操作）", DisconnectCause.LOCAL);
        }
        @Override public void onReject() {
            disconnects++;
            finish("Telecom拒否コールバック受信（外部操作）", DisconnectCause.REJECTED);
        }
        @Override public void onAbort() { finish("Telecom中止コールバック受信", DisconnectCause.CANCELED); }
        @Override public void onMuteStateChanged(boolean isMuted) {
            observeMute(isMuted, "onMuteStateChanged");
        }
        @Override public void onCallAudioStateChanged(CallAudioState audio) {
            if (audio != null) observeMute(audio.isMuted(), "onCallAudioStateChanged");
            log(audio == null ? "通話音声状態: 未取得" : "通話音声状態: route=" + audio.getRoute() + " muted=" + audio.isMuted());
        }
        @Override public void onCallEndpointChanged(CallEndpoint endpoint) {
            log(endpoint == null ? "通話経路: 未取得" : "通話経路: " + endpoint.getEndpointName() + " type=" + endpoint.getEndpointType());
        }
    }
    public static final class TestCallReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if ("answer".equals(intent.getAction())) answerFromScreen();
            else finish("通知からテスト終了", DisconnectCause.LOCAL);
        }
    }
}
