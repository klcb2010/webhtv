#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"

echo "[mod] root=$ROOT"

# ============================================================
# 1. 复制外挂文件
# ============================================================

while IFS= read -r -d '' src; do
  rel="${src#"$MOD/"}"

  case "$rel" in
    apply.sh|README.md|*.md)
      continue
      ;;
    */strings_patch.xml)
      continue
      ;;
    hooks/*)
      continue
      ;;
  esac

  dest="$ROOT/$rel"
  mkdir -p "$(dirname "$dest")"
  cp -f "$src" "$dest"
  echo "[mod] copy $rel"
done < <(find "$MOD" -type f -print0)


# ============================================================
# 2. 删除已经废弃的外挂字幕自动记忆历史实现
# ============================================================

for obsolete in \
  "app/src/main/java/com/fongmi/android/tv/playback/SubtitleRestoreCoordinator.java" \
  "app/src/main/java/com/fongmi/android/tv/playback/SubtitleRestorePolicy.java" \
  "app/src/main/java/com/fongmi/android/tv/playback/SubtitleSource.java" \
  "app/proguard-rules-subtitle.pro"
do
  if [[ -f "$ROOT/$obsolete" ]]; then
    rm -f "$ROOT/$obsolete"
    echo "[mod] removed obsolete $obsolete"
  fi
done


# ============================================================
# 3. 清理历史误提交残留
# ============================================================

if [[ -f "$MOD/hooks/clean_repo_leftovers.py" ]]; then
  python3 "$MOD/hooks/clean_repo_leftovers.py" "$ROOT"
fi


# ============================================================
# 4. 合并 Android Resources
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
    r'(?ms)^[ \t]*'
    r'<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b'
    r'[^>]*\bname="(?P<name>[^"]+)"[^>]*>'
    r'.*?'
    r'</(?P=tag)>[ \t]*$'
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

for name in order:
    target = re.sub(
        r'(?ms)^[ \t]*'
        r'<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b'
        r'[^>]*\bname="'
        + re.escape(name)
        + r'"[^>]*>'
        r'.*?'
        r'</(?P=tag)>[ \t]*\n?',
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
# 5. 检查必要资源
# ============================================================

required=(
  select_global_history_mode
  select_subtitle_language
  select_subtitle_language_value
)

for name in "${required[@]}"; do
  if ! grep -Rqs \
      --include='*.xml' \
      "name=\"$name\"" \
      "$ROOT/app/src/main/res" \
      "$ROOT/app/src/mobile/res" \
      "$ROOT/app/src/leanback/res" \
      2>/dev/null
  then
    echo "[mod] ERROR: required resource missing: $name"
    exit 1
  fi
done

echo "[mod] required resources verified"


# ============================================================
# 6. Hook
# ============================================================

run_hook() {
  local script="$1"

  if [[ -f "$script" ]]; then
    echo "[mod] hook $(basename "$script")"
    python3 "$script" "$ROOT"
  fi
}


run_hook "$MOD/hooks/inject_subtitle.py"

# 已废弃：
# run_hook "$MOD/hooks/inject_subtitle_restore.py"

run_hook "$MOD/hooks/inject_video_ai.py"
run_hook "$MOD/hooks/inject_personal_manifest.py"

run_hook "$MOD/hooks/fix_exo_dv5.py"
run_hook "$MOD/hooks/fix_migrations_keep.py"
run_hook "$MOD/hooks/fix_db_history_schema.py"
run_hook "$MOD/hooks/fix_recyclerview_fixed_size.py"

run_hook "$MOD/hooks/inject_home_sites_retry.py"
run_hook "$MOD/hooks/inject_home_push.py"

run_hook "$MOD/hooks/fix_update_setting_stubs.py"
run_hook "$MOD/hooks/inject_result_msg_ui.py"

run_hook "$MOD/hooks/inject_subtitle_style.py"
run_hook "$MOD/hooks/inject_toast_gate.py"

run_hook "$MOD/hooks/inject_stalled_auto_change.py"


# ============================================================
# 7. 最终检查：绝不允许旧 PROGUARD 残留
# ============================================================

if grep -nE 'PROGUARD_SRC|PROGUARD_DST' "$MOD/apply.sh" 2>/dev/null; then
  echo "[mod] ERROR: obsolete PROGUARD variables remain in apply.sh"
  exit 1
fi

echo "[mod] done"