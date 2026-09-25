#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Wire SubtitleRestoreCoordinator into VideoActivity.setPlayer (fish2018: player() + result)."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
IMPORT = "import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;"

INJECT = """
        try {
            if (mHistory != null) {
                Object _pl = null;
                try { _pl = player(); } catch (Throwable ignored) {}
                SubtitleRestoreCoordinator.restore(mHistory, _pl, result);
            }
        } catch (Throwable ignored) {}
"""


def ensure_import(text: str) -> str:
    if IMPORT in text:
        return text
    m = re.search(r"(package [^\n]+;\n)", text)
    if not m:
        return text
    return text[: m.end()] + "\n" + IMPORT + "\n" + text[m.end() :]


def strip_bad_inject(text: str) -> str:
    """Remove previous broken inject that referenced mPlayers / getPlayer() / mResult."""
    # Broad: any try block containing mPlayers or getPlayer() near SubtitleRestoreCoordinator
    pat = re.compile(
        r"\n[ \t]*try \{[ \t]*\n"
        r"(?:[ \t]*.*\n){0,15}?"
        r"[ \t]*SubtitleRestoreCoordinator\.restore\([^;]+;\n"
        r"[ \t]*\}[ \t]*\n?"
        r"[ \t]*\} catch \(Throwable ignored\) \{\}[ \t]*\n?",
        re.MULTILINE,
    )
    def repl(m: re.Match) -> str:
        block = m.group(0)
        if "mPlayers" in block or "getPlayer()" in block or "mResult" in block:
            print("[mod] stripped bad restore inject")
            return "\n"
        return block
    return pat.sub(repl, text)


def patch_video(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)
    text = strip_bad_inject(text)

    if "SubtitleRestoreCoordinator.restore(mHistory, _pl, result)" in text:
        path.write_text(text, encoding="utf-8")
        print("[mod] restore already OK:", path)
        return True

    placed = False
    for pat in (
        r"(private\s+void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)",
        r"(void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)",
        r"(private\s+void\s+setPlayer\s*\([^)]*\)\s*\{)",
        r"(void\s+setPlayer\s*\([^)]*\)\s*\{)",
    ):
        m = re.search(pat, text)
        if m:
            text = text[: m.end()] + "\n" + INJECT + text[m.end() :]
            placed = True
            break
    if not placed:
        print("[mod] WARN no setPlayer in", path)
        path.write_text(text, encoding="utf-8")
        return False

    if "SubtitleRestoreCoordinator.remember(mHistory" not in text:
        text, n = re.subn(
            r"((?:player\(\)|getControlPlayer\(\))\.setSub\((\w+)\);)",
            r"\1 try { SubtitleRestoreCoordinator.remember(mHistory, \2); } catch (Throwable ignored) {}",
            text,
            count=8,
        )
        if n:
            print("[mod] remember on setSub x%d in %s" % (n, path.name))

    path.write_text(text, encoding="utf-8")
    print("[mod] restore inject OK:", path)
    return True


def patch_assrt(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "SubtitleRestoreCoordinator" in text:
        text = ensure_import(text)
        path.write_text(text, encoding="utf-8")
        print("[mod] Assrt has coordinator:", path.name)
        return True
    return False


def main() -> int:
    for p in ROOT.rglob("VideoActivity.java"):
        if "ui/activity" in str(p).replace("\\", "/"):
            patch_video(p)
    for p in ROOT.rglob("AssrtSubtitleMatch.java"):
        patch_assrt(p)
    for name in ("SubtitleSource.java", "SubtitleRestorePolicy.java", "SubtitleRestoreCoordinator.java"):
        print("[mod] %s: %d" % (name, len(list(ROOT.rglob(name)))))
    print("[mod] inject_subtitle_restore done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
