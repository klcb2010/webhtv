#!/usr/bin/env python3
"""Movie hide OP/ED; Live honor hide for scale & play_params — TV + mobile."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

VIDEO_PATHS = [
    ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]
LIVE_PATHS = [
    ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
]


def patch_video(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path.relative_to(ROOT))
        return
    t = path.read_text(encoding="utf-8")
    if "OPENING_MOVIE_HIDE" in t:
        print("[mod] OP/ED already", path.relative_to(ROOT))
        return

    for needle, size_expr in [
        (
            "PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.EPISODES, mBinding.control.action.episodes, items.size() >= 2);",
            "items.size()",
        ),
        (
            "PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.EPISODES, mBinding.control.action.episodes, size >= 2);",
            "size",
        ),
    ]:
        if needle in t:
            insert = (
                needle
                + "\n        // OPENING_MOVIE_HIDE: 电影(集数<2)隐藏片头片尾，连续剧保留\n"
                + "        try {\n"
                + f"            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, mBinding.control.action.opening, {size_expr} >= 2);\n"
                + f"            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, mBinding.control.action.ending, {size_expr} >= 2);\n"
                + "        } catch (Throwable ignored) {}\n"
            )
            path.write_text(t.replace(needle, insert, 1), encoding="utf-8")
            print("[mod] OP/ED movie hide", path.relative_to(ROOT))
            return

    m = re.search(
        r"mBinding\.control\.action\.episodes\.setVisibility\((items\.size\(\)|size) < 2 \? View\.GONE : View\.VISIBLE\);",
        t,
    )
    if m:
        size_expr = m.group(1)
        insert = (
            m.group(0)
            + "\n        // OPENING_MOVIE_HIDE\n"
            + "        try {\n"
            + f"            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, mBinding.control.action.opening, {size_expr} >= 2);\n"
            + f"            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, mBinding.control.action.ending, {size_expr} >= 2);\n"
            + "        } catch (Throwable ignored) {}\n"
        )
        path.write_text(t[: m.start()] + insert + t[m.end() :], encoding="utf-8")
        print("[mod] OP/ED movie hide (episodes vis)", path.relative_to(ROOT))
        return

    alt = "private void forceHideConfiguredActionButtons() {"
    if alt in t:
        block = (
            alt
            + "\n        try {\n"
            + "            int ep = 0;\n"
            + "            try {\n"
            + "                if (getFlag() != null && getFlag().getEpisodes() != null) "
            "ep = getFlag().getEpisodes().size();\n"
            + "            } catch (Throwable ignored) {}\n"
            + "            // OPENING_MOVIE_HIDE\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, "
            "mBinding.control.action.opening, ep >= 2);\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, "
            "mBinding.control.action.ending, ep >= 2);\n"
            + "        } catch (Throwable ignored) {}\n"
        )
        path.write_text(t.replace(alt, block, 1), encoding="utf-8")
        print("[mod] OP/ED via forceHide", path.relative_to(ROOT))
        return

    # applyActionButtonVisibility fallback
    alt2 = "private void applyActionButtonVisibility() {"
    if alt2 in t:
        block = (
            alt2
            + "\n        try {\n"
            + "            int ep = 0;\n"
            + "            try {\n"
            + "                if (getFlag() != null && getFlag().getEpisodes() != null) "
            "ep = getFlag().getEpisodes().size();\n"
            + "            } catch (Throwable ignored) {}\n"
            + "            // OPENING_MOVIE_HIDE\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.OPENING, "
            "mBinding.control.action.opening, ep >= 2);\n"
            + "            PlayerButtonSetting.setVisibleIfAllowed(PlayerButtonSetting.ENDING, "
            "mBinding.control.action.ending, ep >= 2);\n"
            + "        } catch (Throwable ignored) {}\n"
        )
        path.write_text(t.replace(alt2, block, 1), encoding="utf-8")
        print("[mod] OP/ED via applyActionButtonVisibility", path.relative_to(ROOT))
        return

    print("[mod] ERROR cannot patch OP/ED", path.relative_to(ROOT))
    sys.exit(1)


def patch_live(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path.relative_to(ROOT))
        return
    t = path.read_text(encoding="utf-8")
    if "applyLiveButtonVisibility" in t:
        print("[mod] live visibility already", path.relative_to(ROOT))
        return

    helper = """
    private void applyLiveButtonVisibility() {
        try {
            PlayerButtonSetting.setVisibleIfAllowed(
                    PlayerButtonSetting.SCALE, mBinding.control.action.scale, true);
            PlayerButtonSetting.setVisibleIfAllowed(
                    PlayerButtonSetting.PLAY_PARAMS, mBinding.control.action.playParams, true);
        } catch (Throwable ignored) {
        }
    }
"""
    if "import com.fongmi.android.tv.setting.PlayerButtonSetting;" not in t:
        if "import com.fongmi.android.tv.setting.PlayerSetting;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.setting.PlayerSetting;",
                "import com.fongmi.android.tv.setting.PlayerButtonSetting;\n"
                "import com.fongmi.android.tv.setting.PlayerSetting;",
                1,
            )
        else:
            t = t.replace(
                "package com.fongmi.android.tv.ui.activity;",
                "package com.fongmi.android.tv.ui.activity;\n\n"
                "import com.fongmi.android.tv.setting.PlayerButtonSetting;",
                1,
            )

    last = t.rfind("\n}")
    t = t[:last] + helper + t[last:]

    t2, n = re.subn(
        r"(private void showControl\([^\)]*\) \{\n)",
        r"\1        applyLiveButtonVisibility();\n",
        t,
        count=1,
    )
    if n:
        t = t2
    else:
        t2, n = re.subn(
            r"(void showControl\([^\)]*\) \{\n)",
            r"\1        applyLiveButtonVisibility();\n",
            t,
            count=1,
        )
        if n:
            t = t2

    if "setScale(LiveSetting.getScale());" in t:
        t = t.replace(
            "setScale(LiveSetting.getScale());",
            "setScale(LiveSetting.getScale());\n        applyLiveButtonVisibility();",
            1,
        )
    elif "super.onResume();" in t and "applyLiveButtonVisibility();" not in t:
        t = t.replace(
            "super.onResume();",
            "super.onResume();\n        applyLiveButtonVisibility();",
            1,
        )

    path.write_text(t, encoding="utf-8")
    print("[mod] live visibility", path.relative_to(ROOT))


def main() -> None:
    print("[mod] inject_player_buttons_fix ROOT", ROOT)
    for p in VIDEO_PATHS:
        patch_video(p)
    for p in LIVE_PATHS:
        patch_live(p)
    print("[mod] inject_player_buttons_fix done")


if __name__ == "__main__":
    main()
