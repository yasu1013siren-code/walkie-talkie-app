# 次回の開始地点

販売準備PR #2、ブランチ sale-prep/access-turn-20261002。まずVALIDATION.mdの2026-10-03続行結果と最新mainを比較する。

- 実装/ローカル検証：API36、Play Integrity購入確認、Cloudflare短期TURN、問い合わせ先設定、価格資料。Node15件/Android28件、APK/AABビルド、arm64 ELF/APK ZIP16KB整列を確認。
- 判断待ち：価格候補（少数店舗¥4,980、一般運用¥9,980も比較）、購入アカウント単位の販売と提供期間、サポート、事業用問い合わせメール、TURN契約/予算。
- アクセス待ち：Play Console/Google Cloudのプロジェクト・署名証明書・サーバーADC、TURN検証認証情報。秘密鍵や資格情報を公開PR/会話へ貼らない。既存署名鍵を再作成しない。
- 未検証：購入/返金の実判定、外部relay、Bluetooth/S10/M2、背景送受信、16KB実機、複数台負荷、長時間、WAF/全体Quota、審査。
- 通常の回帰検証は `npm ci && npm test`、JDK21/SDK36で `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:bundleRelease --no-daemon`。CLIのJava互換は17。公開環境を変更せず検証する。
- サーバーのNODE_ENV未設定は購入確認必須。開発試験にはNODE_ENV=development/testを明示。販売用ReleaseとWebで購入確認を省略しない。最大8時間で再参加。Play配布前に更新用versionCodeを決める。

本番反映・契約・ストア公開は承認後の別工程。今回のRelease AABは配布用の保存済み鍵で署名していない。
