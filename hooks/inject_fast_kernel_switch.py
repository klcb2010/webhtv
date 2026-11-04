#!/usr/bin/env python3
"""Minimal fast kernel switch V1 for MPV branch.
Skip SiteApi.playerContent re-fetch when non-parse source + valid URL present.
Falls through to local player().switchPlayer(type) → 秒切 (默壳 style).
Idempotent via marker MOD_FAST_KERNEL_SWITCH_V1.
"""
from __future__ import annotations
import sys
from pathlib import Path

MARKER = "/* MOD_FAST_KERNEL_SWITCH_V1 */"
INSERT_LINES = [
    "        // MOD_FAST_KERNEL_SWITCH_V1: non-parse + valid URL → local switch (秒切)",
    "        if (!isUseParse()) {",
    "            String curUrl = player() != null ? player().getUrl() : null;",
    "            if (curUrl != null && !curUrl.isEmpty()) {",
    "                return false;",
    "            }",
    "        }",
]

def patch_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print(f"[skip] already: {path}")
        return False
    lines = text.splitlines(keepends=True)
    out = []
    i = 0
    patched = False
    while i < len(lines):
        line = lines[i]
        out.append(line)
        if (not patched
            and "private boolean refreshAndSwitchPlayerKernel(int type)" in line
            and MARKER not in line):
            j = i + 1
            while j < len(lines) and lines[j].strip() == "":
                out.append(lines[j])
                j += 1
            if j < len(lines) and "playerKernelSwitchRefreshing" in lines[j]:
                out.append(lines[j])
                for ins in INSERT_LINES:
                    out.append(ins + "\n")
                patched = True
                i = j
        i += 1
    if not patched:
        print(f"[FAIL] pattern not found: {path}")
        return False
    new_text = "".join(out)
    new_text = new_text.replace(
        "private boolean refreshAndSwitchPlayerKernel(int type) {",
        "private boolean refreshAndSwitchPlayerKernel(int type) { " + MARKER,
        1,
    )
    path.write_text(new_text, encoding="utf-8")
    print(f"[OK] {path}")
    return True

def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    targets = [
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "mod/app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "mod/app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ]
    changed = 0
    for rel in targets:
        t = root / rel
        if t.is_file() and patch_file(t):
            changed += 1
        elif not t.is_file():
            print(f"[miss] {t}")
    print(f"changed={changed}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
