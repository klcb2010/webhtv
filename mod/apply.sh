#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"

echo "[mod] root=$ROOT"

# ============================================================
# Copy mod files to source tree
# ============================================================

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

# ============================================================
# Merge Android resources
# ============================================================

merge() {
  local patch="$1"
  local target="$2"

  [[ -f "$patch" ]] || return 0

  python3 - "$patch" "$target" <<'PY'
import re
import sys
from pathlib import Path

patch_file = Path(sys.argv[1])
target_file = Path(sys.argv[2])

patch = patch_file.read_text(encoding="utf-8")

node_pattern = re.compile(
    r'(?ms)^[ \t]*<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b'
    r'[^>]*\bname="(?P<name>[^"]+)"[^>]*>.*?</(?P=tag)>[ \t]*$'
)

entries = {}
order = []

for m in node_pattern.finditer(patch):
    name = m.group("name")

    if name not in entries:
        order.append(name)

    entries[name] = m.group(0).strip()

if not entries:
    raise SystemExit(
        f"[mod] ERROR: no Android resources found in {patch_file}"
    )

if target_file.exists():
    target = target_file.read_text(encoding="utf-8")
else:
    target_file.parent.mkdir(parents=True, exist_ok=True)
    target = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '</resources>\n'
    )

# Remove existing nodes with same names,
# then append patched entries.
for name in order:
    target = re.sub(
        r'(?ms)^[ \t]*<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b'
        r'[^>]*\bname="'
        + re.escape(name)
        + r'"[^>]*>.*?</(?P=tag)>[ \t]*\n?',
        '',
        target,
    )

insert = "\n".join(entries[name] for name in order) + "\n"

if "</resources>" in target:
    target = target.replace(
        "</resources>",
        insert + "</resources>",
        1,
    )
else:
    target = target.rstrip() + "\n" + insert

target_file.write_text(target, encoding="utf-8")

print(
    f"[mod] merged {len(order)} resources -> {target_file}"
)
PY
}

merge \
  "$MOD/app/src/main/res/values/strings_patch.xml" \
  "$ROOT/app/src/main/res/values/strings.xml"

merge \
  "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" \
  "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"

merge \
  "$MOD/app/src/leanback/res/values/strings_patch.xml" \
  "$ROOT/app/src/leanback/res/values/strings.xml"

if [[ -f "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml" ]]; then
  merge \
    "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" \
    "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml"
else
  merge \
    "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" \
    "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
fi

# ============================================================
# Verify required Android resources
# ============================================================

required=(
  select_global_history_mode
  select_subtitle_language
  select_subtitle_language_value
)

for name in "${required[@]}"; do
  if ! grep -Rqs --include='*.xml' "name=\"$name\"" \
      "$ROOT/app/src/main/res" \
      "$ROOT/app/src/mobile/res" \
      "$ROOT/app/src/leanback/res" \
      2>/dev/null; then

    echo "[mod] ERROR: required resource missing: $name"
    exit 1
  fi
done

echo "[mod] required resources verified"

# ============================================================
# Assrt subtitle auto-match hooks
# ============================================================

if [[ -f "$MOD/hooks/inject_subtitle.py" ]]; then
  python3 "$MOD/hooks/inject_subtitle.py"
fi

# ============================================================
# Video AI recommendation
# ============================================================

if [[ -f "$MOD/hooks/inject_video_ai.py" ]]; then
  python3 "$MOD/hooks/inject_video_ai.py" "$ROOT"
fi

# ============================================================
# Personal manifest
# ============================================================

if [[ -f "$MOD/hooks/inject_personal_manifest.py" ]]; then
  python3 "$MOD/hooks/inject_personal_manifest.py" "$ROOT"
fi

# ============================================================
# ExoPlayer DV5 fix
# ============================================================

if [[ -f "$MOD/hooks/fix_exo_dv5.py" ]]; then
  python3 "$MOD/hooks/fix_exo_dv5.py" "$ROOT"
fi

# ============================================================
# Migration protection
# ============================================================

if [[ -f "$MOD/hooks/fix_migrations_keep.py" ]]; then
  python3 "$MOD/hooks/fix_migrations_keep.py" "$ROOT"
fi

# ============================================================
# History database schema fix
# ============================================================

if [[ -f "$MOD/hooks/fix_db_history_schema.py" ]]; then
  python3 "$MOD/hooks/fix_db_history_schema.py" "$ROOT"
fi

# ============================================================
# GitHub Proxy setting
# ============================================================

if [[ -f "$MOD/hooks/inject_github_proxy_setting.py" ]]; then
  python3 "$MOD/hooks/inject_github_proxy_setting.py" "$ROOT"
fi

# ============================================================
# Final verification
#
# selector_item is TV/Leanback-only.
# Mobile must NEVER reference it.
# ============================================================

echo "[mod] checking Mobile selector_item leakage..."

if grep -Rqs \
    --include='*.java' \
    'R\.drawable\.selector_item' \
    "$ROOT/app/src/mobile/java" \
    2>/dev/null; then

  echo
  echo "[mod] ERROR: selector_item leaked into Mobile source."
  echo "[mod] Mobile Java must not reference R.drawable.selector_item."
  echo
  echo "[mod] offending files:"
  
  grep -Rni \
      --include='*.java' \
      'R\.drawable\.selector_item' \
      "$ROOT/app/src/mobile/java" \
      2>/dev/null || true

  echo
  echo "[mod] Please keep selector_item usage TV/Leanback-only."
  exit 1
fi

echo "[mod] mobile selector_item check passed"

# ============================================================
# Verify TV selector resource
# ============================================================

TV_SELECTOR="$ROOT/app/src/leanback/res/drawable/selector_item.xml"

if [[ ! -f "$TV_SELECTOR" ]]; then
  echo
  echo "[mod] ERROR: TV selector_item.xml missing:"
  echo "[mod] $TV_SELECTOR"
  echo
  exit 1
fi

echo "[mod] TV selector_item.xml verified"

# ============================================================
# Final status
# ============================================================

echo "[mod] all checks passed"
echo "[mod] done"
