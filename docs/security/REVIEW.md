# インカム安全対策（2026-10-09 JST）

## 対象と変更

開始時main: `66ffd6564bb3202a6c3b54d19de8e352098990e2`。
既存ドラフトPR #2: `sale-prep/access-turn-20261002`、`45f1b17f0abb685f7c99eae377e55ccf9b377b81`。PRの差分と関連ファイルを読み、招待検証、TURN、クライアントの認証待ち処理を再利用した。mainから独立した `security/room-access-20261009` に実装。Play Integrity・購入確認・販売関連ファイルや依存関係は追加していない。main・既存PR・Render設定・デプロイ・販売状態は変更していない。

- 店舗IDとルームIDの組に限定した招待キーをSHA-256と定数時間比較で検証。キーは32〜256文字、生成時は32ランダムバイト以上。
- 設定がない場合は全参加拒否。`ALLOW_LEGACY_ROOMS=true` は起動エラー。test/developmentでも認証迂回なし。
- 無効・期限切れ・別ルームの参加を拒否。参加していないSocketはシグナリング・PTT通知・TURN情報を取得できない。参加期限は招待期限または最大8時間。1秒間隔とイベント受信時に期限を確認し、期限切れ参加者へ退出要求を返す。
- 招待無効の再参加要求では旧参加状態も解除。期限切れの送信先にもシグナリングを渡さない。
- 参加試行はSocketごと10回/分、直結接続元ごと60回/分、プロセス全体300回/分。再接続で接続元カウンタは消えない。カウンタ数4096まで。偽造可能なX-Forwarded-Forを採用しない。
- Socket.IOパケット64KiB、SDP48KiB、ICE4096文字、名前40文字、ID32文字。信号はoffer/answer/ICEの項目のみ。signal/PTTはSocketごと100イベント/秒。
- TURNは認証済みのSocketだけへ配布。REST共有秘密とCloudflare APIトークンはサーバー環境のみ。Cloudflare応答・タイムアウトを検証し、失敗時は参加を拒否。資格情報をログに出さない。
- WebとAndroid双方が参加承認前の送信を禁止。旧サーバーへ招待キーを送らず待機後に拒否。Androidの通話/PTT/音声増幅/S10/Bluetooth/背景サービスの処理を維持。
- WebにCSP、nosniff、DENY、no-referrer、Permissions-Policy、HSTSを追加。ブラウザのOriginを公開HTTPS Originへ制限。Originなしのネイティブ通信も招待認証は必須。

## Android全コードの点検

対象: MainActivity.java、IntercomService.java、IntercomCallService.java、TestCallService.java、Manifest、Gradle設定、既存テスト。

| 点検事項 | 確認結果 |
|---|---|
| WebViewの読込先・遷移・JSブリッジ | 最新main/PR #2はネイティブActivity/サービス。WebView・addJavascriptInterface・URL遷移ハンドラが存在しないため対象なし。Webページには旧ラッパー向けIntercomNative呼出しが残るが、現在APKにJSブリッジはない。旧v0.1.x APKの安全性保証にはその実体/コードが別途必要。 |
| 接続先 | HTTPS OriginのみをGradleで検証するSERVER_URLに限定。ユーザー入力で接続先を変えない。 |
| TLS | 独自TrustManager、許可型HostnameVerifier、TLS検証を無効化するコードなし。OS/Socket.IO既定の検証を使用。証明書ピンニングは未実装。 |
| 平文 | usesCleartextTraffic=falseを維持。UDPメディアはWebRTCの暗号化で保護され、HTTPの平文設定とは別。 |
| マイク | RECORD_AUDIOの実行時許可を確認。サービスでも再確認。参加承認までAudioTrackを無効化。START_NOT_STICKYで勝手なマイク再起動をしない。 |
| exported | Launcher Activityのみ無条件exported。入力Intentで通話開始しない。通話サービス2件はBIND_TELECOM_CONNECTION_SERVICEのOS権限付き。IntercomServiceと診断Receiverは非exported。 |
| PendingIntent | 明示的対象＋FLAG_IMMUTABLE。 |
| 保存/バックアップ | allowBackup=false。招待入力はパスワード表示、状態保存・Autofill無効、設定ファイルに保存しない。参加開始で入力欄を消去し、サービス退出でメモリ参照を解除。再接続の間だけメモリ保持。Java文字列の完全なメモリ消去は保証しない。 |
| ログ | 認証情報を含み得るWebRTC/例外/SDP失敗ログを抑制。招待キー・TURN資格情報をログやレポートへ出さない。端末全体/ライブラリ内部のログ未実測。 |
| 署名 | 追跡ファイルに秘密鍵・署名パスワードなし。ignoreへ鍵/署名設定を追加。今回署名バックアップは開かず、既存鍵を変更していない。Debug APKはテスト署名。 |

