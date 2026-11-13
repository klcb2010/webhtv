#!/usr/bin/env python3
"""Live fullscreen: keep channel list above video via elevation/bringToFront only (no texture force)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

LIVE_JAVA = [
    ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
]

LIVE_XML = [
    ROOT / "app/src/mobile/res/layout/activity_live.xml",
    ROOT / "app/src/mobile/res/layout-land/activity_live.xml",
    ROOT / "app/src/leanback/res/layout/activity_live.xml",
]

HELPER = """
    private void ensureLiveMenuAboveVideo() {
        try {
            if (mBinding == null || mBinding.recycler == null) return;
            android.view.View list = mBinding.recycler;
            list.setElevation(64f);
            list.setTranslationZ(64f);
            list.bringToFront();
            if (list.getParent() instanceof android.view.ViewGroup) {
                list.getParent().requestLayout();
            }
            list.invalidate();
        } catch (Throwable ignored) {
        }
    }
"""


def patch_java(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path.relative_to(ROOT))
        return
    t = path.read_text(encoding="utf-8")
    if "ensureLiveMenuAboveVideo" in t:
        # Strip any previous FORCE_LIVE_TEXTURE blocks if present
        t2 = re.sub(
            r"\n\s*// FORCE_LIVE_TEXTURE[^\n]*\n\s*try \{\n\s*if \(mBinding\.exo != null\) mBinding\.exo\.setRender\(PlayerSetting\.RENDER_TEXTURE\);\n\s*\} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t,
        )
        t2 = re.sub(
            r"\n\s*// FORCE_LIVE_TEXTURE_KERNEL\n\s*try \{\n\s*if \(mBinding\.exo != null\) mBinding\.exo\.setRender\(PlayerSetting\.RENDER_TEXTURE\);\n\s*\} catch \(Throwable ignored\) \{\}\n",
            "\n",
            t2,
        )
        if t2 != t:
            path.write_text(t2, encoding="utf-8")
            print("[mod] stripped texture force", path.relative_to(ROOT))
        else:
            print("[mod] zorder already", path.relative_to(ROOT))
        return

    # moveLiveMenuToVideo: after addView
    if "mBinding.video.addView(mBinding.recycler" in t:
        t2, n = re.subn(
            r"(mBinding\.video\.addView\(mBinding\.recycler,\s*params\);\n)",
            r"\1        ensureLiveMenuAboveVideo();\n",
            t,
            count=1,
        )
        if n:
            t = t2

    # showUI when list becomes visible
    if "mBinding.recycler.setVisibility(View.VISIBLE);" in t:
        t = t.replace(
            "mBinding.recycler.setVisibility(View.VISIBLE);",
            "mBinding.recycler.setVisibility(View.VISIBLE);\n        ensureLiveMenuAboveVideo();",
            1,
        )

    # leanback may only use setVisibility without moveLiveMenu
    if "ensureLiveMenuAboveVideo" not in t and "mBinding.recycler.setVisibility(View.VISIBLE)" in t:
        t = t.replace(
            "mBinding.recycler.setVisibility(View.VISIBLE);",
            "mBinding.recycler.setVisibility(View.VISIBLE);\n        ensureLiveMenuAboveVideo();",
            1,
        )

    last = t.rfind("\n}")
    t = t[:last] + HELPER + t[last:]
    path.write_text(t, encoding="utf-8")
    print("[mod] live list zorder", path.relative_to(ROOT))


def patch_xml(path: Path) -> None:
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    # Do NOT change surface_type. Only elevation on recycler.
    if 'android:id="@+id/recycler"' not in t:
        return
    chunk = t.split('android:id="@+id/recycler"', 1)[1][:200]
    if "android:elevation=" in chunk:
        print("[mod] xml elevation already", path.relative_to(ROOT))
        return
    t = t.replace(
        'android:id="@+id/recycler"',
        'android:id="@+id/recycler"\n        android:elevation="32dp"',
        1,
    )
    path.write_text(t, encoding="utf-8")
    print("[mod] live xml elevation", path.relative_to(ROOT))


def main() -> None:
    print("[mod] inject_live_channel_zorder ROOT", ROOT)
    for p in LIVE_JAVA:
        patch_java(p)
    for p in LIVE_XML:
        patch_xml(p)
    print("[mod] inject_live_channel_zorder done")


if __name__ == "__main__":
    main()
