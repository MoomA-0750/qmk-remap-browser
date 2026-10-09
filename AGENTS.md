# QMK Remap Browser 作業メモ

- 返答は日本語
- usevia.app を WebView で開き、USB Host API 経由の `navigator.hid` ポリフィルを注入して、Android から QMK/VIA キーボードをリマップするアプリ
- 仕様・計画・経緯は `dev/active/v1-usevia-remap/`（spec.md・plan.md・context.md）。構想の一次情報は Obsidian Vault の `Human/Projects/QMKキーボードAndroidリマップアプリ構想.md`
- 共通ルールの正本は Obsidian Vault の `AGENTS.md`（`~/Documents/git-cloned/Obsidian-Vault/AGENTS.md`）
- USB/HID 通信の実体は独立リポジトリ `../qmk-usb-hid-bridge`（`settings.gradle.kts` の `includeBuild` で参照）。その部分の修正はそちらで行う
- commit メッセージは `git commit -F <file>` で渡す

## 画面は Material 3 Expressive

- Android の画面（`app/` の Compose）は Material 3 Expressive で作る。無印の Material 3 の部品や書き方へ戻さない
- 置き換え表は `android-m3-expressive` スキル。書いたら `scripts/check_m3e.sh app` が「問題なし」になること（CC では編集のたびにフックが自動で調べる）
- どうしても無印を使う行には、同じ行の末尾に `// m3e-allow: 理由` を書く
