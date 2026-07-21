# Spec: usevia.appでキー1個をリマップする（V1）

関連: Obsidian Vault [[QMKキーボードAndroidリマップアプリ構想]]

## ゴール

Android上でUSB Host API経由のnavigator.hidポリフィルを注入するカスタムブラウザを実装し、usevia.app上で実機QMK/Viaキーボードのキー1個をリマップして、それが実機に反映されることを確認できる状態をV1完了とする。

## スコープ

- 対象サイト: **usevia.appのみ**（vial.rocks等は対象外。ポリフィル自体はWebHID標準に沿う汎用実装だが、V1で検証・保証するのはusevia.appでの動作のみ）
- 対象デバイス: USB OTG接続のQMK/Viaファームウェア搭載キーボード

## 対象外（Out of Scope）

- usevia.app以外のサイト対応（vial.rocks等）
- 本物のWebHID標準ダイアログに近い作り込まれたデバイス選択UI
- 複数デバイスの同時接続・ホットスワップUX
- User-Agent偽装対応（前提の検証で必要と判明した場合のみ次Specで対応）

## 受け入れ条件（EARS記法）

### USB接続・権限

- WHEN ユーザーがUSB OTG経由でQMK/Viaキーボードを接続する, THE アプリ SHALL Android標準のUSBパーミッションダイアログを表示する
- WHEN ユーザーがUSBパーミッションを許可する, THE アプリ SHALL 該当デバイスのHIDインターフェースを`claimInterface(force=true)`で確保し、以降のHID通信を可能にする

### ブラウザ・ポリフィル注入

- WHEN WebViewが`usevia.app`のページを読み込む, THE アプリ SHALL ページ自身のJSが実行される前に`navigator.hid`ポリフィルを注入する（`WebViewCompat.addDocumentStartJavaScript()`使用、iframe内も対象）
- WHEN 注入されたポリフィルが評価される, THE ポリフィル SHALL `requestDevice()` / `getDevices()` / `HIDDevice`（`open()` / `close()` / `sendReport()` / `sendFeatureReport()` / `receiveFeatureReport()` / `oninputreport`）を標準WebHID仕様と互換の形で提供する

### デバイス選択（最小限UI）

- WHEN usevia.appが`navigator.hid.requestDevice()`を呼び出し、かつ許可済みUSB HIDデバイスが1台のみ接続されている, THE アプリ SHALL 選択ダイアログを出さずそのデバイスを自動選択する
- WHEN usevia.appが`navigator.hid.requestDevice()`を呼び出し、かつ許可済みUSB HIDデバイスが複数接続されている, THE アプリ SHALL デバイス名の一覧をシンプルなリストUIで表示し、ユーザーの選択結果を`requestDevice()`のPromise解決値として返す
- WHEN usevia.appが`navigator.hid.requestDevice()`を呼び出し、かつ許可済みUSB HIDデバイスが0台, THE アプリ SHALL 空配列を返す（標準WebHID仕様と同様の挙動）

### HID通信

- WHEN usevia.appがVIAプロトコルのRaw HIDレポートを`sendReport()`で送信する, THE アプリ SHALL Kotlin側でinterrupt転送を用いて実デバイスへレポートを送信する
- WHEN 実デバイスからHIDレポートが返る, THE アプリ SHALL `oninputreport`イベントを通じてusevia.appのJSにレポートを伝達する

### 成功条件（V1完了の定義）

- WHEN ユーザーがusevia.app上でキーボードの特定キーのキーマップを変更し保存する, THE 変更 SHALL 実機キーボードに反映され、変更後のキー割り当てとして実際に動作する（実機での動作確認をもってV1完了とする）

## 未確認の前提（着手時に最初に検証すること）

- usevia.appがUser-Agent文字列で「モバイル端末か」を判定し、機能をブロックしていないか。ブロックしている場合はデスクトップ向けUA偽装が追加で必要になり、本Specのスコープに影響する

## 参考

- Vault内調査ノート: [[QMKキーボードAndroidリマップアプリ構想]]
- QMK Raw HIDプロトコル: `raw_hid.h`
- VIA本体: `the-via/app`
