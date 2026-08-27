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
  [[ -f "$patch" && -f "$target" ]] || { echo "[mod] skip $target"; return 0; }
  local tmp
  tmp="$(mktemp)"
  # Insert any missing string/array lines from patch before </resources>
  python3 - "$patch" "$target" "$tmp" <<'PY'
import sys, re
patch, target, out = sys.argv[1:4]
pt = open(patch, encoding="utf-8").read()
tg = open(target, encoding="utf-8").read()
# extract entries by name=
entries = []
for m in re.finditer(r'(?:^[ \t]*<string name="([^"]+)"[\s\S]*?</string>|^[ \t]*<string-array name="([^"]+)"[\s\S]*?</string-array>)', pt, re.M):
    block = m.group(0)
    name = m.group(1) or m.group(2)
    entries.append((name, block if block.startswith(" ") or block.startswith("\t") else "    " + block.strip()))
missing = []
for name, block in entries:
    if f'name="{name}"' not in tg:
        missing.append(block if block.endswith("\n") else block + "\n")
if not missing:
    open(out, "w", encoding="utf-8").write(tg)
    print(f"[mod] all keys present: {target}")
else:
    if "</resources>" not in tg:
        raise SystemExit(f"no </resources> in {target}")
    tg2 = tg.replace("</resources>", "".join(missing) + "</resources>", 1)
    open(out, "w", encoding="utf-8").write(tg2)
    print(f"[mod] merged {len(missing)} entries into {target}")
PY
  mv "$tmp" "$target"
}

merge_strings "$MOD/app/src/main/res/values/strings_patch.xml" "$ROOT/app/src/main/res/values/strings.xml"
merge_strings "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
echo "[mod] done"
