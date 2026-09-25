#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Silent SUB-EXT-HISTORY hooks on PlayerManager (VideoActivity 已在 mod 源内烘焙).
1) setSub → onUserSetSub
2) prepareMpvOutputForNewItem / setMediaItem / setMediaItemNow / this.spec = → injectPending
3) VideoActivity 若未烘焙则补 prepareRestore（幂等）
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


def insert_after_match(text: str, pattern: str, insert: str, label: str, already_token: str) -> str:
    if already_token in text and already_token in insert:
        # check if this specific method already has it nearby
        m = re.search(pattern, text)
        if m:
            window = text[m.end() : m.end() + 500]
            if already_token in window:
                log("[mod] already: %s" % label)
                return text
    m = re.search(pattern, text)
    if not m:
        log("[mod] WARN no match for %s" % label)
        return text
    window = text[m.end() : m.end() + 400]
    key = already_token
    if key and key in window:
        log("[mod] already near: %s" % label)
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

    # setSub
    text = insert_after_match(
        text,
        r"(public\s+void\s+setSub\s*\(\s*Sub\s+sub\s*\)\s*\{)",
        (
            "\n"
            "        try {\n"
            "            if (sub != null) SubtitleRestoreCoordinator.onUserSetSub(sub);\n"
            "        } catch (Throwable ignored) {}\n"
        ),
        "PlayerManager.setSub → onUserSetSub",
        "SubtitleRestoreCoordinator.onUserSetSub",
    )

    inject_block = (
        "\n"
        "        // Silent SUB-EXT: inject pending external sub before media start\n"
        "        try { SubtitleRestoreCoordinator.injectPendingIntoPlayerManager(this); } catch (Throwable ignored) {}\n"
    )

    for pattern, label in (
        (r"(private\s+void\s+prepareMpvOutputForNewItem\s*\(\s*\)\s*\{)", "prepareMpvOutputForNewItem → inject"),
        (r"(private\s+void\s+setMediaItemNow\s*\(\s*long\s+\w+\s*,\s*boolean\s+\w+\s*\)\s*\{)", "setMediaItemNow → inject"),
        (r"(private\s+void\s+setMediaItem\s*\(\s*long\s+\w+\s*\)\s*\{)", "setMediaItem → inject"),
    ):
        text = insert_after_match(
            text, pattern, inject_block, label, "SubtitleRestoreCoordinator.injectPendingIntoPlayerManager"
        )

    # after this.spec = spec in start()
    if "this.spec = spec;" in text and "injectPendingIntoPlayerManager" in text:
        # add after first this.spec = spec in start method if not already next line
        def repl_spec(m):
            after = text[m.end() : m.end() + 200]
            if "injectPendingIntoPlayerManager" in after:
                return m.group(0)
            return (
                m.group(0)
                + "\n        try { SubtitleRestoreCoordinator.injectPendingIntoPlayerManager(this); } catch (Throwable ignored) {}"
            )

        new_text, n = re.subn(
            r"(this\.spec\s*=\s*spec;)",
            repl_spec,
            text,
            count=2,
        )
        if n:
            text = new_text
            log("[mod] this.spec= → inject (x%d)" % n)

    path.write_text(text, encoding="utf-8")
    log("[mod] PlayerManager patched")


def patch_video(path: pathlib.Path) -> None:
    if not path.is_file():
        return
    rel = str(path.relative_to(ROOT)) if ROOT in path.parents or path.is_relative_to(ROOT) else path.name
    text = path.read_text(encoding="utf-8")
    text = ensure_import(text)

    # 幂等：若源内已烘焙则跳过
    if "SubtitleRestoreCoordinator.prepareRestore" in text:
        log("[mod] already prepareRestore (baked): %s" % rel)
    else:
        m = re.search(r"(private\s+void\s+setPlayer\s*\(\s*Result\s+result\s*\)\s*\{)", text)
        if m:
            insert = (
                "\n"
                "        try {\n"
                "            SubtitleRestoreCoordinator.bindHistory(mHistory);\n"
                "            SubtitleRestoreCoordinator.prepareRestore(mHistory);\n"
                "        } catch (Throwable ignored) {}\n"
            )
            text = text[: m.end()] + insert + text[m.end() :]
            log("[mod] setPlayer → prepareRestore: %s" % rel)
        else:
            log("[mod] WARN no setPlayer: %s" % rel)

    if "SubtitleRestoreCoordinator.clearBind" not in text:
        for sig in ("void onDestroy() {", "protected void onDestroy() {", "public void onDestroy() {"):
            if sig in text:
                text = text.replace(
                    sig,
                    sig + "\n        try { SubtitleRestoreCoordinator.clearBind(); } catch (Throwable ignored) {}",
                    1,
                )
                log("[mod] onDestroy clearBind: %s" % rel)
                break

    # attach if missing
    if "AssrtSubtitleMatch.attachRememberedSub(result" not in text:
        for pat in (
            "        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata(), mInitialPlaybackPosition);",
            "        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());",
        ):
            if pat in text:
                pre = (
                    "        try { AssrtSubtitleMatch.attachRememberedSub(result, mHistory, getEpisode()); } catch (Throwable ignored) {}\n"
                    "        try { AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player()); } catch (Throwable ignored) {}\n"
                    "        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 800); } catch (Throwable ignored) {}\n"
                    "        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 2000); } catch (Throwable ignored) {}\n"
                    "        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 4500); } catch (Throwable ignored) {}\n"
                )
                text = text.replace(pat, pre + pat, 1)
                log("[mod] attach before startPlayer: %s" % rel)
                break

    path.write_text(text, encoding="utf-8")


def main() -> int:
    log("[mod] subtitle_restore begin")
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
