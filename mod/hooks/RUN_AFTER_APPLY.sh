#!/usr/bin/env bash
# 在 mod/apply.sh 末尾追加调用，或在 workflow 里 apply.sh 之后执行：
#   bash mod/hooks/RUN_AFTER_APPLY.sh "$ROOT"
set -euo pipefail
ROOT="${1:-.}"
SCRIPT="$(cd "$(dirname "$0")" && pwd)/inject_disable_mpv.py"
echo "[mod] running inject_disable_mpv.py on $ROOT"
python3 "$SCRIPT" "$ROOT"
