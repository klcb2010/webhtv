#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate the single pre-start subtitle restore injection.

The actual restore is injected by inject_subtitle.py immediately before startPlayer().
Do not inject another restore here: player() may still refer to the previous item.
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
TARGETS = [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]
MARKER = "AssrtSubtitleMatch.attachRememberedSub(result, mHistory, getEpisode())"

for rel in TARGETS:
    path = ROOT / rel
    if not path.is_file():
        print("[mod] subtitle_restore: skip missing", rel)
        continue
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        print("[mod] subtitle_restore: pre-start restore OK", rel)
    else:
        print("[mod] subtitle_restore: WARN pre-start restore marker missing", rel)
