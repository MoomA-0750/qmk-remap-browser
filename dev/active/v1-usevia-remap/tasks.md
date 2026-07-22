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
- [ ] **T5. JS<->Kotlinブリッジ（最小HTMLで検証）**: `WebMessageListener`によるJSON-RPC風の非同期ブリッジを実装。usevia.appではなく最小限のテスト用HTMLページで、スタブメソッド呼び出しのPromiseが正しくresolve/rejectされることを確認
- [ ] **T6. `navigator.hid`ポリフィルJS**: `requestDevice`/`getDevices`/`HIDDevice`（open/close/sendReport/sendFeatureReport/receiveFeatureReport/oninputreport）を実装し、T5のブリッジに接続。テスト用HTMLページで実機キーボードとのHID送受信が一往復できることを確認
- [ ] **T7. デバイス選択UI（最小限）**: 許可済みHIDデバイスが0/1/複数の各パターンで、Specの受け入れ条件通りの挙動（0件は空配列、1件は自動選択、複数はシンプルな一覧）になることを確認
- [ ] **T8. ポリフィル注入をusevia.appに統合**: `addDocumentStartJavaScript`でusevia.app読み込み前にポリフィルを注入（`allowedOriginRules`はusevia.appのみ）。usevia.app上でデバイス接続ボタンが機能し、キーボードとして認識されることを確認
- [ ] **T9. V1完了検証（Validate）**: usevia.app上でキー1個のリマップを行い、実機キーボードでそのキーが変更後の割り当てとして動作することを確認する（Specの成功条件そのもの）
