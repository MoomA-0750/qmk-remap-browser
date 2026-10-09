#!/usr/bin/env bash
# Compose のコードから「無印 Material 3」の書き方を探す。見つかれば 1 で終わる。
# 使い方: check_m3e.sh [検査するディレクトリ]   例外は同じ行に「// m3e-allow: 理由」
# テーマを定義するファイル（Theme.kt・Color.kt・Shape.kt・Type.kt）は色と形の直書きを許す。
set -u
root="${1:-.}"
fail=0

# Compose を import している .kt だけを見る（build・third_party は除く）
mapfile -t files < <(grep -rlE --include='*.kt' '^import androidx\.compose\.' "$root" 2>/dev/null \
  | grep -vE '/(build|third_party)/')
[ "${#files[@]}" -eq 0 ] && { echo 'm3e: Compose のファイルなし'; exit 0; }

report() { # $1=説明 $2=grep -E の式 $3=テーマファイルを除くか(1/0)
  local hits
  hits=$(grep -nE "$2" "${files[@]}" 2>/dev/null \
    | grep -v 'm3e-allow' \
    | { if [ "$3" = 1 ]; then grep -vE '/(Theme|Color|Shape|Type)\.kt:'; else cat; fi; })
  if [ -n "$hits" ]; then
    printf '\n## %s\n%s\n' "$1" "$hits"
    fail=1
  fi
}

report 'material3 のワイルドカード import（検査をすり抜ける）' \
  '^import androidx\.compose\.material3\.\*' 0
report '無印の部品 → 置き換え表を見る' \
  '^import androidx\.compose\.material3\.(CircularProgressIndicator|LinearProgressIndicator|NavigationBar|NavigationBarItem|NavigationRail|NavigationRailItem|BottomAppBar|MediumTopAppBar|LargeTopAppBar|ExtendedFloatingActionButton)$' 0
report 'MaterialTheme(...) でテーマを張っている → MaterialExpressiveTheme' \
  '(^|[^.A-Za-z])MaterialTheme[[:space:]]*\(' 0
report 'tween / 自前の spring → MaterialTheme.motionScheme.*Spec()' \
  '(^|[^A-Za-z.])(tween|spring)[[:space:]]*[(<]' 0
report '色の直書き → MaterialTheme.colorScheme' \
  '(^|[^A-Za-z])Color\(0x' 1
report '角丸の直書き → MaterialTheme.shapes / MaterialShapes' \
  'RoundedCornerShape\(' 1

if [ "$fail" = 0 ]; then echo 'm3e: 問題なし'; fi
exit "$fail"
