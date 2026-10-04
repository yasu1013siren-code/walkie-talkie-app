# Google Play有料アプリの購入確認

確認日：2026-10-03（UTC）。実装済み、Play Consoleとの実接続は未完了。

## 採用方式

Google Playでアプリ自体を有料販売し、Play Integrity Standard APIの `appLicensingVerdict=LICENSED` をサーバーで検証する。追加の商品IDやアプリ内課金を作る方式ではない。従来のLVLも有料アプリ向けの公式方式だが、今回はサーバーに参加認可が既にあるため、改変された端末の自己申告を信頼しないIntegrity方式を採用した。

LICENSEDはGoogle Playで取得した利用権の判定であり、決済額・注文番号の証明ではない。テスター、プロモーション、同一アカウントの複数端末、Google Playの共有制度も別途確認が必要。1インストール=1支払い、1店舗購入=全スタッフ利用と広告してはいけない。販売単位の推奨は「利用するGoogle Playアカウントごとの購入」。店舗一括ライセンスは別の販売設計が必要。

## 実装

1. サーバーが参加条件を確認し、ランダム値とSocket ID、店舗・ルーム・名前・招待コード・packageからSHA256のrequestHashを生成。招待の原文はGoogleへ送らない。
2. Androidはプロジェクト番号を受け取り、公式Integrity SDK 1.6.0でトークンを取得。メモリ内だけに保持し、同じ参加要求と共に送信。
3. サーバーはGoogleのdecodeIntegrityTokenをADCで呼ぶ。package、requestHash、発行時刻（過去120秒以内・未来10秒以内）、PLAY_RECOGNIZED、Play署名証明書、versionCode>=18、LICENSEDを確認。購入確認後に招待・定員を再確認し、TURN・他参加者を渡す。
4. チャレンジは120秒・1回限り。別Socket/別ルーム/変更された招待には使用できない。並列要求、退出中の検証完了による再参加も抑止する。
5. Googleエラー・設定不足・未購入・評価不能は拒否。端末の「購入済み」設定、固定テストトークン、オフライン猶予は実装していない。購入確認タイムアウトは90秒。

`NODE_ENV=production` およびNODE_ENV未設定/未知値では購入確認を必須にし、`REQUIRE_PLAY_LICENSE=false` でも無効化できない。公開ルーム設定も禁止する。Releaseアプリは購入確認対応サーバー以外を拒否する。`NODE_ENV=development` / `test` を明示したサーバーとDebugには従来機能の回帰試験用互換経路があるが、販売環境でそれを利用しない。Web/従来APKは販売用サーバーへの参加を拒否される。Web自体のWebRTC・ボタン機能は開発環境で維持する。

確認済み接続の最大継続時間は8時間（招待期限が先ならその期限）。満了時に退出し、再参加で購入確認する。8時間以内の返金・失効を即時検知する仕組みではない。この運用条件と夜通し勤務時の再参加を実機試験し、販売説明に反映する。

## 設定手順（未実施）

- Play Consoleに `jp.es.staffintercom.preview` を登録できるか確認。既に別アカウントで使われている場合は、このIDを維持したままでは公開できないため停止して相談する。
- 保存済み署名鍵を維持。今回鍵を読み出したり再作成していない。Play App Signingの登録方式を選ぶ際、アプリ署名鍵とアップロード鍵の違いを確認し、端末に配信される署名証明書を許可リストに設定する。署名付き配布物・Console登録は未実施。
- Consoleのアプリ完全性からGoogle Cloudプロジェクトをリンクし、Play Integrity APIを有効化。Standard要求を使えるよう設定。
- サーバー実行用サービスアカウントを用意し、公式手順に従ってトークン復号権限を確認。ADC/Workload Identityを優先。Renderで秘密ファイルが必要なら管理画面のSecret Fileを使い、`GOOGLE_APPLICATION_CREDENTIALS`に絶対パスだけを指定。JSON・OAuthトークン・秘密鍵をソース、APK、PR、ログへ出さない。
- サーバー設定：`NODE_ENV=production`、`ALLOW_LEGACY_ROOMS=false`、`PLAY_CLOUD_PROJECT_NUMBER`（数値のプロジェクト番号）、`PLAY_CERTIFICATE_SHA256`（Playアプリ署名証明書SHA256をbase64url・パディング無し43文字。複数はカンマ区切り）。証明書は公開情報だが秘密鍵とは別物。
- 有料価格・国・税・共有可否・テスト購入方法をConsoleで確認し、未承認の設定を保存/公開しない。versionCode18のままの成果は試験用。公開更新前には過去公開版より大きい番号を選ぶ。

## Console・実機で必要な試験

内部/クローズドテストのPlay配布版で、購入/利用権あり、未購入、別アカウント、ログアウト、改変/サイドロード、返金/失効、ネットワーク断、Google障害、Quota超過を試す。Consoleで指定したテスト判定は試験環境に限定し、販売用で常時LICENSEDを返す設定を残さない。

初期Quotaは公式資料で1日10,000（トークン準備/要求と復号それぞれの枠を確認）。今回は再接続時にも再準備するため、接続不良端末による枠消費を監視する。warm-upはインスタンスあたり毎分5回まで。大量展開前にバックオフ・サーバー全体/IP単位の制限とQuota申請を追加評価する。現状はSocketごと毎分10回の参加関連要求を制限。Socketを作り直す攻撃を防ぐWAF/全体制限は未実装で、公開販売前の残項目。

## 公式資料

- https://developer.android.com/google/play/integrity/setup
- https://developer.android.com/google/play/integrity/standard
- https://developer.android.com/google/play/integrity/verdicts
- https://developer.android.com/google/play/licensing/overview
