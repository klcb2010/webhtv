#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"
echo "[mod] root=$ROOT"

while IFS= read -r -d '' src; do
  rel="${src#"$MOD/"}"
  case "$rel" in
    apply.sh|README.md|*.md) continue ;;
    */strings_patch.xml) continue ;;
  esac
  dest="$ROOT/$rel"
  mkdir -p "$(dirname "$dest")"
  cp -f "$src" "$dest"
  echo "[mod] copy $rel"
done < <(find "$MOD" -type f -print0)

merge_strings() {
  local patch="$1"
  local target="$2"
  [[ -f "$patch" && -f "$target" ]] || return 0
  if grep -q 'name="setting_home_history"' "$target"; then
    echo "[mod] strings already patched: $target"
    # still try to add missing keys
    for key in setting_episode_history setting_play_back_to_detail setting_home_vod_auto_load; do
      if ! grep -q "name=\"$key\"" "$target"; then
        :
      fi
    done
  fi
  local need=0
  for key in setting_home_history setting_home_vod_auto_load setting_episode_history setting_play_back_to_detail; do
    if ! grep -q "name=\"$key\"" "$target"; then need=1; break; fi
  done
  if [[ "$need" -eq 0 ]]; then
    echo "[mod] all keys present: $target"
    return 0
  fi
  # Remove old partial keys then re-merge full patch? Simpler: append missing lines only
  local tmp
  tmp="$(mktemp)"
  awk -v patchfile="$patch" '
    BEGIN {
      while ((getline line < patchfile) > 0) {
        if (match(line, /name="([^"]+)"/, a)) keys[a[1]] = line
      }
      close(patchfile)
    }
    {
      if (match($0, /name="([^"]+)"/, a) && (a[1] in keys)) {
        # skip existing, will rewrite from keys at end? keep original
        delete keys[a[1]]
      }
      if ($0 ~ /<\/resources>/) {
        for (k in keys) print keys[k]
      }
      print
    }
  ' "$target" > "$tmp"
  mv "$tmp" "$target"
  echo "[mod] merged strings into $target"
}

merge_strings "$MOD/app/src/main/res/values/strings_patch.xml" "$ROOT/app/src/main/res/values/strings.xml"
merge_strings "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
echo "[mod] done"
