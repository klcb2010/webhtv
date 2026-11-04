#!/usr/bin/env python3
"""MOD_FAST_KERNEL_SWITCH_V1 — 非解析源秒切，幂等"""
from __future__ import annotations
import sys
from pathlib import Path

MARKER = "MOD_FAST_KERNEL_SWITCH_V1"
SNIPPET = """        // MOD_FAST_KERNEL_SWITCH_V1: 非解析源 + 已有 URL → 本地秒切，跳过 playerContent 重取
        if (!isUseParse()) {
            try {
                String curUrl = player() != null ? player().getUrl() : null;
                if (curUrl != null && !curUrl.isEmpty()) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
"""
TARGETS = [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]

def patch_one(path: Path) -> bool:
    if not path.is_file():
        return False
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print(f"[mod] {MARKER} already present: {path}")
        return False
    old = (
        "    private boolean refreshAndSwitchPlayerKernel(int type) {\n"
        "        if (playerKernelSwitchRefreshing) return true;\n"
    )
    if old not in text:
        print(f"[mod] WARN: pattern not found: {path}")
        return False
    new = (
        f"    private boolean refreshAndSwitchPlayerKernel(int type) {{ /* {MARKER} */\n"
        "        if (playerKernelSwitchRefreshing) return true;\n"
        + SNIPPET
    )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"[mod] {MARKER} applied: {path}")
    return True

def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    n = sum(1 for rel in TARGETS if patch_one(root / rel))
    print(f"[mod] inject_fast_kernel_switch done, changed={n}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
