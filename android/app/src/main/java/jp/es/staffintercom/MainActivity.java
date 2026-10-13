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
    private TextView status, route, headsetState, gainLabel, stateChip, talkHint;
    private LinearLayout settings;
    private boolean previousTalking, touchClick;
    private View halo;
    private static final int BG=0xff0b1025, CARD=0xff1b2540, BORDER=0xff3d4a69, INK=0xfff8f4ff, MUTED=0xffb6bfd8, ACCENT=0xff98edf6, STOP=0xffffb6be;
    private SeekBar gainControl;
    private TextView microphoneLabel;
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
        android.content.SharedPreferences prefs=getSharedPreferences("intercom",MODE_PRIVATE);
        LinearLayout root=column();root.setPadding(dp(24),dp(26),dp(24),dp(28));root.setBackground(new IntercomArtwork(IntercomArtwork.AMBIENT,false));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("INCOM",34,INK);brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));brand.setLetterSpacing(.1f);brand.getPaint().setShader(new android.graphics.LinearGradient(0,0,dp(160),dp(25),new int[]{0xfff4efff,0xffbba5ff,0xff91edfa},null,android.graphics.Shader.TileMode.CLAMP));
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView personal=text("PERSONAL",10,ACCENT);personal.setLetterSpacing(.15f);personal.setPadding(dp(12),dp(8),dp(12),dp(8));personal.setBackground(gradient(0xff3c2b61,0xff1b384b,20,0xff6a6296));header.addView(personal);root.addView(header);
        TextView caption=text("あなたの声を、もっと近くに。",14,MUTED);add(root,caption,8);
        LinearLayout session=card();session.setBackground(gradient(0xff342557,0xff183b50,20,0xff625988));add(root,session,24);
        stateChip=text("●  未接続",12,ACCENT);session.addView(stateChip);
        status=text("接続を準備しています",20,INK);status.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));add(session,status,10);
        TextView background=text("画面を消しても、受信を続けます",12,MUTED);add(session,background,8);
        TextView section=text("PUSH TO TALK",11,MUTED);section.setLetterSpacing(.18f);section.setGravity(Gravity.CENTER);add(root,section,22);
        FrameLayout disc=new FrameLayout(this);LinearLayout.LayoutParams discLayout=new LinearLayout.LayoutParams(-1,dp(264));discLayout.topMargin=dp(12);root.addView(disc,discLayout);
        halo=new View(this);halo.setBackground(new IntercomArtwork(IntercomArtwork.HALO,false));FrameLayout.LayoutParams haloLayout=new FrameLayout.LayoutParams(dp(260),dp(260),Gravity.CENTER);disc.addView(halo,haloLayout);
        ptt=new Button(this);ptt.setAllCaps(false);ptt.setTextSize(17);ptt.setTextColor(INK);ptt.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));ptt.setPadding(dp(12),dp(48),dp(12),dp(36));
        ptt.setCompoundDrawablesWithIntrinsicBounds(0,R.drawable.ic_mic,0,0);ptt.setCompoundDrawablePadding(dp(18));ptt.setBackground(dial(false));
        ptt.setContentDescription("押している間だけ送信。指を離すと停止");disc.addView(ptt,new FrameLayout.LayoutParams(dp(212),dp(212),Gravity.CENTER));
        ptt.setOnTouchListener((v,event)->{
            if(service==null || !ptt.isEnabled())return false;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:holding=true;service.setTalking(true);render();return true;
                case MotionEvent.ACTION_UP:holding=false;service.setTalking(false);touchClick=true;try{v.performClick();}finally{touchClick=false;}render();return true;
                case MotionEvent.ACTION_CANCEL:holding=false;service.setTalking(false);render();return true;
            }return true;
        });
        // An accessibility click has no sustained press, so it uses the same safe toggle as the latch.
        ptt.setOnClickListener(v->{if(!touchClick && service!=null && ptt.isEnabled())service.toggleTalking();render();});
        View wave=new View(this);wave.setBackground(new IntercomArtwork(IntercomArtwork.WAVE,false));wave.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams waveLayout=new LinearLayout.LayoutParams(dp(98),dp(18));waveLayout.gravity=Gravity.CENTER_HORIZONTAL;waveLayout.topMargin=dp(2);root.addView(wave,waveLayout);
        talkHint=text("ボタンを押している間だけ送信",12,MUTED);talkHint.setGravity(Gravity.CENTER);add(root,talkHint,12);
        latch=button(root,"送信を開始",v->{if(service!=null)service.toggleTalking();render();});addMargin(latch,20);
        TextView latchHint=text("連続して話すときは、もう一度押すと停止",11,MUTED);latchHint.setGravity(Gravity.CENTER);add(root,latchHint,8);
        LinearLayout audioCard=card();audioCard.setBackground(gradient(0xff19374b,0xff282740,20,0xff425976));add(root,audioCard,24);audioCard.addView(text("AUDIO DEVICE",10,MUTED));
        route=text("音声出力：未接続",15,INK);add(audioCard,route,10);
        Button reconnect=button(audioCard,"Bluetooth接続を再確認",v->{if(service!=null)service.selectAudioRoute();render();});secondary(reconnect);addMargin(reconnect,12);
        LinearLayout actions=new LinearLayout(this);add(root,actions,12);
        join=new Button(this);styleButton(join);join.setText("通話に接続");join.setOnClickListener(v->requestJoin());secondary(join);
        leave=new Button(this);styleButton(leave);leave.setText("退出");leave.setOnClickListener(v->{if(service!=null)service.leave();render();});secondary(leave);
        LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(52),1);half.rightMargin=dp(6);actions.addView(join,half);
        LinearLayout.LayoutParams other=new LinearLayout.LayoutParams(0,dp(52),1);other.leftMargin=dp(6);actions.addView(leave,other);
        Button settingsToggle=button(root,"設定・イヤホン操作  ＋",null);secondary(settingsToggle);addMargin(settingsToggle,20);
        settings=card();settings.setVisibility(View.GONE);add(root,settings,12);
        settingsToggle.setOnClickListener(v->{boolean open=settings.getVisibility()!=View.VISIBLE;settings.setVisibility(open?View.VISIBLE:View.GONE);settingsToggle.setText(open?"設定・イヤホン操作  −":"設定・イヤホン操作  ＋");});
        settings.addView(text("表示名",12,MUTED));
        name=new EditText(this);name.setSingleLine(true);name.setTextColor(INK);name.setHintTextColor(MUTED);name.setTextSize(15);name.setPadding(dp(12),dp(12),dp(12),dp(12));name.setBackground(surface(BG,12,BORDER));name.setHint("名前");name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        name.setText(prefs.getString("name",Build.MODEL.substring(0,Math.min(25,Build.MODEL.length()))+"-"+java.util.UUID.randomUUID().toString().substring(0,4)));add(settings,name,8);
        room=new EditText(this);room.setText("main");store=new EditText(this);store.setText("personal");invite=new EditText(this);invite.setSaveEnabled(false);invite.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);invite.setText(personalInvite());
        headsetMode=new CheckBox(this);headsetMode.setText("イヤホンの通話ボタンを使う");headsetMode.setTextColor(INK);headsetMode.setTextSize(14);headsetMode.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));headsetMode.setChecked(prefs.getBoolean("headsetCalls",true));
        headsetMode.setOnCheckedChangeListener((button,checked)->{prefs.edit().putBoolean("headsetCalls",checked).apply();if(service!=null)service.setHeadsetCalls(checked);});add(settings,headsetMode,20);
        Button register=button(settings,"S10ボタン操作を再登録",v->{
            if(service==null || !service.isJoined()){Toast.makeText(this,"通話に接続してから再登録してください",Toast.LENGTH_SHORT).show();return;}
            if(!headsetMode.isChecked())headsetMode.setChecked(true);else service.setHeadsetCalls(true);service.selectAudioRoute();render();
        });secondary(register);addMargin(register,8);
        headsetState=text("",12,MUTED);add(settings,headsetState,10);
        microphoneLabel=text("",14,INK);add(settings,microphoneLabel,22);
        SeekBar microphoneControl=new SeekBar(this);microphoneControl.setMax(6);microphoneControl.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffbca8ff));microphoneControl.setThumbTintList(android.content.res.ColorStateList.valueOf(0xffbca8ff));
        float savedMicrophone=MicrophoneGain.normalize(prefs.getFloat("microphoneGain",1f));microphoneControl.setProgress(Math.round((savedMicrophone-1f)*2));microphoneLabel.setText("送信マイクの増幅  "+savedMicrophone+"倍");
        microphoneControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){float gain=1f+progress*.5f;microphoneLabel.setText("送信マイクの増幅  "+gain+"倍");if(fromUser){prefs.edit().putFloat("microphoneGain",gain).apply();if(service!=null)service.setMicrophoneGain(gain);}}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });add(settings,microphoneControl,8);add(settings,text("まず1.5〜2倍で確認。音が割れる場合は下げてください。",11,MUTED),4);
        gainLabel=text("",14,INK);add(settings,gainLabel,22);
        gainControl=new SeekBar(this);gainControl.setMax(4);gainControl.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));gainControl.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        float savedGain=IntercomService.normalizeReceiveGain(prefs.getFloat("receiveGain",2f));gainControl.setProgress(Math.round((savedGain-1f)*2));gainLabel.setText("受信音量  "+savedGain+"倍");
        gainControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){float gain=1f+progress*.5f;gainLabel.setText("受信音量  "+gain+"倍");if(fromUser){prefs.edit().putFloat("receiveGain",gain).apply();if(service!=null)service.setReceiveGain(gain);}}
            public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}
        });add(settings,gainControl,8);add(settings,text("音が割れる場合は、音量を下げてください。",11,MUTED),4);
        TextView help=text("Bluetoothイヤホンを接続してから使用してください。イヤホンの通話ボタンで送信開始・停止を切り替えます。停止後、次の操作まで約1秒かかります。",12,MUTED);help.setLineSpacing(dp(3),1);add(settings,help,22);
        TextView footer=text("PERSONAL INTERCOM  /  "+BuildConfig.VERSION_NAME,10,MUTED);footer.setLetterSpacing(.12f);footer.setGravity(Gravity.CENTER);add(root,footer,28);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);scroll.setClipToPadding(false);scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;}
            else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();}
            view.setPadding(left,top,right,bottom);return insets;
        });setContentView(scroll);scroll.requestApplyInsets();render();
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout card(){LinearLayout view=column();view.setPadding(dp(18),dp(18),dp(18),dp(18));view.setBackground(surface(CARD,20,BORDER));return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private android.graphics.drawable.GradientDrawable surface(int color,int radius,int stroke){android.graphics.drawable.GradientDrawable drawable=new android.graphics.drawable.GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));drawable.setStroke(dp(1),stroke);return drawable;}
    private android.graphics.drawable.Drawable touchSurface(int color,int radius,int stroke){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x225fffff),surface(color,radius,stroke),surface(-1,radius,-1));}
    private android.graphics.drawable.GradientDrawable gradient(int first,int last,int radius,int stroke){android.graphics.drawable.GradientDrawable view=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{first,last});view.setCornerRadius(dp(radius));view.setStroke(dp(1),stroke);return view;}
    private android.graphics.drawable.Drawable primary(boolean active){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),gradient(active?0xffffcab0:0xffa69fff,active?0xffff9fc0:0xff8debf6,14,active?0xffffc6d7:0xffced8ff),surface(-1,14,-1));}
    private android.graphics.drawable.Drawable dial(boolean active){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44ffffff),new IntercomArtwork(IntercomArtwork.DIAL,active),surface(-1,108,-1));}
    private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=dp(margin);parent.addView(child,layout);}
    private void addMargin(View view,int margin){LinearLayout.LayoutParams layout=(LinearLayout.LayoutParams)view.getLayoutParams();layout.topMargin=dp(margin);view.setLayoutParams(layout);}
    private void styleButton(Button button){button.setAllCaps(false);button.setTextSize(14);button.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));button.setPadding(dp(10),dp(8),dp(10),dp(8));button.setMinHeight(dp(52));button.setTextColor(BG);button.setBackground(primary(false));}
    private void secondary(Button button){button.setTextColor(INK);button.setBackground(touchSurface(CARD,14,BORDER));}
    private Button button(LinearLayout root,String text,View.OnClickListener click){Button button=new Button(this);styleButton(button);button.setText(text);if(click!=null)button.setOnClickListener(click);root.addView(button,new LinearLayout.LayoutParams(-1,-2));return button;}
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
        String detail=service==null?"未接続":service.getStatus();
        if(connected && (detail.startsWith("受信待機中") || detail.startsWith("● 送信中"))){
            java.util.regex.Matcher count=java.util.regex.Pattern.compile("[（(](\\d+)人[）)]").matcher(detail);
            status.setText(count.find()?count.group(1)+"人で接続中":"通話に接続中");
        }else status.setText(detail);
        stateChip.setText(connected?"●  CONNECTED":active?"●  CONNECTING":"○  OFFLINE");
        stateChip.setTextColor(connected?ACCENT:MUTED);
        route.setText(service == null ? "音声出力：未接続" : service.getRoute());
        headsetState.setText(service == null ? "" : service.getHeadsetStatus());
        join.setEnabled(!active); room.setEnabled(!active); name.setEnabled(!active); store.setEnabled(!active); invite.setEnabled(!active);
        leave.setEnabled(active); ptt.setEnabled(connected); latch.setEnabled(connected);
        boolean talking = active && service.isTalking();
        latch.setText(talking?"送信を停止":"送信を開始");
        ptt.setText(talking?"送信中":"押しながら話す");
        talkHint.setText(talking?"あなたの声を届けています":connected?"ボタンを押している間だけ送信":"接続すると送信できます");
        ptt.setAlpha(connected?1f:.45f);latch.setAlpha(connected?1f:.45f);join.setAlpha(active?.4f:1f);leave.setAlpha(active?1f:.4f);
        if(talking!=previousTalking){latch.setBackground(primary(talking));ptt.setBackground(dial(talking));halo.setBackground(new IntercomArtwork(IntercomArtwork.HALO,talking));previousTalking=talking;}
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
