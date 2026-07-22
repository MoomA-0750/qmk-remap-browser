# Tasks: usevia.appでキー1個をリマップする（V1）

対応Plan: [plan.md](./plan.md)

各タスクは検証可能な小さい単位。1タスクずつ実装・検証してから次に進む。

- [ ] **T0. 前提検証**: 実機WebView（素の状態、ポリフィルなし）で`usevia.app`を開き、モバイルUAでブロックされないか確認する。ブロックされる場合はデスクトップUA偽装タスクをここに追加する
  - **要実機検証・本セッションでは未実施**（adb接続済みの実機なし、`/dev/kvm`不在でエミュレータも起動不可のため）。
  - 実機検証手順: Android実機をUSBデバッグ有効化のうえPCに接続し `adb devices` で認識確認 → `./gradlew installDebug` でT1のAPKを実機にインストール → アプリを起動しWebViewで`https://usevia.app/`が表示された状態で、ページが「モバイル端末非対応」等の警告でブロックされていないか目視確認する。ブロックされていた場合は`WebView.getSettings().setUserAgentString()`でデスクトップUAに差し替えるタスクを本ファイルに追加する。
- [x] **T1. プロジェクト雛形**: Android Studioプロジェクト作成（Kotlin, Compose, minSdk 26, `androidx.webkit`依存追加）。空のWebViewで`usevia.app`を表示できることを確認（この時点ではWebHID未対応でVIA側は接続不可なのが期待挙動）
  - **検証済み（本セッションで実施）**: `JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug` がBUILD SUCCESSFULで完走し、`app/build/outputs/apk/debug/app-debug.apk`を生成することを確認した。
  - 中身は`MainActivity`(Compose) + `AndroidView`でラップした`WebView`が`https://usevia.app/`を`loadUrl`するだけの最小構成（`app/src/main/java/com/moomatechnica/qmkremap/MainActivity.kt`）。ポリフィル注入・USB結線は未実装（意図通り、T2以降で追加）。
  - 「WebViewがusevia.appを実際に表示できるか」「WebHID未対応の挙動になるか」は目視確認が必要なため、これは実機検証待ち（T0の実機検証と合わせて確認できる）。
- [ ] **T2. USB権限・デバイス列挙**: `UsbManager`でUSB接続キーボードを検出し、`PendingIntent`+`BroadcastReceiver`でパーミッションダイアログを表示・許可を受け取る。ログで許可済みデバイス情報が取れることを確認
  - **要実機検証・本セッションでは未実施**（USB OTG接続の実機キーボードもadb接続済み実機もないため、`UsbManager`との実通信は原理的に検証不可）。コードは未着手（T4の`claimInterface`と合わせて実装した方が効率的なため）。
  - 実機検証手順: USB OTGケーブル経由でQMK/Viaキーボードを実機Androidに接続 → アプリ起動（またはUSB_DEVICE_ATTACHEDインテントフィルタ経由での自動起動を実装した場合はそれをトリガーに）→ `UsbManager.deviceList`にキーボードが列挙されることをログ出力で確認 → `PendingIntent`+`BroadcastReceiver`によるパーミッションダイアログが表示され、許可後に`UsbManager.hasPermission(device)`が`true`になることを`adb logcat`で確認する。
- [x] **T3. HIDレポートディスクリプタ取得・パーサー**: `controlTransfer()`でGET_DESCRIPTOR(type=0x22)を取得し、Usage Page/Usageを抽出するパーサーを実装。既知のQMK Raw HIDディスクリプタのバイト列を使ったユニットテストで`0xFF60`/`0x61`のインターフェースを正しく特定できることを確認
  - **検証済み（本セッションで実施）**: `HidReportDescriptorParser`（`app/src/main/java/com/moomatechnica/qmkremap/hid/HidReportDescriptorParser.kt`）と`RawHidInterfaceLocator`（同ディレクトリの`RawHidInterfaceLocator.kt`）を実装。QMKのRaw HIDディスクリプタ（`raw_hid.h`のUsage Page`0xFF60`/Usage`0x61`固定、RAW_EPSIZE=32を再現したバイト列）に加え、Boot Keyboard・Consumer Control・LEDのネストCollectionを持つディスクリプタなど既知のバイト列を使ったJUnitテスト（`app/src/test/java/com/moomatechnica/qmkremap/hid/HidReportDescriptorParserTest.kt`、6ケース）を追加し、`JAVA_HOME=/opt/android-studio/jbr ./gradlew test`が**6 tests, 0 failures**で成功することを確認した。
  - パーサーは`controlTransfer()`でのUSB実通信は含まない純粋なKotlinロジック（バイト列 -> Usage Page/Usageのトップレベルコレクション抽出）なので、この単体では実機不要で完結する。**実機のUSBデバイスから実際に`GET_DESCRIPTOR(type=0x22)`でディスクリプタを取得する部分（`UsbDeviceConnection.controlTransfer()`呼び出し）はT2/T4で実装し、そこは実機検証が必要**。
