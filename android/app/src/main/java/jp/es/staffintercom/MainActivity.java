package jp.es.staffintercom;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Toast;
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
    private WebView webView;
    private MediaSession mediaSession;
    private PermissionRequest pendingAudioRequest;
    private boolean joined;
    private boolean talking;
    private boolean foreground;
    private long lastButtonTime;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mediaSession = new MediaSession(this, "StaffIntercom");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || !isToggleKey(event.getKeyCode())) return super.onMediaButtonEvent(intent);
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) toggleFromHeadset();
                return true;
            }
            @Override public void onPlay() { toggleFromHeadset(); }
            @Override public void onPause() { stopFromHeadset(); }
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
                    if (!SITE_URI.getScheme().equals(origin.getScheme()) ||
                        !SITE_URI.getHost().equals(origin.getHost()) ||
                        origin.getPort() != -1 ||
                        !java.util.Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingAudioRequest != null) pendingAudioRequest.deny();
                        pendingAudioRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingAudioRequest == request) pendingAudioRequest = null;
            }
        });
        setContentView(webView);
        webView.loadUrl(SITE + "/");
    }

    private static boolean isToggleKey(int code) {
        return code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private void toggleFromHeadset() {
        runOnUiThread(() -> {
            if (!joined || webView == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastButtonTime < 350) return;
            lastButtonTime = now;
            webView.evaluateJavascript("window.intercomNativeToggle?.()", null);
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
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
            .setState(active ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED, 0, 1f).build());
        mediaSession.setActive(active);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != AUDIO_PERMISSION || pendingAudioRequest == null) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            pendingAudioRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            pendingAudioRequest.deny();
            Toast.makeText(this, "マイクを許可してください。拒否した場合は端末の設定 → アプリ → スタッフインカム → 権限から変更できます。", Toast.LENGTH_LONG).show();
        }
        pendingAudioRequest = null;
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
        joined = false;
        if (pendingAudioRequest != null) pendingAudioRequest.deny();
        mediaSession.setActive(false);
        mediaSession.release();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
