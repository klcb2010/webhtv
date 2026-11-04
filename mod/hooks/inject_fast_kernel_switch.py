#!/usr/bin/env python3
"""MOD_FAST_KERNEL_SWITCH_V1
非解析源 + 已有有效 URL 时，跳过 SiteApi.playerContent 重取链，直接走本地 switchPlayer → 秒切。
解析源仍走原路径，功能不受影响。幂等。
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

MARKER = "MOD_FAST_KERNEL_SWITCH_V1"
SNIPPET = """        // MOD_FAST_KERNEL_SWITCH_V1: non-parse + valid URL → local switch (秒切)
        if (!isUseParse()) {
            String curUrl = player() != null ? player().getUrl() : null;
            if (curUrl != null && !curUrl.isEmpty()) {
                return false;
            }
        }
"""

TARGETS = [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "mod/app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "mod/app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]

def patch_one(path: Path) -> bool:
    if not path.is_file():
        return False
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print(f"[mod] {MARKER} already present: {path}")
        return False

    # Match the method start, tolerate slight formatting differences
    pat = re.compile(
        r"(private boolean refreshAndSwitchPlayerKernel\(int type\)\s*\{\s*\n"
        r"[ \t]*if \(playerKernelSwitchRefreshing\) return true;\s*\n)",
        re.M,
    )
    m = pat.search(text)
    if not m:
        print(f"[mod] WARN: method not found, skip: {path}")
        return False

    new_text = text[: m.end()] + SNIPPET + text[m.end() :]
    # also mark the method signature line for easy grep
    new_text = new_text.replace(
        "private boolean refreshAndSwitchPlayerKernel(int type) {",
        f"private boolean refreshAndSwitchPlayerKernel(int type) {{ /* {MARKER} */",
        1,
    )
    path.write_text(new_text, encoding="utf-8")
    print(f"[mod] {MARKER} applied: {path}")
    return True

def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    changed = 0
    for rel in TARGETS:
        if patch_one(root / rel):
            changed += 1
    if changed == 0:
        # also try walking for any VideoActivity that has the method
        for p in root.rglob("VideoActivity.java"):
            if patch_one(p):
                changed += 1
    print(f"[mod] inject_fast_kernel_switch done, changed={changed}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
