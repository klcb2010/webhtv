#!/usr/bin/env bash
# 可选：追加到现有 mod/apply.sh 最末尾（全部文件 copy 完成之后）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$MOD/hooks/inject_disable_mpv.py" ]]; then
  echo "[mod] disable MPV via inject_disable_mpv.py"
  python3 "$MOD/hooks/inject_disable_mpv.py" "$ROOT"
fi
