#!/usr/bin/env python3
"""Live default fullscreen.

Mobile: same as tapping rotate (setRotate + landscape) + hide channel list.
Leanback/TV: hide channel list only (hideUI).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

MOBILE_METHOD = """
    /** mod: live default fullscreen = rotate button */
    private void applyDefaultLiveFullscreen() {
        try {
            if (!com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) return;
            try {
                if (!isRotate()) {
                    setRotate(true);
                    setRequestedOrientation(com.fongmi.android.tv.playback.PlaybackOrientation.getRotateOrientation(this));
                }
            } catch (Throwable ignored) {
            }
            try {
                hideUI();
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
"""

TV_METHOD = """
    /** mod: live default fullscreen = hide channel list */
    private void applyDefaultLiveFullscreen() {
        try {
            if (!com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) return;
            hideUI();
        } catch (Throwable ignored) {
        }
    }
"""

CALL = """
        try {
            App.post(this::applyDefaultLiveFullscreen, 500);
        } catch (Throwable ignored) {
        }
"""

def strip_old(t: str) -> str:
    t = re.sub(
        r"\n\s*try \{\s*if \(com\.fongmi\.android\.tv\.setting\.Setting\.isLiveDefaultFullscreen\(\)\) \{\s*App\.post\(this::hideUI, 300\);\s*\}\s*\} catch \(Throwable ignored\) \{\s*\}\n",
        "\n",
        t,
    )
    t = re.sub(
        r"\n\s*/\*\* mod: live default fullscreen[\s\S]*?private void applyDefaultLiveFullscreen\(\) \{[\s\S]*?\n    \}\n",
        "\n",
        t,
    )
    t = re.sub(
        r"\n\s*try \{\s*App\.post\(this::applyDefaultLiveFullscreen, 500\);\s*\} catch \(Throwable ignored\) \{\s*\}\n",
        "\n",
        t,
    )
    return t

def patch(path: pathlib.Path, mobile: bool) -> bool:
    if not path.exists():
        return False
    t = strip_old(path.read_text(encoding="utf-8"))
    if "applyDefaultLiveFullscreen" in t:
        print("[mod] live fullscreen already", path)
        path.write_text(t, encoding="utf-8")
        return True
    if "checkLive();" not in t:
        print("[mod] WARN no checkLive", path)
        return False
    t = t.replace("checkLive();", "checkLive();" + CALL, 1)
    method = MOBILE_METHOD if mobile else TV_METHOD
    idx = t.rfind("\n}")
    if idx < 0:
        print("[mod] WARN no class end", path)
        return False
    t = t[:idx] + "\n" + method + t[idx:]
    path.write_text(t, encoding="utf-8")
    print("[mod] live fullscreen patched mobile=%s %s" % (mobile, path))
    return True

ok = False
ok |= patch(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", True)
ok |= patch(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", False)
if not ok:
    print("[mod] WARN LiveActivity not in tree (ok at CI apply)")
