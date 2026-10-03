# 販売準備の検証結果

実施日：2026-10-02。開始時にGitHub mainを取得し、指定基準 `66ffd6564bb3202a6c3b54d19de8e352098990e2` と一致することを確認。

作業ブランチ：`sale-prep/access-turn-20261002`。

## 完了した範囲

- 店舗・ルーム単位の招待、期限、定員をサーバーで検証。招待ハッシュを秘密環境変数で設定し、原文はソース・独自ログに保存しない。
- 既存ルームを維持する既定設定と、販売用に公開ルームを禁止する設定。店舗間・従来ルームとの分離。
- 認可後にTURN REST方式の一時ICE認証情報を渡す仕組み。Android・Web双方の取得・適用・10分ごとの更新。
- 新クライアントが古い未対応サーバーへ招待コードを送らないプロトコル確認。
- Android検証先のHTTPS URLをビルド環境変数で切替。
- 販売資料、設定手順、実機試験手順、継続費用と未決定事項。

## 実行した確認

| 確認 | 結果 | 実証範囲 |
|---|---|---|
| `npm ci` | 成功 | lockfileに基づく依存導入 |
| `npm test` | 7件成功 | 設定異常、招待期限、不正・未認可参加、満員、店舗分離、信号制御、TURN HMAC、設定順序・再取得、退出/再参加、試行制限、Webのプロトコル待機・音声設定・拒否時停止、既存Webボタン |
| `:app:testDebugUnitTest` | 12件成功 | 既存通話ボタン8件、受信増幅2件、新ICE設定2件。Robolectric SDK35での検証 |
| `:app:assembleDebug` | 成功 | JDK17/Gradle8.13/SDK35。検証用デバッグビルドのみ |
| `git diff --check` | 成功 | 空白・パッチの整合性 |

期限試験の待機タイムアウトは、1秒間隔の失効確認の境界で誤判定しないよう5秒とした。初回試行制限テストの期待回数を修正し、最終テストはすべて成功した。

AndroidのapplicationId `jp.es.staffintercom.preview`、versionCode18/versionName0.2.4、受信既定2.0倍と1〜3倍調整を維持した。WebRTCの音声・バックグラウンドサービス・S10/M2連携の中心処理は維持し、接続参加の認可とICE設定の経路を追加した。新署名鍵・パスワードを作成・変更・公開していない。配布鍵を使った署名検証とAPK配布は今回行っていない。新しい販売用versionCodeは今後の配布時に決める。

GitHub ActionsにもNode/Android検証を追加した。ただし本ファイルの成功結果はローカル実行に基づく。Actions側の成功を先取りして主張しない。

## 未確認・販売前の残作業

- 実機でのバックグラウンド送受信、S10/M2型番ごとの挙動、増幅効果・音割れ、電池・長時間・複数台。
- 外部TURNサービスへの接続、実際のrelay candidate、UDP/TCP/TLS、時刻同期、1時間超の資格情報更新時の疎通。現在確認したのは設定生成とクライアント適用処理であり、中継成功ではない。
- 本番Renderの契約プラン・環境変数・ログ設定と負荷。運用構成は変更していない。
- API36移行、Play審査/FGS/Data safety/AAB/署名登録、ネイティブライブラリの16KBページ対応確認。
- 価格、販売単位、運営者・問い合わせ先、サービス提供期間、TURN契約、購入ライセンス検証。

mainのマージ、本番デプロイ、サーバー設定変更、有料契約、ストア公開を行っていない。

## 続き：API36・Play購入確認・販売条件準備

実施日：2026-10-03（UTC）。既存ドラフトPR #2、remote head `68d039c2cba48998b3af9c009d22b7bddf37a80f` を取得して続行。作業開始時と保存前のmainは `66ffd6564bb3202a6c3b54d19de8e352098990e2` のまま。前節はSDK35での初回履歴であり、以下が今回の検証結果。

### 変更

- compileSdk/targetSdk36。system bars/cutout/IMEのInsetsを反映。マイク/Foreground Service/Telecom/背景音声の中心処理と権限宣言は維持。
- Play Integrity Standard SDK1.6.0とサーバーのGoogle復号APIによる利用権確認。パッケージ/署名証明書/版/時刻/LICENSED/要求ハッシュを確認し、期限付き一回限りチャレンジをSocketと参加情報に結合。未購入・評価不能・通信失敗・未設定では参加不可。
- サーバーは既定/productionで購入確認必須。未設定NODE_ENVやfalse設定で販売用検証を省略できない。開発/testとDebugだけに既存回帰試験の経路を限定。Releaseと販売用Webには省略経路なし。
- 検証済みセッションは最大8時間または招待期限まで。再接続/再参加で新しい確認を行う。再接続前のトークン応答を新しいSocketへ適用しない。Google障害時のオフライン猶予はない。
- Cloudflare短期TURN資格情報API、秘密環境変数、応答検証、タイムアウト、Socket内資格情報再利用。既定TTL9時間とし8時間の接続を保護、9時間未満設定は拒否。従来REST共有秘密方式も維持。
- 価格候補/人数別24か月採算、3社TURN比較と契約/秘密設定/疎通手順、問い合わせ先設定、Console準備と実機試験をdocs/salesへ追加。
- 確認済み事業用連絡先が見つからないため、Androidの `INTERCOM_SUPPORT_EMAIL` を設定可能にした。未指定、未公開。

