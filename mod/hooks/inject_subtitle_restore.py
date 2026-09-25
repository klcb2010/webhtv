#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Wire SubtitleRestoreCoordinator into VideoActivity.setPlayer only (fast, no rglob)."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
IMPORT = "import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;"

# Explicit paths only — never walk whole tree
TARGETS = [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]

INJECT = """
        try {
            if (mHistory != null) {
                Object _pl = null;
                try { _pl = player(); } catch (Throwable ignored) {}
                SubtitleRestoreCoordinator.restore(mHistory, _pl, result);
            }
        } catch (Throwable ignored) {}
"""

MARKER = "SubtitleRestoreCoordinator.restore(mHistory, _pl, result)"


def log(msg: str) -> None:
    print(msg, flush=True)


def ensure_import(text: str) -> str:
    if IMPORT in text:
        return text
    m = re.search(r"(package [^\n]+;\n)", text)
    if not m:
        return text
    return text[: m.end()] + "\n" + IMPORT + "\n" + text[m.end() :]


def strip_bad_inject(text: str) -> str:
    """Line-scan remove broken blocks; no catastrophic regex on huge files."""
    if "mPlayers" not in text and "mResult" not in text:
        # still remove getPlayer() no-arg if paired with coordinator
        if "SubtitleRestoreCoordinator.restore" not in text:
            return text
    lines = text.splitlines(keepends=True)
    out = []
    i = 0
    removed = 0
    while i < len(lines):
        # detect start of try { near coordinator restore with bad symbols in next ~20 lines
        if "try {" in lines[i] and i + 1 < len(lines):
            window = "".join(lines[i : min(i + 20, len(lines))])
            if "SubtitleRestoreCoordinator.restore" in window and (
                "mPlayers" in window or "mResult" in window or "getPlayer()" in window
            ):
                # skip until matching catch (Throwable ignored) {}
                j = i
                while j < len(lines) and j < i + 25:
                    if "catch (Throwable ignored)" in lines[j]:
                        j += 1
                        removed += 1
                        break
                    j += 1
                else:
                    out.append(lines[i])
                    i += 1
                    continue
                i = j
                continue
        out.append(lines[i])
        i += 1
    if removed:
        log("[mod] stripped %d bad restore inject block(s)" % removed)
    return "".join(out)


def patch_video(path: pathlib.Path) -> None:
    rel = path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path)
    log("[mod] subtitle_restore: start %s" % rel)
    if not path.is_file():
        log("[mod] subtitle_restore: skip missing %s" % rel)
        return
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)
    text = strip_bad_inject(text)

    if MARKER in text:
        path.write_text(text, encoding="utf-8")
        log("[mod] subtitle_restore: already OK %s" % rel)
        return

    m = re.search(r"(private\s+void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)", text)
    if not m:
        m = re.search(r"(void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)", text)
    if not m:
        log("[mod] subtitle_restore: WARN no setPlayer(Result) in %s" % rel)
        path.write_text(text, encoding="utf-8")
        return

    text = text[: m.end()] + "\n" + INJECT + text[m.end() :]
    path.write_text(text, encoding="utf-8")
    log("[mod] subtitle_restore: injected %s" % rel)


def main() -> int:
    log("[mod] subtitle_restore: begin")
    for rel in TARGETS:
        patch_video(ROOT / rel)
    # sources must already be copied by apply.sh
    for name in ("SubtitleSource.java", "SubtitleRestorePolicy.java", "SubtitleRestoreCoordinator.java"):
        hits = list((ROOT / "app").rglob(name)) if (ROOT / "app").is_dir() else []
        log("[mod] subtitle_restore: %s -> %d" % (name, len(hits)))
    log("[mod] subtitle_restore: done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
