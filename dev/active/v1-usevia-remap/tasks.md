# Tasks: usevia.appでキー1個をリマップする（V1）

対応Plan: [plan.md](./plan.md)

各タスクは検証可能な小さい単位。1タスクずつ実装・検証してから次に進む。

- [x] **T0. 前提検証**: 実機WebView（素の状態、ポリフィルなし）で`usevia.app`を開き、モバイルUAでブロックされないか確認する。ブロックされる場合はデスクトップUA偽装タスクをここに追加する
  - **検証済み（実機Pixel 3、Android 12、WebView 150.0.7871.46、2026-07-22実施）**: `usevia.app`はUser-Agent文字列によるモバイル判定ブロックを**行っていない**。実際には`navigator.hid`の有無によるフィーチャー検出で、未対応時は「USB Detection Error — Right now, we only support browsers that have WebHID enabled」という案内エラーを表示するだけ（Chrome mobile・自作WebViewアプリの両方で同じ挙動を確認）。
  - **結論: デスクトップUA偽装は不要**。`navigator.hid`ポリフィルさえ正しく注入できれば、usevia.app側の追加対応なしにフィーチャー検出を通過できる見込み。
- [x] **T1. プロジェクト雛形**: Android Studioプロジェクト作成（Kotlin, Compose, minSdk 26, `androidx.webkit`依存追加）。空のWebViewで`usevia.app`を表示できることを確認（この時点ではWebHID未対応でVIA側は接続不可なのが期待挙動）
  - **検証済み（本セッションで実施、実機Pixel 3で最終確認2026-07-22）**: `JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug` がBUILD SUCCESSFULで完走し、`app/build/outputs/apk/debug/app-debug.apk`を生成することを確認した。
  - 中身は`MainActivity`(Compose) + `AndroidView`でラップした`WebView`が`https://usevia.app/`を`loadUrl`するだけの最小構成（`app/src/main/java/com/moomatechnica/qmkremap/MainActivity.kt`）。ポリフィル注入・USB結線は未実装（意図通り、T2以降で追加）。
  - **実機検証で発見・修正した不具合**: 実機にインストールして初めて判明したが、`AndroidView`のfactory内で生成した`WebView`に`layoutParams`を明示的に設定していないと、`usevia.app`のJS自体は実行される（logcatにコンソールログが出る）にもかかわらず**画面には何も描画されずComposeの`Surface`背景色だけが表示され続ける**という不具合があった。`WebView(context).apply { layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT); ... }`を明示的に設定することで解消。Compose`AndroidView`にWebViewなど独自の測定・レイアウトロジックを持つViewを载せる際の既知の落とし穴で、Gradleビルドやユニットテストでは検出できず実機での目視確認で初めて発覚した。今後Compose+WebViewを扱う箇所では常にlayoutParamsを明示することを徹底する。
  - この修正後、実機で`usevia.app`が正しく表示され、T0と同じ「USB Detection Error」画面（フィーチャー検出によるもの）が表示されることを確認した。
- [x] **T2. USB権限・デバイス列挙**: `UsbManager`でUSB接続キーボードを検出し、`PendingIntent`+`BroadcastReceiver`でパーミッションダイアログを表示・許可を受け取る。ログで許可済みデバイス情報が取れることを確認
  - **検証済み（実機Pixel 3 + NuPhy Halo75 V2、USB OTG接続、2026-07-22実施）**: `UsbHidTransport`（`app/src/main/java/com/moomatechnica/qmkremap/usb/UsbHidTransport.kt`）を実装し、`MainActivity`に配線。実機ログで`T2: connected USB devices = 1`、`device name=/dev/bus/usb/001/002 vid=6645 pid=13045 product=NuPhy Halo75 V2 interfaceCount=4 hasPermission=false`を確認 → 標準USBパーミッションダイアログ「Allow QMK Remap Browser to access NuPhy Halo75 V2?」が表示 → OKタップ後`T2 permission callback: device=/dev/bus/usb/001/002 granted=true hasPermission=true`を確認した。
  - **実機のUSBトポロジ情報（T3/T4で利用）**: NuPhy Halo75 V2はVID=6645(0x19F5)/PID=13045(0x32F5)、HIDインターフェース4本。インターフェース0はBoot Keyboard（class=3,subclass=1,protocol=1、INエンドポイント8byte）。インターフェース1がIN/OUT両方32byteエンドポイントを持ち、QMKの`RAW_EPSIZE=32`と一致するためRaw HID候補（T4で実際のレポートディスクリプタを取得しT3のパーサーで確定させる）。インターフェース2/3はIN単方向32byte（Consumer Control等と推定、未確定）。
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
