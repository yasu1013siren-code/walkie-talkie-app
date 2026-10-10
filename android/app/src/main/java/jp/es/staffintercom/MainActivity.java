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
    private EditText room, name, store, invite;
    private TextView status, route, headsetState, gainLabel;
    private SeekBar gainControl;
    private CheckBox headsetMode, transcriptionMode, saveMode, voiceMode;
    private EditText startPhrase, stopPhrase;
    private TextView speechStatus, conversationText;
    private ConversationStore history;
    private android.media.MediaPlayer probePlayer;
    private Button join, leave, latch, ptt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { render(); handler.postDelayed(this, 500); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((IntercomService.LocalBinder) b).getService(); applySpeech(false); render();
        }
        public void onServiceDisconnected(ComponentName n) { service = null; render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        history = new ConversationStore(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this); title.setText("スタッフインカム " + BuildConfig.VERSION_NAME + "\nバックグラウンド通話"); title.setTextSize(23); root.addView(title);
        room = new EditText(this); room.setSingleLine(true); room.setHint("ルームID（例：es）");
        name = new EditText(this); name.setSingleLine(true); name.setHint("名前");
        android.content.SharedPreferences prefs = getSharedPreferences("intercom", MODE_PRIVATE);
        room.setText(prefs.getString("room", "es")); name.setText(prefs.getString("name", ""));
        root.addView(room); root.addView(name);
        store = new EditText(this); store.setSingleLine(true); store.setHint("店舗ID（必須）");
        store.setText(prefs.getString("storeId", "")); root.addView(store);
        invite = new EditText(this); invite.setSingleLine(true); invite.setHint("招待コード（保存されません）");
        invite.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        invite.setSaveEnabled(false); invite.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(invite);
        headsetMode = new CheckBox(this); headsetMode.setText("イヤホンの通話ボタンを使う");
        headsetMode.setChecked(prefs.getBoolean("headsetCalls", false));
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
        transcriptionMode = new CheckBox(this); transcriptionMode.setText("会話をリアルタイムで文字表示");
        transcriptionMode.setChecked(prefs.getBoolean("transcribe",true)); root.addView(transcriptionMode);
        saveMode = new CheckBox(this); saveMode.setText("確定した文字を端末に保存（最新5000件）");
        saveMode.setChecked(prefs.getBoolean("saveConversation",true)); root.addView(saveMode);
        voiceMode = new CheckBox(this); voiceMode.setText("声で送信開始・停止（試験機能）");
        voiceMode.setChecked(prefs.getBoolean("voiceCommands",false)); root.addView(voiceMode);
        startPhrase = new EditText(this); startPhrase.setSingleLine(true); startPhrase.setHint("送信開始の言葉"); startPhrase.setText(prefs.getString("startWord","インカム開始")); root.addView(startPhrase);
        stopPhrase = new EditText(this); stopPhrase.setSingleLine(true); stopPhrase.setHint("送信停止の言葉"); stopPhrase.setText(prefs.getString("stopWord","インカム停止")); root.addView(stopPhrase);
        button(root,"文字起こし・音声操作の設定を適用",v -> applySpeech(true));
        join = button(root, "ルームに参加", v -> requestJoin());
        status = new TextView(this); status.setTextSize(18); status.setPadding(0, pad, 0, pad); root.addView(status);
        route = new TextView(this); route.setTextSize(15); root.addView(route);
        headsetState = new TextView(this); root.addView(headsetState);
        speechStatus = new TextView(this); root.addView(speechStatus);
        button(root,"相手の受信音声を5秒録音（端末内のみ）",v -> {
            if(probePlayer!=null){probePlayer.release();probePlayer=null;}
            if(service==null || !service.startSpeechProbe())Toast.makeText(this,"文字起こしをONにし、相手と接続してから押してください",Toast.LENGTH_LONG).show();
            else Toast.makeText(this,"相手に5秒ほど話してもらってください。録音は端末内のみです",Toast.LENGTH_LONG).show();
        });
        button(root,"補正前の音声を再生",v -> playProbe(false));
        button(root,"認識に渡した音声を再生",v -> playProbe(true));
        button(root,"認識用音声を書き出す",v -> {
            if(!new java.io.File(getFilesDir(),"speech-probe-recognition.wav").exists()){Toast.makeText(this,"先に5秒録音してください",Toast.LENGTH_SHORT).show();return;}
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/wav").putExtra(Intent.EXTRA_TITLE,"intercom-recognition.wav"),201);
        });
        TextView heading = new TextView(this); heading.setText("最近の会話（文字起こし）"); heading.setTextSize(20); root.addView(heading);
        conversationText = new TextView(this); conversationText.setTextIsSelectable(true); root.addView(conversationText);
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
        TextView speechHelp = new TextView(this); speechHelp.setText("文字起こしは両端末を新版に更新してください。話した端末が音声を認識し、相手へ文字を送ります。途中の文字は訂正される場合があります。\n音声操作は通話接続後に開始・停止の言葉だけを話してください。声で開始した送信は30秒で自動停止します。画面やイヤホンのボタンも使えます。\n初回は日本語モデルの準備に時間がかかります。文字起こしの精度・Bluetooth・画面OFF中の音声操作は実機で確認してください。"); root.addView(speechHelp);
        button(root,"会話履歴を書き出す",v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"intercom-conversation.txt"),200));
        button(root,"会話履歴を削除",v -> new android.app.AlertDialog.Builder(this).setMessage("この端末に保存した文字の履歴を削除しますか？").setNegativeButton("戻る",null).setPositiveButton("削除",(d,w)->{history.clear();if(service!=null)service.clearRecentConversation();render();}).show());
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
    private void playProbe(boolean normalized){
        java.io.File file=new java.io.File(getFilesDir(),normalized?"speech-probe-recognition.wav":"speech-probe-raw.wav");
        if(!file.exists()){Toast.makeText(this,"先に5秒録音してください",Toast.LENGTH_SHORT).show();return;}
        if(service!=null && service.isTalking()){Toast.makeText(this,"送信を停止してから再生してください",Toast.LENGTH_SHORT).show();return;}
        try{
            if(probePlayer!=null){probePlayer.release();probePlayer=null;}
            probePlayer=new android.media.MediaPlayer();
            probePlayer.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            probePlayer.setDataSource(file.getAbsolutePath());probePlayer.setOnCompletionListener(player->{player.release();if(probePlayer==player)probePlayer=null;});
            probePlayer.prepare();probePlayer.start();
        }catch(Exception e){if(probePlayer!=null){probePlayer.release();probePlayer=null;}Toast.makeText(this,"再生できませんでした",Toast.LENGTH_SHORT).show();}
    }
    private void applySpeech(boolean announce) {
        String start=startPhrase.getText().toString().trim(), stop=stopPhrase.getText().toString().trim();
        if (voiceMode.isChecked() && (SpeechRules.normalize(start).length()<3 || SpeechRules.normalize(stop).length()<3 || start.length()>40 || stop.length()>40 || SpeechRules.normalize(start).equals(SpeechRules.normalize(stop)))) {
            if(announce)Toast.makeText(this,"開始・停止には異なる3〜40文字の言葉を設定してください",Toast.LENGTH_LONG).show();return;
        }
        getSharedPreferences("intercom",MODE_PRIVATE).edit().putBoolean("transcribe",transcriptionMode.isChecked()).putBoolean("saveConversation",saveMode.isChecked()).putBoolean("voiceCommands",voiceMode.isChecked()).putString("startWord",start).putString("stopWord",stop).apply();
        if(service!=null)service.configureSpeech(transcriptionMode.isChecked(),saveMode.isChecked(),voiceMode.isChecked(),start,stop);
        if(announce)Toast.makeText(this,"設定を適用しました",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if((request!=200 && request!=201) || result!=RESULT_OK || data==null || data.getData()==null)return;
        try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
            if(out==null)throw new java.io.IOException();
            if(request==201){try(java.io.InputStream in=new java.io.FileInputStream(new java.io.File(getFilesDir(),"speech-probe-recognition.wav"))){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}}
            else out.write(history.text(5000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this,request==201?"認識用音声を書き出しました":"会話履歴を書き出しました",Toast.LENGTH_SHORT).show();
        }catch(Exception e){Toast.makeText(this,"書き出せませんでした",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onDestroy(){if(probePlayer!=null){probePlayer.release();probePlayer=null;}if(history!=null)history.close();super.onDestroy();}
    private void requestJoin() {
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
        getSharedPreferences("intercom", MODE_PRIVATE).edit().putString("storeId", store.getText().toString().trim()).putString("room", room.getText().toString().trim()).putString("name", name.getText().toString().trim()).apply();
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
        if(speechStatus!=null)speechStatus.setText(service==null?"接続後に音声認識を開始します":service.getSpeechState());
        if(conversationText!=null){String value=service==null?history.text(100):service.getConversation();if(!value.contentEquals(conversationText.getText()))conversationText.setText(value);}
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
