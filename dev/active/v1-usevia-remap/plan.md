# Plan: usevia.appでキー1個をリマップする（V1）

対応Spec: [spec.md](./spec.md)

## 実装言語・プラットフォーム

**Kotlin + Android標準WebView（androidx.webkit）**。

USB Host API（`UsbManager`/`UsbDeviceConnection`）とWebView両方にネイティブアクセスできる必要があり、この組み合わせを満たす現実的な代替は存在しないため、これは好みの選択ではなく制約から決まる一択。UI部分はJetpack Compose（`AndroidView`でWebViewをラップ、デバイス選択リストもCompose）を使う。

- minSdk: 26（Android 8.0）。`WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)`で実行時ガードする前提だが、それより低いAPIレベルを積極的にサポートする理由がないため下限として設定
- 依存: `androidx.webkit:webkit`（`addDocumentStartJavaScript`/`WebMessageListener`用）

## アーキテクチャ

```
[usevia.app JS] <--postMessage(JSON-RPC風)--> [WebHidBridge(Kotlin)] <--> [UsbHidTransport(Kotlin)] <--USB Host API--> [実機キーボード]
       ^
       | 起動時にaddDocumentStartJavaScriptで注入
[webhid-polyfill.js（assets同梱）]
```

### コンポーネント

1. **`webhid-polyfill.js`**（`app/src/main/assets/`）
   - `navigator.hid = { requestDevice, getDevices }` と `HIDDevice`（`open/close/sendReport/sendFeatureReport/receiveFeatureReport/oninputreport`）を定義
   - Kotlin側との通信は`WebMessageListener`経由。リクエストに連番`id`を振り、Kotlin側からの`{id, result}`/`{id, error}`応答でPromiseをresolve/rejectする（JSON-RPC風の自前プロトコル）
   - `oninputreport`は逆方向、Kotlin側からの`{event:"inputreport", ...}`をリッスンして発火

2. **`WebHidBridge`（Kotlin, `WebViewCompat.addWebMessageListener`実装）**
   - JSからのRPC呼び出し（`requestDevice`/`getDevices`/`open`/`sendReport`等）を受け、`UsbHidTransport`に委譲
   - 結果を`postWebMessage`でJS側に返す。`oninputreport`用の非同期プッシュも同じチャネルで行う

3. **`UsbHidTransport`（Kotlin）**
   - `UsbManager`でデバイス一覧・パーミッション要求（`PendingIntent` + `BroadcastReceiver`）
   - パーミッション許可後、対象インターフェースを`claimInterface(force=true)`
   - **HIDレポートディスクリプタの取得・パース**: Androidの高水準APIはUSB HIDレポートディスクリプタを直接公開しないため、`controlTransfer()`でGET_DESCRIPTOR（type=0x22）を発行し生バイト列を取得。パースしてUsage Page/Usageを抽出し、**usevia.appが`requestDevice({filters:[{usagePage:0xFF60, usage:0x61}]})`で要求するRaw HIDインターフェース（QMKの`RAW_ENABLE`規約）を、キーボードが同時に持つ他のHIDインターフェース（Boot Keyboard等）から正しく判別する**。V1で技術的に一番リスクが高い部分
   - interrupt転送でレポート送受信（受信は別スレッドでポーリングし、`oninputreport`としてブリッジ経由でJSへプッシュ）

4. **`MainActivity`（Compose）**
   - WebView（`usevia.app`固定表示、`allowedOriginRules`もusevia.appのみに制限）
   - デバイス選択UI: 許可済みHIDデバイスが1台なら自動選択、複数ならシンプルなリスト（Specの受け入れ条件通り、標準WebHIDダイアログ相当の作り込みはしない）

## 主要な技術的リスク・検証事項

- **HIDレポートディスクリプタのパース精度**: 記述子中の複数Top-Level Collectionから正しいUsage Page/Usageペアを拾えるか。QMKのraw_hid実装は`0xFF60`/`0x61`固定なので、パーサーはこのペアの検出に絞ってよく、汎用HIDディスクリプタパーサーとしての完全性は不要
- **usevia.appのUA判定**（Specの未確認の前提）: 実装着手前に実機WebViewで`usevia.app`を素の状態（ポリフィルなし）で開き、モバイルUAでブロックされないか確認する。ブロックされる場合は`WebView.getSettings().setUserAgentString()`でデスクトップUAを設定するタスクを追加する
- **`claimInterface(force=true)`によるカーネルusbhidドライバのデタッチ**: デタッチ後もAndroid側で当該デバイスが安定して認識され続けるか（デタッチ・再アタッチのタイミングでの不整合）は実機検証が必要

## テスト・検証方針

- HIDレポートディスクリプタのパーサーはUSBデバイス不要の純粋なKotlinロジックなので、既知のQMKディスクリプタのバイト列を使ったユニットテストが可能（唯一の自動テスト対象）
- JS<->Kotlinブリッジのプロトコル（id対応・resolve/reject）は、usevia.appではなく最小限のテスト用HTMLページで先に検証する（Tasksで小さく分離）
- それ以外（USB通信・実機動作）は自動テスト困難なため、実機キーボード＋実際のusevia.appでの手動検証に依存する（Specの成功条件通り）
