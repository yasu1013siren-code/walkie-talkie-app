# 検証結果（2026-10-09 JST）

- `npm ci`: 成功。
- `npm test`: Node/Web 15件成功。実Socketで認証成功/空・誤キー拒否/設定なし拒否/店舗とルームの分離/期限切れ退出/認証前TURN取得拒否/信号の大きさと項目制限/再接続をまたぐ試行制限/不正Origin拒否。Webの再接続・旧サーバーへキー非送信・拒否時マイク停止・PTT回帰を含む。
- `npm audit --omit=dev`: 既知の報告0件（依存96、production90）。これはすべての脆弱性が存在しない保証ではない。
- 公開HTTPS/既知の静的資源: 通常GETで取得。結果はREVIEW.md。攻撃的な本番試験なし。
- Androidの全追跡コードとManifest/Gradle: 点検済み。WebViewがない最新ネイティブ版を対象とした。
- ローカルAndroidビルド: JDK17はあるがGradle/Android SDKがなく未実行。追加CIで単体試験とDebug APKビルドを実施し、結果を追記する。
- GitHub Actionsの結果: 保存後に確認する。
- 実機・TURN実relay・Render内部設定・既存署名の配布APK検証: 未実施。後日の手順はSETUP_AND_TEST.md。

認証関連のログ/コード/レポートに実キー・TURNトークン・署名秘密情報は含めていない。テストで使用する乱数キーは実サービスのキーではない。
