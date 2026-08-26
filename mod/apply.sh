#!/usr/bin/env bash
# Apply mod/ overlays onto repo root. Run from repo root: bash mod/apply.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"

echo "[mod] root=$ROOT"
echo "[mod] mod=$MOD"

# 1) Overlay full files (exclude scripts and string patches)
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

# 2) Merge string patches into strings.xml if keys missing
merge_strings() {
  local patch="$1"
  local target="$2"
  if [[ ! -f "$patch" ]]; then
    echo "[mod] skip missing patch $patch"
    return
  fi
  if [[ ! -f "$target" ]]; then
    echo "[mod] WARN missing target $target"
    return
  fi
  if grep -q 'name="setting_home_history"' "$target"; then
    echo "[mod] strings already contain setting_home_history: $target"
    return
  fi
  # Insert before closing </resources>
  local tmp
  tmp="$(mktemp)"
  awk -v patchfile="$patch" '
    /<\/resources>/ {
      while ((getline line < patchfile) > 0) print line
      close(patchfile)
    }
    { print }
  ' "$target" > "$tmp"
  mv "$tmp" "$target"
  echo "[mod] merged strings into $target"
}

merge_strings "$MOD/app/src/main/res/values/strings_patch.xml" \
  "$ROOT/app/src/main/res/values/strings.xml"
merge_strings "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" \
  "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"

echo "[mod] done"
