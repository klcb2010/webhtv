#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Wire SubtitleRestoreCoordinator into Assrt + VideoActivity + TrackDialog."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
IMPORT = "import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;"


def ensure_import(text: str) -> str:
    if IMPORT in text:
        return text
    m = re.search(r"(package [^\n]+;\n)", text)
    if not m:
        return text
    return text[: m.end()] + "\n" + IMPORT + "\n" + text[m.end() :]


def patch_assrt(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)

    if "static void rememberDownloadedSub" not in text:
        helper = """
    /** 外挂字幕落盘后调用：记住路径，供历史重进恢复 */
    public static void rememberDownloadedSub(String historyKey, String episodeUrl, java.io.File file, String displayName) {
        try {
            if (file == null || !file.isFile()) return;
            String name = displayName != null && !displayName.isEmpty() ? displayName : file.getName();
            com.fongmi.android.tv.bean.Sub sub = com.fongmi.android.tv.bean.Sub.create(name, file.getAbsolutePath(), "", "");
            SubtitleRestoreCoordinator.remember(historyKey, episodeUrl, sub);
        } catch (Throwable ignored) {}
    }

    public static void rememberSub(String historyKey, String episodeUrl, com.fongmi.android.tv.bean.Sub sub) {
        try { SubtitleRestoreCoordinator.remember(historyKey, episodeUrl, sub); } catch (Throwable ignored) {}
    }
"""
        idx = text.rfind("\n}")
        if idx > 0:
            text = text[:idx] + helper + text[idx:]

    if "SubtitleRestoreCoordinator.remember" not in text or text.count("SubtitleRestoreCoordinator.remember") < 2:
        text2, n = re.subn(
            r"(Sub\s+(\w+)\s*=\s*Sub\.(?:create|from)\([^;]+;)",
            r"\1\n                try { SubtitleRestoreCoordinator.remember((String) null, (String) null, \2); } catch (Throwable ignored) {}",
            text,
            count=4,
        )
        if n:
            text = text2

    path.write_text(text, encoding="utf-8")
    print("[mod] AssrtSubtitleMatch patched:", path)
    return True


def patch_video(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)

    if "SubtitleRestoreCoordinator.restore" not in text:
        inject = (
            "        try {\n"
            "            if (mHistory != null) {\n"
            "                Object _pl = null;\n"
            "                try { _pl = mPlayers; } catch (Throwable ignored) {}\n"
            "                if (_pl == null) try { _pl = getPlayer(); } catch (Throwable ignored) {}\n"
            "                com.fongmi.android.tv.bean.Result _rs = null;\n"
            "                try { _rs = mResult; } catch (Throwable ignored) {}\n"
            "                SubtitleRestoreCoordinator.restore(mHistory, _pl, _rs);\n"
            "            }\n"
            "        } catch (Throwable ignored) {}\n"
        )
        placed = False
        for pat in (
            r"(private\s+void\s+setPlayer\s*\([^)]*\)\s*\{)",
            r"(void\s+setPlayer\s*\([^)]*\)\s*\{)",
            r"(private\s+void\s+startPlayer\s*\([^)]*\)\s*\{)",
            r"(void\s+startPlayer\s*\([^)]*\)\s*\{)",
        ):
            m = re.search(pat, text)
            if m:
                text = text[: m.end()] + "\n" + inject + text[m.end() :]
                placed = True
                break
        if not placed:
            m = re.search(r"(\n\s*)(mPlayers\.setMediaItem\s*\()", text)
            if m:
                text = text[: m.start()] + m.group(1) + inject + m.group(1) + m.group(2) + text[m.end() :]
                placed = True
        print("[mod] restore inject", "OK" if placed else "WARN", path)

    if "SubtitleRestoreCoordinator.remember(mHistory" not in text:
        text, n = re.subn(
            r"((?:mPlayers|getPlayer\(\))\.setSub\((\w+)\);)",
            r"\1 try { SubtitleRestoreCoordinator.remember(mHistory, \2); } catch (Throwable ignored) {}",
            text,
            count=8,
        )
        if n:
            print("[mod] remember on setSub x%d in %s" % (n, path.name))

    path.write_text(text, encoding="utf-8")
    return True


def patch_track_dialog(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)
    if "SubtitleRestoreCoordinator.remember" in text:
        path.write_text(text, encoding="utf-8")
        return True
    text2, n = re.subn(
        r"((?:players?|mPlayers|getPlayer\(\)|player)\.setSub\((\w+)\);)",
        r"\1 try { SubtitleRestoreCoordinator.remember((com.fongmi.android.tv.bean.History) null, \2); } catch (Throwable ignored) {}",
        text,
        count=10,
    )
    if n == 0:
        return False
    path.write_text(text2, encoding="utf-8")
    print("[mod] TrackDialog setSub remember x%d: %s" % (n, path))
    return True


def main() -> int:
    for p in ROOT.rglob("AssrtSubtitleMatch.java"):
        patch_assrt(p)
    for p in ROOT.rglob("VideoActivity.java"):
        if "ui/activity" in str(p).replace("\\", "/"):
            patch_video(p)
    for p in ROOT.rglob("TrackDialog.java"):
        patch_track_dialog(p)
    for name in ("SubtitleSource.java", "SubtitleRestorePolicy.java", "SubtitleRestoreCoordinator.java"):
        hits = list(ROOT.rglob(name))
        print("[mod] %s: %d file(s)" % (name, len(hits)))
    print("[mod] inject_subtitle_restore done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
