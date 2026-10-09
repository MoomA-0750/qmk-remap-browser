#!/usr/bin/env bash
# CC の PostToolUse フック。書いた .kt を check_m3e.sh で調べ、外れていれば CC へ返して直させる。
f=$(jq -r '.tool_input.file_path // empty')
case "$f" in *.kt) ;; *) exit 0 ;; esac
[ -f "$f" ] || exit 0
dir=$(cd "$(dirname "$0")" && pwd)
if ! out=$("$dir/check_m3e.sh" "$f"); then
  printf 'Material 3 Expressive の決まりから外れています（無印M3を書かない。置き換え表は android-m3-expressive スキル）:\n%s\n' "$out" >&2
  exit 2
fi
exit 0
