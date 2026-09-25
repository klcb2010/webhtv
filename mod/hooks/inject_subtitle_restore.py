#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外挂字幕自动记忆已关闭：不再注入 PlayerManager / VideoActivity。"""
from __future__ import annotations
import pathlib
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def main() -> int:
    print("[mod] subtitle_restore DISABLED (manual select only)", flush=True)
    # 若历史注入残留，尽量剥掉（幂等、失败忽略）
    pm = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    if pm.is_file():
        t = pm.read_text(encoding="utf-8")
        import re
        t2 = re.sub(
            r"\n[ \t]*try \{\n[ \t]*if \(sub != null\) SubtitleRestoreCoordinator\.onUserSetSub\(sub\);\n[ \t]*\} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t,
        )
        t2 = re.sub(
            r"\n[ \t]*// Silent SUB-EXT:.*\n[ \t]*try \{ SubtitleRestoreCoordinator\.injectPendingIntoPlayerManager\(this\); \} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t2,
        )
        t2 = re.sub(
            r"\n[ \t]*try \{ SubtitleRestoreCoordinator\.injectPendingIntoPlayerManager\(this\); \} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t2,
        )
        if t2 != t:
            pm.write_text(t2, encoding="utf-8")
            print("[mod] stripped residual SubtitleRestore hooks from PlayerManager", flush=True)
        # drop unused import
        if "SubtitleRestoreCoordinator" not in t2:
            t3 = t2.replace("import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;\n", "")
            if t3 != t2:
                pm.write_text(t3, encoding="utf-8")
    for rel in (
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ):
        p = ROOT / rel
        if not p.is_file():
            continue
        t = p.read_text(encoding="utf-8")
        if "SubtitleRestoreCoordinator" not in t and "attachRememberedSub" not in t:
            continue
        import re
        t2 = re.sub(
            r"\n[ \t]*try \{\n[ \t]*SubtitleRestoreCoordinator\.[^;]+;\n[ \t]*SubtitleRestoreCoordinator\.[^;]+;\n[ \t]*\} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t,
        )
        t2 = re.sub(r"\n[ \t]*try \{ AssrtSubtitleMatch\.attachRememberedSub\([^;]+;\n", "\n", t2)
        t2 = re.sub(r"\n[ \t]*try \{ AssrtSubtitleMatch\.onPlayerReady\([^;]+;\n", "\n", t2)
        t2 = re.sub(
            r"\n[ \t]*try \{ com\.fongmi\.android\.tv\.App\.post\(\(\) -> \{ try \{ AssrtSubtitleMatch\.selectPendingIfAny\(player\(\)\); \} catch \(Throwable ignored\) \{\} \}, \d+\); \} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t2,
        )
        t2 = t2.replace("import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;\n", "")
        if t2 != t:
            p.write_text(t2, encoding="utf-8")
            print("[mod] stripped residual restore from", rel, flush=True)
    print("[mod] subtitle_restore done (disabled)", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
