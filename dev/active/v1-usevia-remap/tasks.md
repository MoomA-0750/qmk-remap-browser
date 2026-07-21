# Tasks: usevia.appでキー1個をリマップする（V1）

対応Plan: [plan.md](./plan.md)

各タスクは検証可能な小さい単位。1タスクずつ実装・検証してから次に進む。

- [ ] **T0. 前提検証**: 実機WebView（素の状態、ポリフィルなし）で`usevia.app`を開き、モバイルUAでブロックされないか確認する。ブロックされる場合はデスクトップUA偽装タスクをここに追加する
- [ ] **T1. プロジェクト雛形**: Android Studioプロジェクト作成（Kotlin, Compose, minSdk 26, `androidx.webkit`依存追加）。空のWebViewで`usevia.app`を表示できることを確認（この時点ではWebHID未対応でVIA側は接続不可なのが期待挙動）
- [ ] **T2. USB権限・デバイス列挙**: `UsbManager`でUSB接続キーボードを検出し、`PendingIntent`+`BroadcastReceiver`でパーミッションダイアログを表示・許可を受け取る。ログで許可済みデバイス情報が取れることを確認
- [ ] **T3. HIDレポートディスクリプタ取得・パーサー**: `controlTransfer()`でGET_DESCRIPTOR(type=0x22)を取得し、Usage Page/Usageを抽出するパーサーを実装。既知のQMK Raw HIDディスクリプタのバイト列を使ったユニットテストで`0xFF60`/`0x61`のインターフェースを正しく特定できることを確認
- [ ] **T4. `claimInterface`とinterrupt転送**: 対象インターフェースを`force=true`でclaimし、interrupt転送でレポート送受信できることを確認（QMKのraw hid経由で既知のコマンド、例: protocol versionping相当を送り、期待通りの応答をログで確認）
- [ ] **T5. JS<->Kotlinブリッジ（最小HTMLで検証）**: `WebMessageListener`によるJSON-RPC風の非同期ブリッジを実装。usevia.appではなく最小限のテスト用HTMLページで、スタブメソッド呼び出しのPromiseが正しくresolve/rejectされることを確認
- [ ] **T6. `navigator.hid`ポリフィルJS**: `requestDevice`/`getDevices`/`HIDDevice`（open/close/sendReport/sendFeatureReport/receiveFeatureReport/oninputreport）を実装し、T5のブリッジに接続。テスト用HTMLページで実機キーボードとのHID送受信が一往復できることを確認
- [ ] **T7. デバイス選択UI（最小限）**: 許可済みHIDデバイスが0/1/複数の各パターンで、Specの受け入れ条件通りの挙動（0件は空配列、1件は自動選択、複数はシンプルな一覧）になることを確認
- [ ] **T8. ポリフィル注入をusevia.appに統合**: `addDocumentStartJavaScript`でusevia.app読み込み前にポリフィルを注入（`allowedOriginRules`はusevia.appのみ）。usevia.app上でデバイス接続ボタンが機能し、キーボードとして認識されることを確認
- [ ] **T9. V1完了検証（Validate）**: usevia.app上でキー1個のリマップを行い、実機キーボードでそのキーが変更後の割り当てとして動作することを確認する（Specの成功条件そのもの）