- [ ] **T4. `claimInterface`とinterrupt転送**: 対象インターフェースを`force=true`でclaimし、interrupt転送でレポート送受信できることを確認（QMKのraw hid経由で既知のコマンド、例: protocol versionping相当を送り、期待通りの応答をログで確認）
  - **要実機検証・本セッションでは未実施**（USB経由の実通信そのものが対象のため原理的に検証不可）。コードは未着手。`UsbHidTransport`としてT2と合わせて実装する想定（plan.md参照）。
  - 実機検証手順: T2完了後、`claimInterface(force=true)`で対象インターフェース（T3のパーサーが特定した`0xFF60`/`0x61`のインターフェース）をclaim → `UsbDeviceConnection.bulkTransfer()`（またはinterrupt転送）でQMK raw hidの既知コマンド（例: `id_protocol_version`相当のレポート）を送信 → 応答バイト列を`adb logcat`で確認し、期待するプロトコルバージョン値が返ることを確認する。あわせて、claim後もカーネルのusbhidドライバがデタッチされたままAndroid側でデバイスが安定して認識され続けるか（抜き差ししても不整合が起きないか）も確認する。
- [ ] **T5. JS<->Kotlinブリッジ（最小HTMLで検証）**: `WebMessageListener`によるJSON-RPC風の非同期ブリッジを実装。usevia.appではなく最小限のテスト用HTMLページで、スタブメソッド呼び出しのPromiseが正しくresolve/rejectされることを確認
  - **部分実装・検証済み（本セッションで実施）**: 実機・実WebViewを必要としない「idの相関・resolve/reject判定」ロジックだけを切り出して実装した。
    - Kotlin側: `WebHidRpcRouter`（`app/src/main/java/com/moomatechnica/qmkremap/bridge/WebHidRpcRouter.kt`、`WebHidRpc.kt`）。JSからのRPC呼び出しをメソッドハンドラにディスパッチし、`id`を保ったまま結果を組み立てる。JUnitテスト（`app/src/test/java/com/moomatechnica/qmkremap/bridge/WebHidRpcRouterTest.kt`、5ケース、非同期呼び出しが完了順不同でも各`id`が正しく紐づくケースを含む）で検証済み。`./gradlew test`で**5 tests, 0 failures**。
    - JS側: `webhid-polyfill.js`内の`createWebHidPolyfill()`のPromise管理部分（後述T6と共通）。Node.jsのテスト（`web/webhid-polyfill.test.js`、`node --test web/webhid-polyfill.test.js`で実行、9ケース中の該当分）でid相関・resolve/reject・応答の遅延到着や不正JSONの無視を検証済み、**9 tests, 0 failures**。
  - **実機検証待ちの部分**: `WebViewCompat.addWebMessageListener()`本体の実装・登録（Kotlin側で実際にJSへメッセージを届ける配線）、および実際のWebView上でのテスト用HTMLページを使った検証は未実施（コード未着手）。T8での統合時に合わせて実配線する。
  - 実機検証手順: `WebHidBridge`（未実装）で`WebViewCompat.addWebMessageListener(webView, "QmkRemapNative", setOf("*"), listener)`を実装 → 最小限のテスト用HTMLをassetsに配置しWebViewで読み込み → ページ内JSから`window.QmkRemapNative.postMessage(...)`でスタブメソッドを呼び出し、対応するPromiseが`adb logcat`上のログと一致した値でresolve/rejectされることを確認する。
