package jp.es.staffintercom;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Button;
import android.content.ClipData;
import android.content.ClipboardManager;
import java.util.ArrayDeque;
import android.view.KeyEvent;
import android.widget.Toast;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public final class MainActivity extends Activity {
    private static final String SITE = "https://walkie-talkie-app-42l7.onrender.com";
    private static final int AUDIO_PERMISSION = 100;
    private static final Uri SITE_URI = Uri.parse(SITE);
    private TextView diagnosticView;
    private final Handler diagnosticHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> diagnosticEvents = new ArrayDeque<>();
    private int keyEvents;
    private int mediaCommands;
    private String focusStatus = "未要求";
    private String playbackStatus = "停止";
    private final Runnable diagnosticRefresh = new Runnable() {
        @Override public void run() {
            renderDiagnostics();
            diagnosticHandler.postDelayed(this, 1000);
        }
    };
    private WebView webView;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioTrack controlPlayback;
    private boolean hasAudioFocus;
    private PermissionRequest pendingAudioRequest;
    private boolean joined;
    private boolean talking;
    private boolean foreground;
    private boolean pageLoaded;
    private long lastButtonTime;
    private volatile String microphoneEvent = "WebViewのマイク要求は未受信";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {
                focusStatus = "通知=" + change;
                recordDiagnostic("音声フォーカス " + change);
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                    stopFromHeadset();
            }).build();
        mediaSession = new MediaSession(this, "StaffIntercom");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null) recordKey("MediaSession", event);
                if (event == null || !isToggleKey(event.getKeyCode())) return super.onMediaButtonEvent(intent);
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleFromHeadset();
                return true;
            }
            @Override public void onPlay() { recordCommand("PLAY"); toggleFromHeadset(); }
            @Override public void onPause() { recordCommand("PAUSE"); toggleFromHeadset(); }
            @Override public void onStop() { recordCommand("STOP"); toggleFromHeadset(); }
        });
        mediaSession.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "スタッフインカム").build());
        updateSession();

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void setJoined(boolean value) {
                runOnUiThread(() -> {
                    joined = value;
                    if (!joined) talking = false;
                    updateSession();
                });
            }
            @JavascriptInterface public void setTalking(boolean value) {
                runOnUiThread(() -> { talking = joined && value; updateSession(); });
            }
            @JavascriptInterface public String getMicrophoneDiagnostics() {
                return "Android権限=" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "許可" : "拒否") +
                    " / " + microphoneEvent;
            }
        }, "IntercomNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !SITE.equals(request.getUrl().getScheme() + "://" + request.getUrl().getAuthority());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin = request.getOrigin();
                    microphoneEvent = "WebView要求あり: " + origin;
                    if (!SITE_URI.getScheme().equals(origin.getScheme()) ||
                        !SITE_URI.getHost().equals(origin.getHost()) ||
                        (origin.getPort() != -1 && origin.getPort() != 443) ||
                        !java.util.Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        microphoneEvent = "WebView拒否: origin=" + origin + " resources=" + java.util.Arrays.toString(request.getResources());
                        request.deny();
                        Toast.makeText(MainActivity.this, "Web画面のマイク要求を許可できませんでした。アプリを再起動してください。", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        microphoneEvent = "WebViewマイク許可済み: " + origin;
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        microphoneEvent = "Android権限を要求中";
                        if (pendingAudioRequest != null) pendingAudioRequest.deny();
                        pendingAudioRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                microphoneEvent = "WebView要求が取り消されました";
                if (pendingAudioRequest == request) pendingAudioRequest = null;
            }
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout actions = new LinearLayout(this);
        TextView title = new TextView(this);
        title.setText("イヤホン診断 v0.1.7");
        actions.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button copy = new Button(this);
        copy.setText("コピー");
        copy.setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("イヤホン診断", diagnosticView.getText()));
            Toast.makeText(this, "診断をコピーしました", Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy);
        Button clear = new Button(this);
        clear.setText("消去");
        clear.setOnClickListener(v -> {
            diagnosticEvents.clear(); keyEvents = 0; mediaCommands = 0; renderDiagnostics();
        });
        actions.addView(clear);
        root.addView(actions);
        ScrollView diagnosticScroll = new ScrollView(this);
        diagnosticView = new TextView(this);
        diagnosticView.setTextSize(12);
        diagnosticView.setTextIsSelectable(true);
        diagnosticView.setPadding(12, 4, 12, 8);
        diagnosticScroll.addView(diagnosticView);
        root.addView(diagnosticScroll, new LinearLayout.LayoutParams(-1,
            (int) (150 * getResources().getDisplayMetrics().density)));
        setContentView(root);
        diagnosticHandler.post(diagnosticRefresh);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            loadWebApp();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        }
    }

    private void loadWebApp() {
        if (pageLoaded) return;
        pageLoaded = true;
        webView.loadUrl(SITE + "/");
    }

    private static boolean isToggleKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_STOP;
    }

    private void recordDiagnostic(String message) {
        runOnUiThread(() -> {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
                .format(new java.util.Date());
            diagnosticEvents.addFirst(time + " " + message);
            while (diagnosticEvents.size() > 12) diagnosticEvents.removeLast();
            renderDiagnostics();
        });
    }

    private void recordKey(String source, KeyEvent event) {
        runOnUiThread(() -> {
            keyEvents++;
            recordDiagnostic(source + " " + KeyEvent.keyCodeToString(event.getKeyCode()) +
                "(" + event.getKeyCode() + ") " + (event.getAction() == KeyEvent.ACTION_DOWN ? "押下" : event.getAction() == KeyEvent.ACTION_UP ? "解放" : "複数入力") +
                " repeat=" + event.getRepeatCount());
        });
    }

    private void recordCommand(String command) {
        mediaCommands++;
        recordDiagnostic("音声操作受信: " + command);
    }

    private void renderDiagnostics() {
        if (diagnosticView == null) return;
        StringBuilder text = new StringBuilder();
        text.append("ルーム=").append(joined ? "参加" : "未参加")
            .append(" / 画面=").append(foreground ? "表示中" : "非表示")
            .append(" / 送信=").append(talking ? "中" : "停止")
            .append("\nMediaSession=").append(mediaSession != null && mediaSession.isActive() ? "有効" : "無効")
            .append(" / フォーカス=").append(focusStatus)
            .append("\n操作受付用の無音再生=").append(playbackStatus)
            .append("\nキー受信=").append(keyEvents).append("件 / 音声操作受信=").append(mediaCommands).append("件")
            .append("\n").append(microphoneEvent);
        if (keyEvents == 0 && mediaCommands == 0)
            text.append("\nこのアプリへのボタン信号は未受信（原因は未確定）");
        for (String event : diagnosticEvents) text.append("\n").append(event);
        diagnosticView.setText(text.toString());
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        recordKey("画面", event);
        if (joined && isToggleKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleFromHeadset();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void toggleFromHeadset() {
        runOnUiThread(() -> {
            if (!joined || webView == null) {
                recordDiagnostic("切替を見送り: ルーム未参加"); return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastButtonTime < 350) {
                recordDiagnostic("切替を見送り: 350ms以内の重複"); return;
            }
            lastButtonTime = now;
            webView.evaluateJavascript("(() => { if (typeof window.intercomNativeToggle !== 'function') return 'missing'; window.intercomNativeToggle(); return 'called'; })()",
                result -> recordDiagnostic("Web画面へ切替要求: " + result));
        });
    }

    private void stopFromHeadset() {
        runOnUiThread(() -> {
            if (joined && talking && webView != null) {
                webView.evaluateJavascript("window.intercomNativeStop?.()", null);
            }
        });
    }

    private void updateSession() {
        if (mediaSession == null) return;
        boolean active = joined && foreground && webView != null;
        if (active && !hasAudioFocus) {
            hasAudioFocus = audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            focusStatus = hasAudioFocus ? "取得成功" : "取得失敗";
        } else if (!active && hasAudioFocus) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            hasAudioFocus = false;
            focusStatus = "解放";
        }
        if (active && hasAudioFocus) startControlPlayback();
        else stopControlPlayback();
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
            .setState(active ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED, 0, 1f).build());
        mediaSession.setActive(active);
    }

    private void startControlPlayback() {
        if (controlPlayback != null) return;
        final int sampleRate = 16000;
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(sampleRate * 2)
                .build();
            // MODE_STATIC starts in STATE_NO_STATIC_DATA until PCM is written.
            int initialState = track.getState();
            if (initialState == AudioTrack.STATE_UNINITIALIZED) {
                playbackStatus = "作成失敗: state=" + initialState;
                track.release();
                return;
            }
            int written = track.write(new short[sampleRate], 0, sampleRate);
            int loadedState = track.getState();
            if (written != sampleRate || loadedState != AudioTrack.STATE_INITIALIZED) {
                playbackStatus = "書込失敗: write=" + written + " state=" + loadedState;
                track.release();
                return;
            }
            int loopResult = track.setLoopPoints(0, sampleRate, -1);
            if (loopResult != AudioTrack.SUCCESS) {
                playbackStatus = "ループ設定失敗: code=" + loopResult;
                track.release();
                return;
            }
            track.setVolume(0f);
            track.play();
            controlPlayback = track;
            playbackStatus = "再生中";
        } catch (RuntimeException e) {
            if (track != null) track.release();
            playbackStatus = "失敗: " + e.getClass().getSimpleName();
            Log.w("StaffIntercom", "Could not start media control playback", e);
        }
    }

    private void stopControlPlayback() {
        if (controlPlayback == null) return;
        controlPlayback.stop();
        controlPlayback.release();
        controlPlayback = null;
        playbackStatus = "停止";
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != AUDIO_PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        microphoneEvent = granted ? "Android権限を許可" : "Android権限を拒否";
        if (pendingAudioRequest != null) {
            if (granted) {
                pendingAudioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                microphoneEvent = "WebViewマイク許可済み";
            }
            else pendingAudioRequest.deny();
            pendingAudioRequest = null;
        }
        if (!granted) {
            Toast.makeText(this, "マイクを許可してください。拒否した場合は端末の設定 → アプリ → スタッフインカム → 権限から変更できます。", Toast.LENGTH_LONG).show();
        }
        loadWebApp();
    }

    @Override protected void onStop() {
        foreground = false;
        if (webView != null) webView.evaluateJavascript("window.intercomNativeStop?.()", null);
        updateSession();
        super.onStop();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        updateSession();
    }

    @Override protected void onDestroy() {
        diagnosticHandler.removeCallbacks(diagnosticRefresh);
        joined = false;
        if (pendingAudioRequest != null) pendingAudioRequest.deny();
        mediaSession.setActive(false);
        stopControlPlayback();
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(audioFocusRequest);
        mediaSession.release();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