### 今回実行した確認

| 確認 | 結果 | 範囲と限界 |
|---|---|---|
| `npm ci` | 成功 | 更新したlockfileで依存導入 |
| `npm test` | **15件成功** | 従来参加/分離/期限/信号/Webボタン、購入判定受理・拒否、チャレンジのSocket/要求結合、Google障害拒否、productionの実Socketによるトークンなし参加拒否、TURN APIの契約形式と異常系。Google/TURN実サービス成功ではない |
| `:app:testDebugUnitTest` | **28件成功** | SDK35と36それぞれ14件。通話ボタン各8、増幅各2、ICE各2、Insets/IMEと不正ライセンス要求各2。TelecomはRobolectricの許可Shadow使用。Bluetooth実機信号は模擬していない |
| `:app:assembleDebug` | 成功 | SDK36/JDK21/Gradle8.13/AGP8.13.2。検証用Debug APK |
| `:app:bundleRelease` | 成功 | Release AAB生成。保存済み配布鍵での署名・Consoleアップロードは未実施 |
| arm64 ELF 16KB整列 | 成功 | Debug APK/Release AABのlibjingle_peerconnection_so.so、3つのPT_LOADが16384整列。scripts/check-native-pages.pyで再現可能 |
| APK ZIP整列 | 成功 | build-tools35の `zipalign -c -P 16 4`。Playが生成する最終APKや16KB端末の起動成功は未確認 |
| APKメタデータ | 成功 | applicationId jp.es.staffintercom.preview、min26、compile36/target36、versionCode18/versionName0.2.4 |
| `git diff --check` / JS構文確認 | 成功 | パッチ空白とserver/play-license/clientの構文 |
| ソース差分 | 確認 | mainとの比較でAndroidManifestとIntercomCallServiceの変更なし。増幅既定2.0/1〜3、WebRTC音声・S10/M2中心処理を維持 |

環境準備で最初のビルド/試験は失敗したが、最終成功と区別して記録する：通信proxy設定、JDKのコンパイラ起動環境、SDK36用RobolectricのJava21要件、管理環境のTLS信頼ストア、SDK36 FileDescriptor用Java21 test-only module exportを解決。テストを無効化/スキップして成功扱いにしていない。公式Robolectric資料 https://robolectric.org/getting-started/ と https://github.com/robolectric/robolectric/releases/ に従いCIもJDK21/SDK36に更新した。アプリのJavaソース/ターゲット互換は17のまま。

最終ビルド識別用SHA256（配布/署名証明ではない）：

- Debug APK：`ecf6a497f9af348895087f80b85bedb9ea1ce67407caed9ddc9b590d3c8b58eb`
- Release AAB：`8871a8b9285002bebd1cd333fac54cb5265e3d2d128c3980932c86eb39e8f1e9`

ローカル検証を記録した。更新後GitHub Actionsの結果を先取りして成功と表現しない。生成物は検証専用で、利用者向け更新APKとして配布しない。

### 未完了・必要な決定/アクセス

1. **Play Console/Google Cloud**：アプリID登録、保存済み署名鍵とPlay App Signingの扱い、クラウドプロジェクト番号、配信署名証明書、サーバーADC権限。内部/クローズドテストで購入・未購入・返金・別アカウント・改変・Quota/障害を確認。購入確認の本番実証は未完了。
2. **価格/利用条件**：候補¥1,480/¥2,980/¥4,980/¥9,980。少数店舗試験は¥4,980候補、全中継と長期運用なら¥9,980も比較。最終価格、購入アカウント単位の販売、サービス期間、初期赤字予算、サポート/返金/終了条件をユーザーが決める。人数増加で永続採算を保証しない。
3. **事業用問い合わせ先**：ユーザー指定の受信可能なメール、運営者、対応時間、ポリシーURLを確定。個人連絡先を推測していない。
4. **TURN**：Cloudflareを推奨候補とするが、契約/支払い/鍵作成は未実施。ユーザーが事業アカウントと予算を選び、秘密管理で検証サーバーに設定。実relay・UDP/TCP/TLS・9時間TTLと長時間再参加を試す。
5. **実機/公開準備**：Android16・Samsung・S10/M2で背景送受信、2/5/10台、長時間、マイク/通知拒否、着信割込み、音量、16KB端末。現行設定は最大8時間で再参加が必要。公開前にIP/全体Quota制限、FGS/Data safety申告、最終署名/AAB/更新番号とストア審査を確認。

鍵とパスワードの再作成/出力/公開はしていない。mainマージ、本番設定変更/デプロイ、有料契約/支払い、価格の確定、連絡先の新規作成/公開、Console課金、ストア公開は行っていない。成果は既存販売準備ブランチ/ドラフトPRにのみ保存する。
