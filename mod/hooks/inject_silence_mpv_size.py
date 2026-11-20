#!/usr/bin/env python3
"""Silence SIZE_TAG=\"MPV_SIZE\" layout logs (tag is historical; EXO uses it too)."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
PAT = re.compile(r"Log\.d\(\s*SIZE_TAG\s*,[\s\S]*?\);")

TARGETS = [
    "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java",
]


def patch(path: Path) -> int:
    if not path.is_file():
        return 0
    t = path.read_text(encoding="utf-8")
    if "MPV_SIZE" not in t:
        return 0
    nt, n = PAT.subn("/* MPV_SIZE log silenced */", t)
    if n:
        path.write_text(nt, encoding="utf-8")
        print(f"[mod] silence MPV_SIZE in {path.relative_to(ROOT)} ({n})")
    return n


def main() -> None:
    print("[mod] inject_silence_mpv_size ROOT", ROOT)
    total = 0
    seen = set()
    for rel in TARGETS:
        p = ROOT / rel
        total += patch(p)
        seen.add(p.resolve())
    app = ROOT / "app"
    if app.is_dir():
        for p in app.rglob("*.java"):
            try:
                t = p.read_text(encoding="utf-8")
            except Exception:
                continue
            if 'SIZE_TAG = "MPV_SIZE"' not in t:
                continue
            if p.resolve() in seen:
                continue
            total += patch(p)
    print(f"[mod] inject_silence_mpv_size done total={total}")


if __name__ == "__main__":
    main()
