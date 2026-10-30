#!/usr/bin/env python3
"""
阶段 2：删除 MPV 原生 so（APK 瘦身主收益），并让 CI 校验脚本空通过。

不删除 Java 源码（PlayerManager 等仍大量引用类型，删类会编不过）。
阶段 1 已保证不会 new MpvPlayerEngine，运行时不会加载这些 so。

用法（android-release 中，在 inject_disable_mpv.py 之后）:
  python3 mod/hooks/inject_strip_mpv_assets.py .
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []
OK: list[str] = []


def rm_tree(rel: str) -> None:
    path = ROOT / rel
    if not path.exists():
        OK.append(f"already absent: {rel}")
        return
    size = 0
    if path.is_dir():
        for f in path.rglob("*"):
            if f.is_file():
                size += f.stat().st_size
        shutil.rmtree(path)
    else:
        size = path.stat().st_size
        path.unlink()
    OK.append(f"removed {rel} ({size / (1024 * 1024):.1f} MiB)")


def stub_verify_script(rel: str) -> None:
    path = ROOT / rel
    if not path.parent.is_dir():
        ERRORS.append(f"missing parent for {rel}")
        return
    content = """#!/bin/bash
# MPV_DISABLED phase2: native assets stripped; skip verification.
set -e
echo "verify_mpv_native_assets: skipped (MPV assets removed)"
exit 0
"""
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)
    OK.append(f"stubbed {rel}")


def main() -> int:
    print(f"[mod] inject_strip_mpv_assets ROOT={ROOT}")
    if not (ROOT / "app").is_dir():
        print("ERROR: app/ not found", file=sys.stderr)
        return 2

    # Primary size: committed libmpv/ffmpeg stacks per ABI
    rm_tree("app/src/arm64_v8a/assets/mpv-libs")
    rm_tree("app/src/armeabi_v7a/assets/mpv-libs")

    # Common alternate locations (if fork moves them)
    for rel in (
        "app/src/main/assets/mpv-libs",
        "app/src/mobile/assets/mpv-libs",
        "app/src/leanback/assets/mpv-libs",
    ):
        if (ROOT / rel).exists():
            rm_tree(rel)

    # CI would fail without assets
    stub_verify_script("scripts/verify_mpv_native_assets.sh")

    # Optional: leave third_party for source builds; not packaged into APK
    # Optional Java deletion is out of scope (compile graph too large)

    print("---- results ----")
    for line in OK:
        print("  OK   ", line)
    for line in ERRORS:
        print("  FAIL ", line, file=sys.stderr)

    # Verify so files gone
    left = list((ROOT / "app").rglob("**/mpv-libs/**/*.so")) if (ROOT / "app").exists() else []
    if left:
        ERRORS.append(f"still have so files: {left[:5]}")
        print(f"  FAIL still have: {left[:5]}", file=sys.stderr)
        return 1

    if ERRORS:
        print(f"[mod] FAILED with {len(ERRORS)} error(s)", file=sys.stderr)
        return 1
    print("[mod] inject_strip_mpv_assets SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
