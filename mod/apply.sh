#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"
echo "[mod] root=$ROOT"
while IFS= read -r -d '' src; do
  rel="${src#"$MOD/"}"
  case "$rel" in apply.sh|README.md|*.md) continue;; */strings_patch.xml) continue;; esac
  dest="$ROOT/$rel"
  mkdir -p "$(dirname "$dest")"
  cp -f "$src" "$dest"
  echo "[mod] copy $rel"
done < <(find "$MOD" -type f -print0)
merge() {
  local patch="$1" target="$2"
  [[ -f "$patch" && -f "$target" ]] || return 0
  python3 -c '
import re,sys
pt=open(sys.argv[1],encoding="utf-8").read(); tg=open(sys.argv[2],encoding="utf-8").read()
entries=[]
for m in re.finditer(r"(?:^[ \t]*<string name=\"([^\"]+)\"[\s\S]*?</string>|^[ \t]*<string-array name=\"([^\"]+)\"[\s\S]*?</string-array>)", pt, re.M):
  block=m.group(0); name=m.group(1) or m.group(2)
  if not block.startswith(" "): block="    "+block.strip()
  if not block.endswith("\n"): block+="\n"
  entries.append((name,block))
miss=[b for n,b in entries if f"name=\"{n}\"" not in tg]
if miss:
  tg=tg.replace("</resources>", "".join(miss)+"</resources>",1)
  open(sys.argv[2],"w",encoding="utf-8").write(tg)
  print("[mod] merged",len(miss),sys.argv[2])
else: print("[mod] ok",sys.argv[2])
' "$patch" "$target"
}
merge "$MOD/app/src/main/res/values/strings_patch.xml" "$ROOT/app/src/main/res/values/strings.xml"
merge "$MOD/app/src/main/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
merge "$MOD/app/src/leanback/res/values/strings_patch.xml" "$ROOT/app/src/leanback/res/values/strings.xml"
if [[ -f "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml" ]]; then
  merge "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/leanback/res/values-zh-rCN/strings.xml"
else
  merge "$MOD/app/src/leanback/res/values-zh-rCN/strings_patch.xml" "$ROOT/app/src/main/res/values-zh-rCN/strings.xml"
fi
echo "[mod] done"
