"""Reuse the existing personal APK's credential; never commit or log it."""
from pathlib import Path
import hashlib, json, struct, zipfile

archive = Path('personal-base/intercom-personal.apk')
policy = json.loads(Path('personal-base/personal-policy.json').read_text())
digest = policy['personal']['rooms']['main']['invites'][0]['sha256']
key = None
with zipfile.ZipFile(archive) as apk:
    for name in apk.namelist():
        if not name.endswith('.dex'):
            continue
        data = apk.read(name)
        count, offset = struct.unpack_from('<II', data, 0x38)
        for i in range(count):
            p = struct.unpack_from('<I', data, offset + i * 4)[0]
            while data[p] & 128:
                p += 1
            p += 1
            value = data[p:data.index(0, p)].decode('utf-8', errors='replace')
            if hashlib.sha256(value.encode()).hexdigest() == digest:
                key = value
assert key and len(key) == 43, 'Existing personal invitation not found'
print('::add-mask::' + key)

p = Path('android/app/build.gradle')
s = p.read_text().replace("applicationId 'jp.es.staffintercom.preview'", "applicationId 'jp.es.staffintercom.personalspeech'")
s = s.replace("versionName '0.2.6'", "versionName '0.2.6-personal-speech'")
p.write_text(s)
p = Path('android/app/src/main/AndroidManifest.xml')
p.write_text(p.read_text().replace('android:label="スタッフインカム"', 'android:label="自分用インカム 文字起こし検証"'))
p = Path('android/app/src/main/java/jp/es/staffintercom/MainActivity.java')
s = p.read_text()
def once(before, after):
    global s
    assert s.count(before) == 1, 'Personal build source changed'
    s = s.replace(before, after)
once('private boolean bound, holding;', 'private boolean bound, holding, autoJoinStarted;')
once('service = ((IntercomService.LocalBinder) b).getService(); applySpeech(false); render();',
     'service = ((IntercomService.LocalBinder) b).getService(); applySpeech(false); render();\n            if (!autoJoinStarted) { autoJoinStarted=true; if (!service.isJoined()) requestJoin(); }')
once('title.setText("スタッフインカム " + BuildConfig.VERSION_NAME + "\\nバックグラウンド通話")',
     'title.setText("自分用インカム\\n文字起こし・会話履歴")')
once('room.setText(prefs.getString("room", "es")); name.setText(prefs.getString("name", ""));',
     'room.setText("main"); name.setText(prefs.getString("name", android.os.Build.MODEL.substring(0, Math.min(25, android.os.Build.MODEL.length())) + "-" + java.util.UUID.randomUUID().toString().substring(0,4)));')
once('root.addView(room); root.addView(name);', 'root.addView(name);')
once('store.setText(prefs.getString("storeId", "")); root.addView(store);', 'store.setText("personal");')
once('root.addView(invite);', 'invite.setText(' + json.dumps(key) + ');')
once('prefs.getBoolean("headsetCalls", false)', 'prefs.getBoolean("headsetCalls", true)')
once('join = button(root, "ルームに参加", v -> requestJoin());', 'join = button(root, "通話に接続", v -> requestJoin());')
once('private void requestJoin() {', 'private void requestJoin() {\n        room.setText("main"); store.setText("personal"); invite.setText(' + json.dumps(key) + ');')
once('getSharedPreferences("intercom", MODE_PRIVATE).edit().putString("storeId", store.getText().toString().trim()).putString("room", room.getText().toString().trim()).putString("name", name.getText().toString().trim()).apply();',
     'getSharedPreferences("intercom", MODE_PRIVATE).edit().putString("name", name.getText().toString().trim()).apply();')
p.write_text(s)
