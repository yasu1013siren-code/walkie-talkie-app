# API36移行と互換性確認

確認日：2026-10-03（UTC）。compileSdk/targetSdkを36へ更新。minSdk26、applicationId、versionCode18/versionName0.2.4、WebRTC SDK、送受信とS10/M2の中心処理、増幅設定、保存済み署名鍵を維持。

Google Play公式要件は2026-08-31から新規/更新の通常AndroidアプリにAPI36以上を要求する。延期申請期限を利用する前提にはしていない。

| 影響 | コード確認・対応 | 残る確認 |
|---|---|---|
| 強制edge-to-edge | システムバー・切り欠き・IMEのInsetsをScrollViewへ適用。API26〜29には旧Insets経路 | Android15/16、縦横、ジェスチャー/3ボタン、キーボード、タブレットで視認性 |
| Foreground Service | microphone/mediaPlayback/phoneCallと対応権限を維持。Activityからユーザー操作で起動し、Foreground化後に音声初期化 | マイク拒否/取消、通知拒否、OSによる停止、画面消灯、8時間。PlayのFGS申告/審査 |
| バックグラウンド開始 | BOOT_COMPLETED、常時自動再起動、dataSync/mediaProcessing FGSは使わない。既存の再起動方針を維持 | サービス終了後に勝手に録音開始しないこと、利用者への再参加案内 |
| Audio focus | 通話FGS中にフォーカス要求。音声中断時に送信を止める既存処理を維持 | 電話着信/他アプリ音楽/Bluetooth再接続 |
| Telecom | self-managed PhoneAccount、MANAGE_OWN_CALLS、phoneCall FGSを維持。S10/M2の操作ロジック変更なし | Android16・実際のS10/M2・Samsungで応答/切断/ミュート、他通話との競合 |
| JobScheduler quota | WorkManager/JobScheduler/DownloadManagerを音声処理に使用していないため移行不要 | 将来ジョブ導入時に再評価 |
| Predictive back | 戻るキーの独自捕捉なし。Activity非表示でも既存Serviceに通話を保持 | 戻る操作で長押し送信が解除されること、固定送信の通知停止 |
| LAN/Bluetooth | API36のLAN保護は公式移行資料でopt-in。不要な権限追加はしない。BLUETOOTH_CONNECTは既存の許可要求を維持 | LAN制限を有効にした試験、Android次版、SCO/LE Audio、有線との切替 |
| 16KBページ | SDK更新だけでは証明できない。WebRTC .soのELFとAPK ZIPの整列確認を行う | 16KB実機/エミュレータ、Play Console AAB解析。実機成功とは区別 |

Foreground Serviceを備えていても、端末ベンダーの省電力制限で常時稼働が保証されるわけではない。実機試験完了まで販売説明に「全端末で確実」と記載しない。

## 公式資料

- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/guide/practices/page-sizes
