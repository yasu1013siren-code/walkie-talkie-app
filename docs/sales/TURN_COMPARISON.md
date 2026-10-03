# TURN比較・導入準備

公式資料確認日：2026-10-03（UTC）。米ドル、税・為替・追加費用別。アカウント契約、支払い、本番設定、実中継接続は未実施。

| サービス | 現行料金・条件 | 現実装との関係 |
|---|---|---|
| Cloudflare Realtime TURN | SFU/TURN合計の送出1,000GB/月まで無料、超過$0.05/GB。送出はTURNからクライアント向けを計上。セルフサービスは企業SLA保証とは別。中国ネットワーク外。登録時に請求条件確認 | 推奨候補。短期資格情報APIを新実装。共有秘密をHMAC化して使う旧方式とは異なる |
| Metered TURN | 無料500MB/月（ingress+egress）、Growth $99/月・150GB・超過$0.40/GB、Business $199/月・500GB・超過$0.20/GB | ダッシュボード/独自APIで資格情報発行。現実装の共有秘密方式にAPIキーを入れても動かない。今回は比較資料のみ、専用アダプタ未実装 |
| Twilio Network Traversal | 東京/シンガポール/ムンバイ$0.600/GB、米国西部/欧州$0.400/GB、シドニー/サンパウロ$0.800/GB。税別・追加費用の可能性 | サーバーからTokens APIを使用する専用アダプタが必要。今回は未実装 |
| 自前coturn | OSSだがVM・帯域・監視・更新費。RenderのHTTP Web ServiceだけではUDP TURNを置けない | 既存TURN REST方式の共有秘密HMAC経路を維持。VM等は未契約 |

料金の安さと既存WebRTCを維持できる点からCloudflareを第一候補とする。日本からの遅延、制限の厳しい店舗回線、SLA要否を実測してから契約を決める。無料枠は継続保証ではなく、利用額の上限でもない。

## Cloudflareの設定準備

1. ユーザーが契約・利用条件・支払い条件と予算を確認し、CloudflareダッシュボードでRealtime TURNキーを作成する。今回は作成していない。
2. 検証サーバーの秘密環境変数へ `TURN_PROVIDER=cloudflare`、`CLOUDFLARE_TURN_KEY_ID`、`CLOUDFLARE_TURN_API_TOKEN`、`TURN_CREDENTIAL_TTL=32400`（9時間）を設定。`TURN_URLS`と`TURN_SHARED_SECRET`は未設定とし方式を混在させない。
3. サーバーが認可済み参加者に限り、`POST https://rtc.live.cloudflare.com/v1/turn/keys/{key}/credentials/generate-ice-servers`をBearer認証・TTL付きで呼ぶ。長期APIトークンはサーバーにのみ保持し、返された短期ICE資格情報だけをAndroid/Webへ渡す。
4. クライアントの既存10分更新を維持。Cloudflare資格情報はSocket単位でTTLの10分前までメモリ内再利用し、更新要求のたびに未使用のキーを量産しない。更新失敗は退出と再参加案内。Cloudflareの既定は9時間TTLで、明示設定が9時間未満ならサーバー起動を拒否する。9時間TTLは購入確認セッション最大8時間より長く、現在使用中の古いTURN allocationの期限切れを避ける。API公式説明では期限切れallocationは切断されるため、`setConfiguration()`だけで既存relayが必ず切替わるとは仮定しない。8時間超の連続稼働は再参加が必要。
5. プロバイダーの利用額・Quota・エラー率の通知を管理画面で設定。API tokenのローテーションと漏洩時のキー削除/資格情報失効手順を管理者へ引き継ぐ。秘密値はチケットやコマンド履歴に貼らない。

`TURN_PROVIDER=rest` が既定で従来構成を維持する。CloudflareのAPIトークンを `TURN_SHARED_SECRET` として使ってはいけない。

## 接続確認（未実施）

- 検証専用サーバーで参加し、異なる携帯回線/店舗Wi-Fi/UDP遮断回線を組み合わせる。
- 試験時だけPeerConnectionの `iceTransportPolicy=relay`（Androidは `IceTransportsType.RELAY`）で強制中継。試験後に通常へ戻し、販売環境で勝手に強制しない。
- Webの `chrome://webrtc-internals`、Androidの統計取得で選択済みcandidate pairのrelay、bytesSent/Received増加を確認。録音を残さず相互に声の到達を確認し、UDP/TCP/TLS443を別々に試す。
- 10分更新、1時間超、8時間まで、画面消灯、S10/M2、2/5/10台、再接続を試す。古いallocation TTL超過は検証用短TTLで再現して切断・再参加を確認。
- Cloudflare analyticsのegressとアプリ統計を比較して採算資料の仮定を差し替える。API取得成功だけを中継成功と記録しない。

## 公式資料

- https://developers.cloudflare.com/realtime/sfu/platform/pricing/
- https://developers.cloudflare.com/realtime/turn/generate-credentials/
- https://developers.cloudflare.com/realtime/turn/faq/
- https://www.cloudflare.com/terms/
- https://www.metered.ca/pricing
- https://www.metered.ca/docs/turn-rest-api/
- https://www.metered.ca/docs/turn-server-service/creating-turn-credentials/
- https://www.twilio.com/en-us/stun-turn/pricing
- https://www.twilio.com/docs/stun-turn/api
- https://github.com/coturn/coturn
