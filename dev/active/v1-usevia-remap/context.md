# Context: usevia.appでキー1個をリマップする（V1）

## このタスクの位置づけ

QMKキーボードのAndroidリマップアプリ構想（Vault: `Human/Projects/QMKキーボードAndroidリマップアプリ構想.md`）のうち、「後者（汎用WebHIDポリフィルブラウザ）」から着手すると決めた最初のV1。詳細な調査経緯・2つのアプローチの比較はVaultノート側が一次情報源。

## 重要な決定事項（要再確認なし）

- 対象サイトはusevia.appのみ（vial.rocksは対象外）
- デバイス選択UIは最小限（1台なら自動選択、複数ならシンプル一覧）
- 実装はKotlin + androidx.webkit（制約上の一択、選択の余地なし）
- V1完了条件: usevia.app上でキー1個リマップし実機で動作確認

## 未解決・要検証（Tasksで潰す）

- usevia.appのUA判定挙動（T0で検証）
- HIDレポートディスクリプタからRaw HIDインターフェース（usagePage 0xFF60/usage 0x61）を正しく特定できるか（T3で検証、plan.mdの「主要な技術的リスク」参照）

## 参考リンク

- Spec: [spec.md](./spec.md)
- Plan: [plan.md](./plan.md)
- Tasks: [tasks.md](./tasks.md)
- QMK Raw HID: `raw_hid.h`, `RAW_ENABLE`機能のUsage Page定義（`0xFF60`/Usage `0x61`）
- VIA本体: `the-via/app`（`navigator.hid.requestDevice`のfilter実装を参照する際のソース）
