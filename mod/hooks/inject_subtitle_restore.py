#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对齐 Silent SUB-EXT-HISTORY：
1) PlayerManager.setSub → onUserSetSub（唯一写入收口）
2) prepareMpvOutputForNewItem / setMediaItem / start 开头 → injectPendingIntoPlayerManager
3) VideoActivity.setPlayer 开头 → bindHistory + prepareRestore
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
IMPORT = "import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;"


def log(msg: str) -> None:
    print(msg, flush=True)


def ensure_import(text: str) -> str:
    if IMPORT in text:
        return text
    m = re.search(r"(package [^\n]+;\n)", text)
    if not m:
        return text
    return text[: m.end()] + "\n" + IMPORT + "\n" + text[m.end() :]


def insert_after_method_open(text: str, pattern: str, insert: str, label: str) -> str:
    if "SubtitleRestoreCoordinator." in insert and insert.strip().split("(")[0].split(".")[-1] in text and label in text:
        # already has this specific inject nearby
        pass
    m = re.search(pattern, text)
    if not m:
        log("[mod] WARN no match for %s" % label)
        return text
    # avoid double insert at same method
    window = text[m.end() : m.end() + 400]
    key = insert.strip().split("\n")[0].strip() if insert.strip() else ""
    if key and key in window:
        log("[mod] already: %s" % label)
        return text
    text = text[: m.end()] + insert + text[m.end() :]
    log("[mod] %s" % label)
    return text


def patch_player_manager(path: pathlib.Path) -> None:
    if not path.is_file():
        log("[mod] skip missing PlayerManager")
        return
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)

    # setSub → remember
    if "SubtitleRestoreCoordinator.onUserSetSub" not in text:
        text = insert_after_method_open(
            text,
            r"(public\s+void\s+setSub\s*\(\s*Sub\s+sub\s*\)\s*\{)",
            (
                "\n"
                "        try {\n"
                "            if (sub != null) SubtitleRestoreCoordinator.onUserSetSub(sub);\n"
                "        } catch (Throwable ignored) {}\n"
            ),
            "PlayerManager.setSub → onUserSetSub",
        )
    else:
        log("[mod] already setSub hook")

    inject_line = (
        "\n"
        "        // Silent SUB-EXT: inject pending external sub before setMediaItem\n"
        "        try { SubtitleRestoreCoordinator.injectPendingIntoPlayerManager(this); } catch (Throwable ignored) {}\n"
    )

    # prepareMpvOutputForNewItem
    if "prepareMpvOutputForNewItem" in text:
        text = insert_after_method_open(
            text,
            r"(private\s+void\s+prepareMpvOutputForNewItem\s*\(\s*\)\s*\{)",
            inject_line,
            "prepareMpvOutputForNewItem → injectPending",
        )

    # setMediaItemNow — covers Exo + all engines (prepareMpv early-returns for non-MPV)
    if "setMediaItemNow" in text:
        text = insert_after_method_open(
            text,
            r"(private\s+void\s+setMediaItemNow\s*\(\s*long\s+timeout\s*,\s*boolean\s+notifyPrepare\s*\)\s*\{)",
            inject_line,
            "setMediaItemNow → injectPending",
        )

    # start(PlaySpec,...) after this.spec = spec
    if re.search(r"this\.spec\s*=\s*spec\s*;", text) and "injectPendingIntoPlayerManager" in text:
        # ensure one inject right after this.spec = spec in start methods
        def add_after_spec_assign(t: str) -> str:
            # only in start methods region — replace first few this.spec = spec; that lack inject after
            pattern = re.compile(
                r"(this\.spec\s*=\s*spec\s*;\n)(?![ \t]*try \{ SubtitleRestoreCoordinator\.injectPending)"
            )
            count = 0

            def repl(m):
                nonlocal count
                count += 1
                if count > 4:
                    return m.group(0)
                return (
                    m.group(1)
                    + "        try { SubtitleRestoreCoordinator.injectPendingIntoPlayerManager(this); } catch (Throwable ignored) {}\n"
                )

            out = pattern.sub(repl, t)
            if count:
                log("[mod] this.spec=spec → injectPending x%d" % min(count, 4))
            return out

        text = add_after_spec_assign(text)

    path.write_text(text, encoding="utf-8")


def patch_video(path: pathlib.Path) -> None:
    if not path.is_file():
        log("[mod] skip missing %s" % path)
        return
    rel = str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path)
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)

    # strip old broken injects
    text = re.sub(
        r"\n[ \t]*try \{\n[ \t]*SubtitleRestoreCoordinator\.(?:restore|prepareRestore|bindHistory)\([^;]+;\n[ \t]*\} catch \(Throwable ignored\) \{\}\n?",
        "\n",
        text,
        count=8,
    )
    text = re.sub(
        r"\n[ \t]*try \{\n[ \t]*if \(mHistory != null\) \{\n[ \t]*Object _pl = null;\n(?:[ \t]*.*\n){0,12}?SubtitleRestoreCoordinator\.[^;]+;\n[ \t]*\}\n[ \t]*\} catch \(Throwable ignored\) \{\}\n?",
        "\n",
        text,
        count=3,
    )

    if "SubtitleRestoreCoordinator.prepareRestore" not in text:
        m = re.search(
            r"(private\s+void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)",
            text,
        )
        if not m:
            m = re.search(r"(void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)", text)
        if m:
            insert = (
                "\n"
                "        // Silent SUB-EXT: 绑定历史 + 登记 pending（真正注入在 setMediaItem 前）\n"
                "        try {\n"
                "            SubtitleRestoreCoordinator.bindHistory(mHistory);\n"
                "            SubtitleRestoreCoordinator.prepareRestore(mHistory);\n"
                "        } catch (Throwable ignored) {}\n"
            )
            text = text[: m.end()] + insert + text[m.end() :]
            log("[mod] setPlayer → prepareRestore: %s" % rel)
        else:
            log("[mod] WARN no setPlayer in %s" % rel)
    else:
        log("[mod] already prepareRestore: %s" % rel)

    if "SubtitleRestoreCoordinator.clearBind" not in text and "void onDestroy()" in text:
        text = text.replace(
            "void onDestroy() {",
            "void onDestroy() {\n        try { SubtitleRestoreCoordinator.clearBind(); } catch (Throwable ignored) {}",
            1,
        )
        log("[mod] onDestroy clearBind: %s" % rel)

    path.write_text(text, encoding="utf-8")


def main() -> int:
    log("[mod] subtitle_restore Silent-aligned begin")
    pm = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    patch_player_manager(pm)
    for rel in (
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ):
        patch_video(ROOT / rel)
    for name in ("SubtitleSource.java", "SubtitleRestorePolicy.java", "SubtitleRestoreCoordinator.java"):
        hits = list((ROOT / "app").rglob(name)) if (ROOT / "app").is_dir() else []
        log("[mod] %s -> %d" % (name, len(hits)))
    log("[mod] subtitle_restore done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
