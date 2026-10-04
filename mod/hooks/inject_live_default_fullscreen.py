#!/usr/bin/env python3
"""Live default fullscreen.

Mobile: mirror the existing onRotate() body (whatever upstream API is).
Leanback/TV: hide channel list (hideUI).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

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

def extract_on_rotate_body(t: str) -> str | None:
    """Copy setRotate + setRequestedOrientation lines from onRotate() as-is."""
    m = re.search(
        r"private void onRotate\(\)\s*\{([\s\S]*?)\n    \}",
        t,
    )
    if not m:
        return None
    body = m.group(1)
    lines = []
    for line in body.splitlines():
        s = line.strip()
        if not s or s.startswith("setR1Callback"):
            continue
        # keep setRotate / setRequestedOrientation / isRotate related
        if "setRotate" in s or "setRequestedOrientation" in s or "getRotateOrientation" in s:
            lines.append("            " + s)
    if not lines:
        return None
    # onRotate toggles with !isRotate(); for default fullscreen we force ON once
    fixed = []
    for line in lines:
        # setRotate(!isRotate()) -> setRotate(true)
        line2 = re.sub(r"setRotate\s*\(\s*!?\s*isRotate\s*\(\s*\)\s*\)", "setRotate(true)", line)
        fixed.append(line2)
    return "\n".join(fixed)

def mobile_method(t: str) -> str:
    rotate_body = extract_on_rotate_body(t)
    if rotate_body is None:
        # ultra-safe fallback: only hideUI
        rotate_body = "            /* onRotate not found; list-only */"
    return f"""
    /** mod: live default fullscreen = same as rotate button */
    private void applyDefaultLiveFullscreen() {{
        try {{
            if (!com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) return;
            try {{
                if (!isRotate()) {{
{rotate_body}
                }}
            }} catch (Throwable ignored) {{
            }}
            try {{
                hideUI();
            }} catch (Throwable ignored) {{
            }}
        }} catch (Throwable ignored) {{
        }}
    }}
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
    method = mobile_method(t) if mobile else TV_METHOD
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
