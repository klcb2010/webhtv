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

merge() {
  local patch="$1" target="$2"
  [[ -f "$patch" ]] || return 0

python3 - "$patch" "$target" <<'PY'
import re
import sys
from pathlib import Path

patch_file = Path(sys.argv[1])
target_file = Path(sys.argv[2])
patch = patch_file.read_text(encoding="utf-8")

node_pattern = re.compile(
    r'(?ms)^[ \t]*<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b[^>]*\bname="(?P<name>[^"]+)"[^>]*>.*?</(?P=tag)>[ \t]*$'
)

entries = {}
order = []
for m in node_pattern.finditer(patch):
    name = m.group("name")
    if name not in entries:
        order.append(name)
    entries[name] = m.group(0).strip()

if not entries:
    raise SystemExit(f"[mod] ERROR: no Android resources found in {patch_file}")

if target_file.exists():
    target = target_file.read_text(encoding="utf-8")
else:
    target_file.parent.mkdir(parents=True, exist_ok=True)
    target = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'

for name in order:
    target = re.sub(
        r'(?ms)^[ \t]*<(?P<tag>string-array|integer-array|plurals|string|bool|color|dimen|integer)\b[^>]*\bname="'
        + re.escape(name)
        + r'"[^>]*>.*?</(?P=tag)>[ \t]*\n?',
        '',
        target,
    )

insert = "\n".join(entries[name] for name in order) + "\n"
if "</resources>" in target:
    target = target.replace("</resources>", insert + "</resources>", 1)
else:
    target = target.rstrip() + "\n" + insert

target_file.write_text(target, encoding="utf-8")
print(f"[mod] merged {len(order)} resources -> {target_file}")
PY

}

merge "$MOD/app/src/main/res/values/strings_patch.xml" "$ROOT/app/src/main/res/values/strings.xml"
merge "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
merge "$MOD/app/src/leanback/res/values/strings_patch.xml" "$ROOT/app/src/leanback/res/values/strings.xml"
if [[ -f "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml" ]]; then
  merge "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml"
else
  merge "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
fi

required=(
  select_global_history_mode
  select_subtitle_language
  select_subtitle_language_value
)

for name in "${required[@]}"; do
  if ! grep -Rqs --include='*.xml' "name=\"$name\"" \
      "$ROOT/app/src/main/res" \
      "$ROOT/app/src/mobile/res" \
      "$ROOT/app/src/leanback/res" 2>/dev/null; then
    echo "[mod] ERROR: required resource missing: $name"
    exit 1
  fi
done

echo "[mod] required resources verified"

# 执行原本的 hooks 路径
if [[ -f "$MOD/hooks/inject_subtitle.py" ]]; then python3 "$MOD/hooks/inject_subtitle.py"; fi
if [[ -f "$MOD/hooks/inject_video_ai.py" ]]; then python3 "$MOD/hooks/inject_video_ai.py" "$ROOT"; fi
if [[ -f "$MOD/hooks/inject_personal_manifest.py" ]]; then python3 "$MOD/hooks/inject_personal_manifest.py" "$ROOT"; fi
if [[ -f "$MOD/hooks/fix_exo_dv5.py" ]]; then python3 "$MOD/hooks/fix_exo_dv5.py" "$ROOT"; fi
if [[ -f "$MOD/hooks/fix_migrations_keep.py" ]]; then python3 "$MOD/hooks/fix_migrations_keep.py" "$ROOT"; fi
if [[ -f "$MOD/hooks/fix_db_history_schema.py" ]]; then python3 "$MOD/hooks/fix_db_history_schema.py" "$ROOT"; fi
if [[ -f "$MOD/hooks/inject_github_proxy_setting.py" ]]; then python3 "$MOD/hooks/inject_github_proxy_setting.py" "$ROOT"; fi

# ==================== 全量要求补丁清洗 (一站式覆盖) ====================
python3 - "$ROOT" <<'PY'
import sys, re
from pathlib import Path

root = Path(sys.argv[1])

# 1. 手机端：清理加速源默认高亮 & requestFocus
for path in root.rglob("*.java"):
    try:
        content = path.read_text(encoding="utf-8")
        if "SettingPersonal" in path.name or "accelerate" in content.lower():
            new_content = re.sub(r'(\b\w*accelerate\w*\b)\.setSelected\(true\);', r'\1.setSelected(false);', content, flags=re.I)
            new_content = re.sub(r'(\b\w*accelerate\w*\b)\.requestFocus\(\);', '', new_content, flags=re.I)
            if new_content != content:
                path.write_text(new_content, encoding="utf-8")
                print(f"[mod] Cleared selection/focus in {path.name}")
    except Exception:
        pass

# 2. TV 端 & 双端通用：精简 AI 推荐展示（仅留 片名 + 年代 + 简短简介）
for path in root.rglob("*AiRecommend*.java"):
    try:
        content = path.read_text(encoding="utf-8")
        # 匹配拼接描述的逻辑，精简格式
        if "setText" in content or "StringBuilder" in content:
            # 抹除大段详细描述拼接，只留 (年代) - 一句话简介
            pattern = r'/\*AI_FORMAT_START\*/.*?/\*AI_FORMAT_END\*/'
            replacement = '''
            StringBuilder sb = new StringBuilder();
            if (item.getName() != null) sb.append(item.getName());
            if (item.getYear() != null && !item.getYear().isEmpty()) sb.append(" (").append(item.getYear()).append(")");
            if (item.getBrief() != null && !item.getBrief().isEmpty()) sb.append(" - ").append(item.getBrief());
            textView.setText(sb.toString());
            '''
            new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)
            if new_content != content:
                path.write_text(new_content, encoding="utf-8")
                print(f"[mod] Trimmed AI recommendation text in {path.name}")
    except Exception:
        pass

# 3. TV 端：修复布局高亮、焦点 & 恢复阴影层级
for xml_path in root.rglob("activity_video.xml"):
    try:
        content = xml_path.read_text(encoding="utf-8")
        # 确保加回 elevation 恢复浅色阴影，加回 focusable 保证 TV 高亮
        if 'id="@+id/site"' in content or 'id="@+id/flag"' in content:
            # 恢复卡片阴影 elevation
            content = re.sub(r'android:elevation="0dp"', 'android:elevation="4dp"', content)
            # 确保线路行到 AI 推荐的右键焦点联动
            content = re.sub(r'android:id="@+id/site"', 'android:id="@+id/site" android:nextFocusRight="@+id/ai_recommend"', content)
            xml_path.write_text(content, encoding="utf-8")
            print(f"[mod] Patched TV UI focus & elevation in {xml_path.name}")
    except Exception:
        pass

PY

echo "[mod] done"