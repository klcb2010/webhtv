#!/usr/bin/env python3
"""Live default fullscreen.

Mobile: set rotate=true as early as possible (before LiveOrientation launch),
then call onRotate() with retries (same as rotate button).
TV: hideUI.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

CALL = """
        try {
            if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                App.post(this::applyDefaultLiveFullscreen, 200);
                App.post(this::applyDefaultLiveFullscreen, 800);
                App.post(this::applyDefaultLiveFullscreen, 1600);
            }
        } catch (Throwable ignored) {
        }
"""

EARLY = """
        try {
            if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                try { setRotate(true); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
"""

MOBILE_METHOD = """
    /** mod: live default fullscreen = same as rotate button */
    private boolean mModDefaultFsDone;

    private void applyDefaultLiveFullscreen() {
        try {
            if (mModDefaultFsDone) return;
            if (!com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) return;
            if (isFinishing()) return;
            try {
                if (isRotate()) {
                    mModDefaultFsDone = true;
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                onRotate();
                mModDefaultFsDone = true;
                return;
            } catch (Throwable ignored) {
            }
            try {
                if (mBinding != null && mBinding.control != null && mBinding.control.right != null
                        && mBinding.control.right.rotate != null) {
                    mBinding.control.right.rotate.performClick();
                    mModDefaultFsDone = true;
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }
"""

TV_METHOD = """
    /** mod: live default fullscreen = hide channel list */
    private boolean mModDefaultFsDone;

    private void applyDefaultLiveFullscreen() {
        try {
            if (mModDefaultFsDone) return;
            if (!com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) return;
            hideUI();
            mModDefaultFsDone = true;
        } catch (Throwable ignored) {
        }
    }
"""

def strip_old(t: str) -> str:
    for pat in [
        r"\n\s*try \{\s*if \(com\.fongmi\.android\.tv\.setting\.Setting\.isLiveDefaultFullscreen\(\)\) \{\s*App\.post\(this::hideUI, 300\);\s*\}\s*\} catch \(Throwable ignored\) \{\s*\}\n",
        r"\n\s*try \{\s*App\.post\(this::applyDefaultLiveFullscreen, 500\);\s*\} catch \(Throwable ignored\) \{\s*\}\n",
        r"\n\s*try \{\s*if \(com\.fongmi\.android\.tv\.setting\.Setting\.isLiveDefaultFullscreen\(\)\) \{[\s\S]*?App\.post\(this::applyDefaultLiveFullscreen[\s\S]*?\}\s*\} catch \(Throwable ignored\) \{\s*\}\n",
        r"\n\s*/\*\* mod: live default fullscreen[\s\S]*?private void applyDefaultLiveFullscreen\(\) \{[\s\S]*?\n    \}\n",
        r"\n\s*private boolean mModDefaultFsDone;\n",
        r"\n\s*try \{\s*if \(com\.fongmi\.android\.tv\.setting\.Setting\.isLiveDefaultFullscreen\(\)\) \{\s*try \{ setRotate\(true\); \} catch \(Throwable ignored\) \{\}\s*\}\s*\} catch \(Throwable ignored\) \{\s*\}\n",
    ]:
        t = re.sub(pat, "\n", t)
    return t

def patch_live_activity(path: pathlib.Path, mobile: bool) -> bool:
    if not path.exists():
        return False
    t = strip_old(path.read_text(encoding="utf-8"))
    if "mModDefaultFsDone" in t and "onRotate()" in t:
        print("[mod] live fullscreen already", path)
        return True
    if "checkLive();" not in t:
        print("[mod] WARN no checkLive", path)
        return False
    t = t.replace("checkLive();", "checkLive();" + CALL, 1)

    # Early setRotate in onCreate / initView for mobile
    if mobile:
        if "protected void onCreate" in t and "setRotate(true)" not in t.split("protected void onCreate")[1][:500]:
            t = re.sub(
                r"(protected void onCreate\s*\(\s*Bundle\s+\w+\s*\)\s*\{[\s\S]*?super\.onCreate\s*\(\s*\w+\s*\)\s*;)",
                r"\1" + EARLY,
                t,
                count=1,
            )
        if "protected void initView" in t:
            t = re.sub(
                r"(protected void initView\s*\(\s*Bundle\s+\w+\s*\)\s*\{[\s\S]*?super\.initView\s*\(\s*\w+\s*\)\s*;)",
                r"\1" + EARLY,
                t,
                count=1,
            )

    method = MOBILE_METHOD if mobile else TV_METHOD
    if mobile and "void onRotate(" not in t and "void onRotate (" not in t:
        method = TV_METHOD
        print("[mod] mobile has no onRotate, hideUI only")
    idx = t.rfind("\n}")
    if idx < 0:
        return False
    t = t[:idx] + "\n" + method + t[idx:]
    path.write_text(t, encoding="utf-8")
    print("[mod] live fullscreen patched mobile=%s %s" % (mobile, path))
    return True

def patch_live_orientation(root: pathlib.Path) -> None:
    for path in root.rglob("*.java"):
        try:
            t = path.read_text(encoding="utf-8")
        except Exception:
            continue
        if "LiveOrientation" not in t and '"LiveOrientation"' not in t:
            continue
        if "isLiveDefaultFullscreen" in t:
            print("[mod] orientation helper already", path)
            continue
        # If method logs reason=launch and uses rotate flag, force rotate when setting on
        changed = False
        # Pattern: rotate = false near launch
        t2, n = re.subn(
            r'(reason\s*[=.].{0,40}launch[\s\S]{0,200}?)(rotate\s*=\s*false)',
            r"\1rotate = com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen() ? true : false",
            t,
            count=1,
            flags=re.IGNORECASE,
        )
        if n:
            t = t2
            changed = True
        # Pattern: , false) when calling with rotate for launch
        # Soft: at start of request methods, if setting enable force
        def inject(mm):
            return mm.group(0) + """
        try {
            if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                // keep; callers may read isRotate()
            }
        } catch (Throwable ignored) {
        }
"""
        t3 = re.sub(r"(void\s+request\s*\([^)]*\)\s*\{)", inject, t, count=1)
        if t3 != t:
            t = t3
            changed = True
        if changed:
            path.write_text(t, encoding="utf-8")
            print("[mod] patched orientation helper", path)
        else:
            print("[mod] found orientation helper, no safe patch site", path)

ok = False
ok |= patch_live_activity(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", True)
ok |= patch_live_activity(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java", False)
patch_live_orientation(ROOT)
if not ok:
    print("[mod] WARN LiveActivity not in tree (ok at CI apply)")
