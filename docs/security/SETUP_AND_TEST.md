# Render設定と後から行う端末確認

本番適用はユーザー確認後。このブランチをpushしてもmainへマージしない。Renderがmain以外を自動デプロイしない設定であることを管理画面で確認する（今回は管理画面未確認）。まず別の検証用サービスを用意する。

## 招待とRender環境

Nodeは.envを自動読込しない。RenderのEnvironmentへ設定する。

1. 暗号学的乱数32バイトをbase64urlにしたキーを、ローカルの安全な場所で生成。短い暗証番号や店名を使わない。生キーをURL、GitHub、ログ、スクリーンショットへ貼らない。SHA-256はUTF-8の生キーそのもの（改行なし）から作る。
2. `ACCESS_POLICY_JSON` は次の構造で、HASHを64桁小文字SHA-256へ置換。例示HASHのままでは有効なキーとして使えない。

```json
{"shopA":{"rooms":{"main":{"maxParticipants":8,"invites":[{"sha256":"HASH","expiresAt":"2026-12-31T15:00:00Z"}]}}}}
```

日時はUTC、IDは英数字/_/-の1〜32文字、maxParticipantsは1〜50。店舗/ルームごとに異なるランダムキーを使う。複数スタッフや段階的交換にはinvites配列を追加する。失効は該当hashの削除、または期限を過去にして環境更新＋再起動。旧hashを残さず、参加者が再参加で拒否されることを検証する。

3. `NODE_ENV=production`、`PUBLIC_ORIGIN=https://検証サービスのホスト`、`ALLOW_LEGACY_ROOMS=false`。未設定または`ACCESS_POLICY_JSON={}`では全員拒否。設定JSON破損は起動失敗。公開参加へ戻るスイッチはない。
4. STUNだけなら `TURN_PROVIDER=rest`, `TURN_URLS=[]`、TURN_SHARED_SECRETなし。つながりにくいネットワークではTURNを設定。
5. 自前coturnはTURN_URLSのJSON配列＋32文字以上のTURN_SHARED_SECRET、TURN_CREDENTIAL_TTL=3600。Cloudflareは `TURN_PROVIDER=cloudflare`, `CLOUDFLARE_TURN_KEY_ID`, `CLOUDFLARE_TURN_API_TOKEN`, `TURN_CREDENTIAL_TTL=32400` をサーバー側だけに設定し、TURN_URLS/TURN_SHARED_SECRETは変数ごと削除する。プロバイダートークンをAPKや公開JSへ入れない。
6. Build command `npm ci`、Start command `npm start`。招待ポリシーとTURN設定のログ出力を追加しない。1インスタンス運用とし、Render/Cloudflareの接続元・WAF・Quotaは管理画面で別途確認する。

## APKと端末

CIの `Security and regression checks` → `android-regression` → `security-review-debug-apk` が検証APK。既存 `Android debug APK` にもAPKがある。ユーザー確認後にダウンロードする。Debug署名は既存APKと異なることがあり、インストール更新が拒否されたら旧版を安易に削除せず署名/アプリIDを確認する。今回保存済み本番署名鍵を使わない。

検証用サーバーのAPKをビルドする場合は、`INTERCOM_SERVER_URL=https://検証サービスのホスト gradle -p android :app:assembleDebug`。標準CI APKは既定の公開URLを指すため、現在の旧サーバーでは安全に参加を拒否する。検証APKの接続先変更はビルド時だけで、招待キーは入力する。

1. WebとAndroidで店舗ID・ルームID・有効キーを入力。2台参加し両方向の音声、画面PTT、送信切替、音量増幅を確認。
2. キー空/誤り/期限切れ/別店舗/別ルームは拒否し、相手の参加者一覧に現れない。期限を短くした検証キーで参加中に期限切れとなり音声/マイク/参加表示が停止することを確認。
3. 検証用サーバー限定で連続誤入力、再接続でも制限が継続し、1分後回復することを確認。本番で総当たり試験しない。
4. Android 16 SCG21でS10ボタンの開始/停止、USB有線、Bluetooth、画面消灯、アプリ切替、30分〜数時間の背景送受信、着信割込み、イヤホン切断時の停止を確認。Webの画面消灯動作はブラウザ制約が残る。
5. 通信を切断→復帰し招待再検証が行われる。再接続直後の自動送信はしない。無効化したキーでは再参加を拒否する。
6. マイク拒否時に送信しない。停止/退出後にマイク使用表示と通知が終わる。新規端末・キー保存・画面復元・Autofillでキーが残らないことを確認。
7. 異なる回線の2台でTURN relayを確認。長時間通話中の資格情報更新と再接続を確認。ログを共有するときはICE/SDP/キーを含めない。
8. TLS不正証明書の検証は管理された検証環境だけで行い接続拒否を確認。旧WebViewラッパーを使う場合は元APK/ソースを別途点検する。今回mainにはWebViewがなく、Webページの認証は標準ブラウザ/許可済み同一Originのラッパーで使用できるが、旧ラッパーの安全性を検証済みとはしない。

本番への切替はサーバー設定＋Webの更新＋新版APKがそろってから。旧APKにはキー入力がなく参加できなくなる。service workerは新キャッシュへ更新されるが、端末でページ更新と招待入力欄の出現を確認する。販売/Google Play購入確認はこの手順とは独立し、保留を継続。
