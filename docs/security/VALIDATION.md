# 検証結果（2026-10-09 JST）

- `npm ci`: 成功。
- `npm test`: Node/Web 15件成功。実Socketで認証成功/空・誤キー拒否/設定なし拒否/店舗とルームの分離/期限切れ退出/認証前TURN取得拒否/信号の大きさと項目制限/再接続をまたぐ試行制限/不正Origin拒否。Webの再接続・旧サーバーへキー非送信・拒否時マイク停止・PTT回帰を含む。
- `npm audit --omit=dev`: 既知の報告0件（依存96、production90）。これはすべての脆弱性が存在しない保証ではない。
- 公開HTTPS/既知の静的資源: 通常GETで取得。結果はREVIEW.md。攻撃的な本番試験なし。
- Androidの全追跡コードとManifest/Gradle: 点検済み。WebViewがない最新ネイティブ版を対象とした。
- ローカルAndroidビルド: JDK17はあるがGradle/Android SDKがなく未実行。追加CIで単体試験とDebug APKビルドを実施し、結果を追記する。
- GitHub Actions（実装コミット `ade733d2477ff7a504fc4a375179fe57beab85de`）: [Security and regression checks / run 37805648805](https://github.com/yasu1013siren-code/walkie-talkie-app/actions/runs/37805648805) のserver-and-web、android-regression両ジョブ成功。Node/Web 15件、Android `:app:testDebugUnitTest` と `:app:assembleDebug` 成功をジョブ手順とビルドログで確認。Android試験には既存S10/音声フォーカス/増幅回帰と新規RtcSettings解析・不正設定拒否を含む。
- 既存CI: [Android debug APK / run 37805630787](https://github.com/yasu1013siren-code/walkie-talkie-app/actions/runs/37805630787) も成功。APK artifactのアップロード成功を確認。
- 検証APK: 上記Security runの `security-review-debug-apk`（artifact ID 11562188785、ZIP 11,108,022 bytes、artifact digest `sha256:86344890d5de6832a01d9c865a62dda7acb7c46efe0b87b3dca1bca234427948`）。ビルドと保存は成功したが端末へのインストール・実通話は未実施。APKは既定の本番URLを指すため、旧サーバーのままでは参加を拒否する。
- 上記CIは実装コードに対する検証。後続の検証結果追記は文書のみ。
- 保存したドラフト: [PR #3](https://github.com/yasu1013siren-code/walkie-talkie-app/pull/3)。mainの基準SHAが変わっていないこととPRがdraft/openであることを確認。
- 実機・TURN実relay・Render内部設定・既存署名の配布APK検証: 未実施。後日の手順はSETUP_AND_TEST.md。

認証関連のログ/コード/レポートに実キー・TURNトークン・署名秘密情報は含めていない。テストで使用する乱数キーは実サービスのキーではない。
