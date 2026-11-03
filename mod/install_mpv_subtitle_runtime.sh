#!/usr/bin/env bash
# MPV-BRANCH-ONLY —— 安装/刷新 MPV 字幕字体与颜色运行时补丁
#
#   bash mod/install_mpv_subtitle_runtime.sh
#
# 作用：
#   1. 校验当前仓库处于 MPV 分支（非 MPV 直接跳过，退出码 0）
#   2. 校验锚点：mod/apply.sh、mod/hooks/inject_mpv_phase_c.py、SubtitleFontManager.java
#   3. 幂等注册 hook 到 mod/apply.sh（已注册则跳过）
#   4. 立即执行一次 hook（构建期注入源码）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/mod"
HOOK="$MOD/hooks/fix_mpv_subtitle_runtime.py"
APPLY="$MOD/apply.sh"
PHASE_C="$MOD/hooks/inject_mpv_phase_c.py"
FONT_MGR="$MOD/app/src/main/java/com/fongmi/android/tv/subtitle/SubtitleFontManager.java"

echo "[mod] install_mpv_subtitle_runtime ROOT=$ROOT"

# 1) 分支防护：只允许 MPV 分支
if ! command -v git >/dev/null 2>&1; then
  echo "[mod] ERROR git not found, cannot verify MPV branch" >&2
  exit 1
fi
BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
if [[ "$BRANCH" != "MPV" ]]; then
  echo "[mod] skip: current branch '$BRANCH' != 'MPV' (this patch only applies to the MPV branch)"
  exit 0
fi
echo "[mod] branch check ok: MPV"

# 2) 锚点校验
missing=0
for f in "$APPLY" "$PHASE_C" "$FONT_MGR" "$HOOK"; do
  if [[ ! -f "$f" ]]; then
    echo "[mod] ERROR missing anchor: $f" >&2
    missing=1
  fi
done
if [[ "$missing" != "0" ]]; then
  echo "[mod] ERROR anchors missing, abort" >&2
  exit 1
fi
if ! grep -q 'inject_mpv_phase_c.py' "$APPLY"; then
  echo "[mod] ERROR mod/apply.sh has no inject_mpv_phase_c.py anchor" >&2
  exit 1
fi
echo "[mod] anchor check ok"

# 3) 幂等注册：必须排在 inject_mpv_phase_c 之后（phase_c 会重写 MpvSubtitleStylePolicy）
if grep -q 'fix_mpv_subtitle_runtime.py' "$APPLY"; then
  echo "[mod] hook already registered in mod/apply.sh"
else
  {
    echo ""
    echo "# MPV-BRANCH-ONLY: 字幕字体/颜色运行时刷新（必须在 inject_mpv_phase_c 之后）"
    echo 'if [[ -f "$MOD/hooks/fix_mpv_subtitle_runtime.py" ]]; then'
    echo '  echo "[mod] fix_mpv_subtitle_runtime"'
    echo '  python3 "$MOD/hooks/fix_mpv_subtitle_runtime.py" "$ROOT" || exit 1'
    echo 'fi'
  } >> "$APPLY"
  echo "[mod] registered hook in mod/apply.sh"
fi

# 4) 权限 + 立即执行
chmod +x "$HOOK" "$0" 2>/dev/null || true
python3 "$HOOK" "$ROOT"
echo "[mod] install_mpv_subtitle_runtime done"
