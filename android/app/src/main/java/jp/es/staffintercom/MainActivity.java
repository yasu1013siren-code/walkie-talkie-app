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
    private boolean bound, holding, autoJoinStarted;
    private EditText room, name, store, invite;
    private TextView status, route, headsetState, gainLabel;
    private SeekBar gainControl;
    private CheckBox headsetMode;
    private Button join, leave, latch, ptt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 500); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((IntercomService.LocalBinder) b).getService(); render();
            if (!autoJoinStarted) { autoJoinStarted=true; if (!service.isJoined()) requestJoin(); }
        }
        public void onServiceDisconnected(ComponentName n) { service = null; render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("自分用インカム " + BuildConfig.VERSION_NAME); title.setTextSize(23); root.addView(title);
        room = new EditText(this); room.setSingleLine(true); room.setHint("ルームID（例：es）");
        name = new EditText(this); name.setSingleLine(true); name.setHint("名前");
        android.content.SharedPreferences prefs = getSharedPreferences("intercom", MODE_PRIVATE);
        room.setText("main"); name.setText(prefs.getString("name", android.os.Build.MODEL.substring(0, Math.min(25, android.os.Build.MODEL.length())) + "-" + java.util.UUID.randomUUID().toString().substring(0,4)));
        root.addView(name);
        store = new EditText(this); store.setSingleLine(true); store.setHint("店舗ID（必須）");
        store.setText("personal");
        invite = new EditText(this); invite.setSingleLine(true); invite.setHint("招待コード（保存されません）");
        invite.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        invite.setSaveEnabled(false); invite.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        invite.setText(personalInvite());
        headsetMode = new CheckBox(this); headsetMode.setText("イヤホンの通話ボタンを使う");
        headsetMode.setChecked(prefs.getBoolean("headsetCalls", true));
        headsetMode.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("intercom", MODE_PRIVATE).edit().putBoolean("headsetCalls", checked).apply();
            if (service != null) service.setHeadsetCalls(checked);
        });
        root.addView(headsetMode);
        button(root,"S10ボタン操作を再登録",v -> {
            if(service==null || !service.isJoined()){Toast.makeText(this,"通話に接続してから再登録してください",Toast.LENGTH_SHORT).show();return;}
            if(!headsetMode.isChecked())headsetMode.setChecked(true);else service.setHeadsetCalls(true);
            service.selectAudioRoute(); render();
        });
        join = button(root, "通話に接続", v -> requestJoin());
        status = new TextView(this); status.setTextSize(18); status.setPadding(0, pad, 0, pad); root.addView(status);
        route = new TextView(this); route.setTextSize(15); root.addView(route);
        headsetState = new TextView(this); root.addView(headsetState);
        gainLabel = new TextView(this); root.addView(gainLabel);
        gainControl = new SeekBar(this); gainControl.setMax(4);
        float savedGain = IntercomService.normalizeReceiveGain(prefs.getFloat("receiveGain", 2f));
        gainControl.setProgress(Math.round((savedGain - 1f) * 2));
        gainLabel.setText("受信音声の増幅：" + savedGain + "倍（音割れ時は下げてください）");
        gainControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float gain = 1f + progress * 0.5f;
                gainLabel.setText("受信音声の増幅：" + gain + "倍（音割れ時は下げてください）");
                if (fromUser) {
                    prefs.edit().putFloat("receiveGain", gain).apply();
                    if (service != null) service.setReceiveGain(gain);
                }
            }
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(gainControl);
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
        help.setText("Bluetoothイヤホンを接続してから参加してください。\n参加中は画面を消しても受信を続けます。送信切替は通知からも操作できます。\n通話ボタン操作ON：通話ボタンで送信開始、もう一度押すと停止。\n停止後は次の操作の準備に約1秒かかります。イヤホン側で待機音が鳴る場合があります。");
        help.setPadding(0, pad, 0, 0); root.addView(help);
        ScrollView scroll = new ScrollView(this); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom); return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets(); render();
    }
    private Button button(LinearLayout root, String text, View.OnClickListener click) {
        Button b = new Button(this); b.setText(text); if (click != null) b.setOnClickListener(click);
        root.addView(b, new LinearLayout.LayoutParams(-1, -2)); return b;
    }
    private String personalInvite() {
        try (java.io.InputStream input = getAssets().open("personal-access.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] bytes = new byte[256]; int n;
            while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n);
            return out.toString("UTF-8").trim();
        } catch (java.io.IOException e) { return ""; }
    }
    private void requestJoin() {
        room.setText("main"); store.setText("personal"); invite.setText(personalInvite());
        if (!room.getText().toString().trim().matches("[a-zA-Z0-9_-]{1,32}")) {
            Toast.makeText(this, "ルームIDは半角英数字・_・- の32文字以内です", Toast.LENGTH_LONG).show(); return;
        }
        String storeId = store.getText().toString().trim();
        if ((!storeId.matches("[a-zA-Z0-9_-]{1,32}") || invite.length() < 32 || invite.length() > 256)) {
            Toast.makeText(this, "店舗IDと招待コードを確認してください", Toast.LENGTH_LONG).show(); return;
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
        getSharedPreferences("intercom", MODE_PRIVATE).edit().putString("name", name.getText().toString().trim()).apply();
        Intent intent = new Intent(this, IntercomService.class).setAction(IntercomService.JOIN)
            .putExtra("room", room.getText().toString().trim()).putExtra("name", name.getText().toString().trim()).putExtra("headsetCalls", headsetMode.isChecked())
            .putExtra("storeId", store.getText().toString().trim()).putExtra("inviteCode", invite.getText().toString());
        invite.setText("");
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
        headsetState.setText(service == null ? "" : service.getHeadsetStatus());
        join.setEnabled(!active); room.setEnabled(!active); name.setEnabled(!active); store.setEnabled(!active); invite.setEnabled(!active);
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