- [ ] **T6. `navigator.hid`ポリフィルJS**: `requestDevice`/`getDevices`/`HIDDevice`（open/close/sendReport/sendFeatureReport/receiveFeatureReport/oninputreport）を実装し、T5のブリッジに接続。テスト用HTMLページで実機キーボードとのHID送受信が一往復できることを確認
  - **部分実装・検証済み（本セッションで実施）**: `app/src/main/assets/webhid-polyfill.js`に`requestDevice`/`getDevices`/`HIDDevice`（`open`/`close`/`sendReport`/`sendFeatureReport`/`receiveFeatureReport`/`oninputreport`/`addEventListener`/`removeEventListener`）を実装。ネイティブブリッジ（`postMessage`を持つオブジェクト）を差し替え可能にしてあるため、実WebViewなしでNode.jsから直接テストできる。`web/webhid-polyfill.test.js`（9ケース: requestDeviceの0件/1件解決、エラー時reject、並行呼び出しの非同期解決順序、sendReportのUint8Array→配列変換、oninputreportの発火とDataView内容、不明deviceId/不正JSON/未知idの無害な無視）を`node --test web/webhid-polyfill.test.js`で実行し**9 tests, 0 failures**を確認した。
  - **実機検証待ちの部分**: 実際のWebView（`window.navigator.hid`への実注入）・実USBキーボードとのHID送受信の一往復は完全に未検証（原理的にNode.js単体では確認不可）。
  - 実機検証手順: T8のポリフィル注入配線完了後、テスト用HTMLページ（またはusevia.app）で`navigator.hid.requestDevice()`を呼び出し実機キーボードが選択できること → `device.open()`後に`device.sendReport()`でQMK raw hidの既知コマンドを送信 → `device.oninputreport`が実機からの応答で発火し、期待するバイト列が返ることを`adb logcat`または画面表示で確認する。
- [ ] **T7. デバイス選択UI（最小限）**: 許可済みHIDデバイスが0/1/複数の各パターンで、Specの受け入れ条件通りの挙動（0件は空配列、1件は自動選択、複数はシンプルな一覧）になることを確認
  - **部分実装・検証済み（本セッションで実施）**: 分岐ロジックを`DeviceSelectionPolicy`（`app/src/main/java/com/moomatechnica/qmkremap/device/DeviceSelectionPolicy.kt`、`UsbHidDeviceInfo.kt`）として実機・UIから独立させて実装。0台→`ReturnEmpty`、1台→`AutoSelect`、複数台→`ShowPicker`の3分岐をJUnitテスト（`app/src/test/java/com/moomatechnica/qmkremap/device/DeviceSelectionPolicyTest.kt`、3ケース、spec.mdの受け入れ条件文言に対応）で検証済み。`./gradlew test`で**3 tests, 0 failures**。
  - **実機検証待ちの部分**: 実際に許可済みデバイス一覧を`UsbManager`から取得してこのポリシーに渡す配線、複数台時のCompose一覧UI表示、ユーザーのタップ結果を`requestDevice()`のPromise解決値として返す部分は未実装。
  - 実機検証手順: USB OTGハブ等でQMK/Viaキーボードを0台/1台/2台以上接続した状態をそれぞれ用意 → `navigator.hid.requestDevice()`を呼び出し、0台なら空配列がJS側に返る、1台ならダイアログなしで自動選択される、複数台ならシンプルな一覧が表示されタップしたデバイスが選択されることを、それぞれ`adb logcat`と画面表示で確認する。
- [ ] **T8. ポリフィル注入をusevia.appに統合**: `addDocumentStartJavaScript`でusevia.app読み込み前にポリフィルを注入（`allowedOriginRules`はusevia.appのみ）。usevia.app上でデバイス接続ボタンが機能し、キーボードとして認識されることを確認
  - **要実機検証・本セッションでは未実施**（実際のusevia.appとの結線・動作確認そのものが対象のため原理的に検証不可）。コードは未着手（T2/T4/T5/T6の実配線が前提のため）。
  - 実機検証手順: T2/T4/T5/T6の実配線を統合後、`WebViewCompat.addDocumentStartJavaScript(webView, polyfillJs, setOf("https://usevia.app"))`で本ポリフィルを注入 → 実機でアプリを起動しusevia.appを開く → デバイス接続ボタンを押し、実機キーボードが一覧または自動選択で認識されることを画面表示で確認する。`allowedOriginRules`がusevia.app以外のオリジンでポリフィルを注入しないことも合わせて確認する。
- [ ] **T9. V1完了検証（Validate）**: usevia.app上でキー1個のリマップを行い、実機キーボードでそのキーが変更後の割り当てとして動作することを確認する（Specの成功条件そのもの）
  - **要実機検証・本セッションでは未実施**（V1完了条件そのものであり実機なしでは原理的に検証不可）。
  - 実機検証手順: T8完了後、usevia.app上で対象キーボードを認識させる → 任意のキー1個の割り当てを変更し保存する → 実機キーボードでそのキーを実際に押し、変更後の割り当て通りに動作する（例: 別のキーコードとして入力される）ことを目視・入力確認する。これが確認できた時点でV1完了。
