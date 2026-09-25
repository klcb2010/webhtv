#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外挂字幕自动记忆已关闭：剥离历史注入，删除废弃类。"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def strip_pm(path: pathlib.Path) -> None:
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    t = re.sub(
        r"\n[ \t]*try \{\n[ \t]*if \(sub != null\) SubtitleRestoreCoordinator\.onUserSetSub\(sub\);\n[ \t]*\} catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
    )
    t = re.sub(
        r"\n[ \t]*// Silent SUB-EXT:[^\n]*\n[ \t]*try \{ SubtitleRestoreCoordinator\.injectPendingIntoPlayerManager\(this\); \} catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
    )
    t = re.sub(
        r"\n[ \t]*try \{ SubtitleRestoreCoordinator\.injectPendingIntoPlayerManager\(this\); \} catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
    )
    t = t.replace("import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;\n", "")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] stripped SubtitleRestore from PlayerManager", flush=True)


def strip_video(path: pathlib.Path) -> None:
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    out = []
    for line in t.splitlines(True):
        if "SubtitleRestoreCoordinator" in line:
            continue
        if "AssrtSubtitleMatch.attachRememberedSub" in line:
            continue
        if "AssrtSubtitleMatch.selectPendingIfAny" in line:
            continue
        if "AssrtSubtitleMatch.onPlayerReady(this, mHistory" in line:
            continue
        out.append(line)
    t = "".join(out)
    t = t.replace("import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;\n", "")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] stripped SubtitleRestore from", path.name, flush=True)


def main() -> int:
    print("[mod] subtitle_restore DISABLED — strip residuals only", flush=True)
    strip_pm(ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")
    for rel in (
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ):
        strip_video(ROOT / rel)
    for obsolete in (
        "app/src/main/java/com/fongmi/android/tv/playback/SubtitleRestoreCoordinator.java",
        "app/src/main/java/com/fongmi/android/tv/playback/SubtitleRestorePolicy.java",
        "app/src/main/java/com/fongmi/android/tv/playback/SubtitleSource.java",
        "app/proguard-rules-subtitle.pro",
    ):
        p = ROOT / obsolete
        if p.is_file():
            p.unlink()
            print("[mod] deleted", obsolete, flush=True)
    print("[mod] subtitle_restore done", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