## 公開サイトの読み取り調査

2026-10-09 00:54 JST前後（HTTP Date: 2026-10-08 15:54:28 GMT）、公開HTTPSトップページは200。通常のGETだけを使用し、参加・シグナリング・総当たり・秘密ファイル探索などの本番試験はしていない。

確認応答には `x-powered-by: Express`、Cloudflare/Renderの識別ヘッダー、`cache-control: public, max-age=0` があり、CSP/Strict-Transport-Security/X-Content-Type-Options/X-Frame-Options/Referrer-Policy/Permissions-Policyは見当たらなかった。この観測だけで全ルートのヘッダーを保証するものではない。

| 公開資源 | HTTP | バイト数 | SHA-256 |
|---|---:|---:|---|
| /client.js | 200 | 13496 | 0306083f3fa003ac59056f86b107b81f67d875fbd4832a4f7291d556dd8d3b42 |
| /sw.js | 200 | 957 | 12f4b09d71e9415c238a87ee3f7949f39fe8d9d4d78db7142a665086cd93fdad |
| /manifest.webmanifest | 200 | 227 | 82b46042662bfe144cb7a475220a0e4b2147dde00bcd89bb660dd3a8f7065269 |
| /style.css | 200 | 3265 | a8dca8d76f8872725b6a54871f9125ced0e71055533f489ae9dc0f202d1e868c |
| /icon.svg | 200 | 275 | 685cec68c44b2b143f9afd00b4ca0f2314b444eb37197aae0420010c23a802dd |

公開client.jsにはstoreId/inviteCodeがない。公開版が今回の認証UIへ更新されていないことを確認。Renderの環境変数・TURN契約/秘密情報・アクセスログ・WAF・自動デプロイ条件・サービス内部権限・リージョン・複数インスタンス構成はアクセス権がなく未確認。

## 残るリスク

- キーを知る人は参加できる。共有キーは個人の本人確認ではない。流出時はキーを取り消して再発行する。録音・悪意ある認可参加者の行動は防げない。
- 招待の削除/更新は環境設定変更とサーバー再起動で反映。既存P2P音声や発行済みTURN資格情報を瞬時に無効化する仕組みではない。標準クライアントは失効通知で停止するが、改造クライアント間の既存P2Pはサーバーが強制遮断できない。
- 発行済みTURNはTTLまで使える。Cloudflare TTLは9時間で最大8時間の通話を覆う。漏えい時はサーバー側プロバイダーキーの失効も検討し、公式管理画面で対応する。
- 接続元制限はRenderプロキシ配下で複数利用者に共有される可能性がある。プロキシ仕様を確認するまで転送ヘッダーを信頼しない。大規模運用にはWAF/接続数制限/共有レート制限が必要。現実装は1プロセスを想定し、再起動でカウンタはリセットされる。
- WebRTCでは参加者に相手の接続先IPが見える場合がある。サーバーによるシグナリング改ざん防御/端末侵害対策/外部監査は含まれない。
- 依存ライブラリの全脆弱性やOS/イヤホン動作を保証するものではない。Androidの実機背景送受信とTLS不正証明書拒否は後日試験が必要。

設定と端末確認は [SETUP_AND_TEST.md](SETUP_AND_TEST.md)。実行した検証は [VALIDATION.md](VALIDATION.md)。
