#!/usr/bin/env python3
"""
MPV 阶段 C（编译安全版）：
1) 字幕：只用 setOption（与阶段 A 相同注入点）
2) 暂停旋转撕裂：helper 内全部反射调用，不直接引用不存在的 API
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_OPT = "MPV_PHASE_C_SUB_OPTIONS"
MARKER_SURF = "MPV_PHASE_C_SURFACE_REBIND"
OK: list[str] = []
ERR: list[str] = []


def rel(p: Path) -> str:
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def find_mpv_player() -> Path | None:
    for c in [
        ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java",
        ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayer.java",
    ]:
        if c.is_file():
            return c
    for p in ROOT.rglob("MpvPlayer.java"):
        if "mpv" in str(p).lower():
            return p
    return None


def detect_callee(text: str) -> str:
    m = re.search(r'((?:this\.)?[A-Za-z_][\w\.]*)\s*\(\s*"hwdec"\s*,', text)
    if m:
        return m.group(1)
    if "setOption(" in text:
        return "setOption"
    return "setOption"


def inject_sub_options(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_OPT in text:
        OK.append(f"sub options already: {rel(path)}")
        return
    lines = text.splitlines(keepends=True)
    idx = -1
    callee = detect_callee(text)
    for i, ln in enumerate(lines):
        if "MPV_PHASE_A_OPTIONS" in ln:
            idx = i
        if idx < 0 and re.search(r'\(\s*"hwdec"\s*,', ln):
            idx = i
    if idx < 0:
        ERR.append(f"no inject site for sub options in {rel(path)}")
        return
    j = idx + 1
    while j < len(lines) and (
        "setOption" in lines[j]
        or "setProperty" in lines[j]
        or lines[j].strip().startswith("//")
        or lines[j].strip() == ""
        or "MPV_PHASE_A" in lines[j]
        or "catch (Throwable" in lines[j]
        or 'try {' in lines[j]
    ):
        j += 1
    ind_m = re.match(r"^(\s*)", lines[idx])
    ind = ind_m.group(1) if ind_m else "        "
    # fonts dir via App.get()，避免 getContext()
    block = f"""{ind}// {MARKER_OPT}: subtitle fonts + colors (phase C)
{ind}try {{ {callee}("sub-ass-override", "force"); }} catch (Throwable ignored) {{}}
{ind}try {{ {callee}("embeddedfonts", "yes"); }} catch (Throwable ignored) {{}}
{ind}try {{ {callee}("sub-font", "yiqicukai"); }} catch (Throwable ignored) {{}}
{ind}try {{
{ind}    android.content.Context _modCtx = null;
{ind}    try {{
{ind}        Class<?> appCl = Class.forName("com.fongmi.android.tv.App");
{ind}        Object app = appCl.getMethod("get").invoke(null);
{ind}        if (app instanceof android.content.Context) _modCtx = (android.content.Context) app;
{ind}    }} catch (Throwable ignored) {{}}
{ind}    if (_modCtx != null) {{
{ind}        java.io.File _modFonts = new java.io.File(_modCtx.getFilesDir(), "subtitle_fonts");
{ind}        if (!_modFonts.isDirectory()) _modFonts = new java.io.File(_modCtx.getFilesDir(), "mpv/fonts");
{ind}        if (_modFonts.isDirectory()) {callee}("sub-fonts-dir", _modFonts.getAbsolutePath());
{ind}    }}
{ind}}} catch (Throwable ignored) {{}}
{ind}try {{
{ind}    Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
{ind}    Object font = pol.getMethod("getSubFontProperty").invoke(null);
{ind}    Object dir = pol.getMethod("getSubFontsDirProperty").invoke(null);
{ind}    Object style = pol.getMethod("getAssForceStyle").invoke(null);
{ind}    if (dir instanceof String && !((String) dir).isEmpty()) {callee}("sub-fonts-dir", (String) dir);
{ind}    if (font instanceof String && !((String) font).isEmpty()) {callee}("sub-font", (String) font);
{ind}    if (style instanceof String && !((String) style).isEmpty()) {callee}("sub-ass-force-style", (String) style);
{ind}}} catch (Throwable ignored) {{}}
{ind}try {{ {callee}("gpu-context", "android"); }} catch (Throwable ignored) {{}}
"""
    lines.insert(j, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append(f"sub options at line {j} via {callee} in {rel(path)}")


def inject_surface_rebind(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_SURF in text:
        OK.append(f"surface rebind already: {rel(path)}")
        return

    # 全部反射，兼容只有 setOption / 私有 native 的封装
    helper = f'''
    // {MARKER_SURF}
    private void modPhaseCOnSurfaceSize(int width, int height) {{
        if (width <= 0 || height <= 0) return;
        final String size = width + "x" + height;
        try {{
            // setOption / setProperty / command 均反射，避免硬编码不存在的方法
            for (String name : new String[] {{ "setOption", "setProperty" }}) {{
                try {{
                    java.lang.reflect.Method m = getClass().getMethod(name, String.class, String.class);
                    try {{ m.invoke(this, "android-surface-size", size); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "force-render", "yes"); }} catch (Throwable ignored) {{}}
                }} catch (Throwable ignored) {{}}
            }}
            boolean paused = false;
            try {{
                java.lang.reflect.Method gp = null;
                try {{ gp = getClass().getMethod("getProperty", String.class); }} catch (Throwable ignored) {{}}
                if (gp != null) {{
                    Object p = gp.invoke(this, "pause");
                    if (p instanceof Boolean) paused = (Boolean) p;
                    else if (p != null) {{
                        String s = String.valueOf(p);
                        paused = "yes".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
                    }}
                }}
            }} catch (Throwable ignored) {{}}
            if (paused) {{
                for (String name : new String[] {{ "setOption", "setProperty" }}) {{
                    try {{
                        java.lang.reflect.Method m = getClass().getMethod(name, String.class, String.class);
                        try {{ m.invoke(this, "force-window", "yes"); }} catch (Throwable ignored) {{}}
                        try {{ m.invoke(this, "force-render", "yes"); }} catch (Throwable ignored) {{}}
                    }} catch (Throwable ignored) {{}}
                }}
                try {{
                    java.lang.reflect.Method cmd = getClass().getMethod("command", String[].class);
                    cmd.invoke(this, (Object) new String[] {{ "script-message", "mod-force-redraw" }});
                }} catch (Throwable ignored) {{}}
            }}
        }} catch (Throwable ignored) {{}}
    }}
'''

    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append(f"cannot find class end in {rel(path)}")
        return
    text = text[:idx] + "\n" + helper + text[idx:]

    hooked = 0
    for cre, repl in [
        (
            re.compile(r"(void\s+surfaceChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
            r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
        (
            re.compile(r"(public\s+void\s+onSurfaceTextureSizeChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
            r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
    ]:
        text2, n = cre.subn(repl, text, count=3)
        if n:
            hooked += n
            text = text2

    def repl_size(m):
        nonlocal hooked
        hooked += 1
        return (
            m.group(1)
            + f"\n        try {{ modPhaseCOnSurfaceSize({m.group(2)}, {m.group(3)}); }} catch (Throwable ignored) {{}}"
        )

    text2, n = re.subn(
        r"(void\s+setVideoSurfaceSize\s*\(\s*int\s+(\w+)\s*,\s*int\s+(\w+)\s*\)\s*\{)",
        repl_size,
        text,
        count=3,
    )
    text = text2

    text2, n = re.subn(
        r"(surfaceWidth\s*=\s*(\w+)\s*;\s*\n\s*surfaceHeight\s*=\s*(\w+)\s*;)",
        r"\1\n        try { modPhaseCOnSurfaceSize(\2, \3); } catch (Throwable ignored) {}",
        text,
        count=5,
    )
    if n:
        hooked += n
        text = text2

    path.write_text(text, encoding="utf-8")
    OK.append(f"surface helper + hooks={hooked} in {rel(path)}")


def soften_vulkan_for_android(path: Path) -> None:
    t = path.read_text(encoding="utf-8")
    if "MPV_PHASE_C_GPU_API" in t:
        OK.append("gpu-api already softened")
        return
    nt, n = re.subn(
        r'setOption\("gpu-api",\s*"vulkan"\)',
        'setOption("gpu-api", "opengl") /* MPV_PHASE_C_GPU_API: opengl for rotate stability */',
        t,
        count=2,
    )
    if n:
        path.write_text(nt, encoding="utf-8")
        OK.append(f"gpu-api vulkan->opengl x{n}")
    else:
        OK.append("gpu-api vulkan string not found (ok)")


def patch_video_activity_config() -> None:
    marker = "MPV_PHASE_C_CONFIG_CHANGE"
    targets = [
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java",
    ]
    for path in targets:
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        if marker in t:
            OK.append(f"config already: {rel(path)}")
            continue
        m = re.search(
            r"(void\s+onConfigurationChanged\s*\(\s*(?:@\w+\s+)*Configuration\s+\w+\s*\)\s*\{)",
            t,
        )
        if not m:
            OK.append(f"no onConfigurationChanged: {rel(path)}")
            continue
        insert = (
            m.group(0)
            + f"""
        // {marker}
        try {{
            if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                    == com.fongmi.android.tv.setting.PlayerSetting.MPV) {{
                android.view.View pv = null;
                try {{ pv = getExoView(); }} catch (Throwable ignored) {{}}
                if (pv != null) {{
                    final android.view.View v = pv;
                    v.post(() -> {{
                        try {{ v.requestLayout(); v.invalidate(); }} catch (Throwable ignored) {{}}
                    }});
                }}
            }}
        }} catch (Throwable ignored) {{}}
"""
        )
        t = t[: m.start()] + insert + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        OK.append(f"onConfigurationChanged patched: {rel(path)}")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
    else:
        inject_sub_options(path)
        inject_surface_rebind(path)
        soften_vulkan_for_android(path)
    patch_video_activity_config()
    print("---- results ----")
    for x in OK:
        print(" OK", x)
    for x in ERR:
        print(" FAIL", x)
    if ERR:
        print("[mod] inject_mpv_phase_c FAILED")
        return 1
    print("[mod] inject_mpv_phase_c SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
