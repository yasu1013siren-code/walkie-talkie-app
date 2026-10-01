package jp.es.staffintercom;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** UI only: the service owns the audio session, including while this Activity is stopped. */
public final class MainActivity extends Activity {
    private IntercomService service;
    private boolean bound, holding;
    private EditText room, name;
    private TextView status, route;
    private Button join, leave, latch, ptt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 500); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((IntercomService.LocalBinder) b).getService(); render();
        }
        public void onServiceDisconnected(ComponentName n) { service = null; render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("スタッフインカム 0.2.0\nバックグラウンド通話・試験版"); title.setTextSize(23); root.addView(title);
        room = new EditText(this); room.setSingleLine(true); room.setHint("ルームID（例：es）");
        name = new EditText(this); name.setSingleLine(true); name.setHint("名前");
        android.content.SharedPreferences prefs = getSharedPreferences("intercom", MODE_PRIVATE);
        room.setText(prefs.getString("room", "es")); name.setText(prefs.getString("name", ""));
        root.addView(room); root.addView(name);
        join = button(root, "ルームに参加", v -> requestJoin());
        status = new TextView(this); status.setTextSize(18); status.setPadding(0, pad, 0, pad); root.addView(status);
        route = new TextView(this); route.setTextSize(15); root.addView(route);
        ptt = button(root, "押しながら話す", null);
        ptt.setOnTouchListener((v, event) -> {
            if (service == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: holding = true; service.setTalking(true); render(); return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: holding = false; service.setTalking(false); render(); return true;
            }
            return true;
        });
        latch = button(root, "送信を開始（もう一度押すと停止）", v -> { if (service != null) service.toggleTalking(); render(); });
        button(root, "Bluetooth接続を再確認", v -> { if (service != null) service.selectAudioRoute(); render(); });
        leave = button(root, "退出", v -> { if (service != null) service.leave(); render(); });
        TextView help = new TextView(this);
        help.setText("Bluetoothイヤホンを接続してから参加してください。\n参加中は画面を消しても受信を続けます。送信切替は通知からも操作できます。\nイヤホンボタンは機種によって届かない場合があります。");
        help.setPadding(0, pad, 0, 0); root.addView(help);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll); render();
    }
    private Button button(LinearLayout root, String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); if (click != null) b.setOnClickListener(click);
        root.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    private void requestJoin() {
        if (!room.getText().toString().trim().matches("[a-zA-Z0-9_-]{1,32}")) {
            Toast.makeText(this, "ルームIDは半角英数字・_・- の32文字以内です", Toast.LENGTH_LONG).show(); return;
        }
        if (name.length() > 40) { Toast.makeText(this, "名前は40文字以内です", Toast.LENGTH_LONG).show(); return; }
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), 100); return; }
        startSession();
    }
    private void startSession() {
        getSharedPreferences("intercom", MODE_PRIVATE).edit().putString("room", room.getText().toString().trim()).putString("name", name.getText().toString().trim()).apply();
        Intent intent = new Intent(this, IntercomService.class).setAction(IntercomService.JOIN)
            .putExtra("room", room.getText().toString().trim()).putExtra("name", name.getText().toString().trim());
        startForegroundService(intent);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != 100) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "マイクの許可が必要です", Toast.LENGTH_LONG).show(); return;
        }
        startSession();
    }
    private void render() {
        if (status == null) return;
        boolean active = service != null && service.isJoined();
        boolean connected = active && service.isConnected();
        status.setText(service == null ? "未参加" : service.getStatus());
        route.setText(service == null ? "音声出力：未接続" : service.getRoute());
        join.setEnabled(!active); room.setEnabled(!active); name.setEnabled(!active);
        leave.setEnabled(active); ptt.setEnabled(connected); latch.setEnabled(connected);
        boolean talking = active && service.isTalking();
        latch.setText(talking ? "送信を停止" : "送信を開始（もう一度押すと停止）");
        ptt.setText(talking ? "● 送信中" : "押しながら話す");
    }
    @Override protected void onStart() { super.onStart(); bound = bindService(new Intent(this, IntercomService.class), connection, BIND_AUTO_CREATE); handler.post(refresh); }
    @Override protected void onStop() {
        // A press-and-hold ends if the UI disappears. A latched transmission remains under user control.
        if (holding && service != null) service.setTalking(false);
        holding = false; handler.removeCallbacksAndMessages(null);
        if (bound) { unbindService(connection); bound = false; }
        service = null; super.onStop();
    }
}
