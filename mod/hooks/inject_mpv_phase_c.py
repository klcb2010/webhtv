#!/usr/bin/env python3
"""
MPV 阶段 C：
1) 字幕字体/颜色：在 MpvPlayer 启动 option 后补强 sub-fonts-dir / sub-font / ass 样式
2) 暂停时横竖屏撕裂：surface 尺寸变化后强制 android-surface-size + force-render，必要时短暂反转 pause 触发重绘

不引入日志。
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


def inject_sub_options(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_OPT in text:
        OK.append(f"sub options already: {rel(path)}")
        return
    lines = text.splitlines(keepends=True)
    # Prefer after PHASE_A block or after hwdec setOption
    idx = -1
    callee = "setOption"
    for i, ln in enumerate(lines):
        if "MPV_PHASE_A_OPTIONS" in ln:
            idx = i
        if idx < 0 and re.search(r'setOption\s*\(\s*"hwdec"', ln):
            idx = i
            callee = "setOption"
    if idx < 0:
        for i, ln in enumerate(lines):
            m = re.search(r'((?:this\.)?[A-Za-z_][\w\.]*)\s*\(\s*"hwdec"', ln)
            if m:
                idx = i
                callee = m.group(1)
                break
    if idx < 0:
        ERR.append(f"no inject site for sub options in {rel(path)}")
        return
    # skip to end of contiguous try setOption block after marker
    j = idx + 1
    while j < len(lines) and (
        "setOption" in lines[j]
        or "setProperty" in lines[j]
        or lines[j].strip().startswith("//")
        or lines[j].strip() == ""
        or "MPV_PHASE_A" in lines[j]
        or "catch (Throwable" in lines[j]
    ):
        j += 1
    insert_at = j
    indent_m = re.match(r"^(\s*)", lines[idx])
    ind = indent_m.group(1) if indent_m else "        "
    block = (
        f"{ind}// {MARKER_OPT}: subtitle fonts + colors (phase C)\n"
        f"{ind}try {{ {callee}(\"sub-ass-override\", \"force\"); }} catch (Throwable ignored) {{}}\n"
        f"{ind}try {{ {callee}(\"embeddedfonts\", \"yes\"); }} catch (Throwable ignored) {{}}\n"
        f"{ind}try {{ {callee}(\"sub-font\", \"yiqicukai\"); }} catch (Throwable ignored) {{}}\n"
        f"{ind}try {{\n"
        f"{ind}    java.io.File _modFonts = new java.io.File(getContext().getFilesDir(), \"subtitle_fonts\");\n"
        f"{ind}    if (!_modFonts.isDirectory()) _modFonts = new java.io.File(getContext().getFilesDir(), \"mpv/fonts\");\n"
        f"{ind}    if (_modFonts.isDirectory()) {callee}(\"sub-fonts-dir\", _modFonts.getAbsolutePath());\n"
        f"{ind}}} catch (Throwable ignored) {{}}\n"
        f"{ind}try {{\n"
        f"{ind}    // 与壳子用户字幕样式对齐（有则用反射调 policy）\n"
        f"{ind}    Class<?> pol = Class.forName(\"com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy\");\n"
        f"{ind}    java.lang.reflect.Method gf = pol.getMethod(\"getSubFontProperty\");\n"
        f"{ind}    java.lang.reflect.Method gd = pol.getMethod(\"getSubFontsDirProperty\");\n"
        f"{ind}    java.lang.reflect.Method gs = pol.getMethod(\"getAssForceStyle\");\n"
        f"{ind}    Object font = gf.invoke(null);\n"
        f"{ind}    Object dir = gd.invoke(null);\n"
        f"{ind}    Object style = gs.invoke(null);\n"
        f"{ind}    if (dir instanceof String && !((String) dir).isEmpty()) {callee}(\"sub-fonts-dir\", (String) dir);\n"
        f"{ind}    if (font instanceof String && !((String) font).isEmpty()) {callee}(\"sub-font\", (String) font);\n"
        f"{ind}    if (style instanceof String && !((String) style).isEmpty()) {callee}(\"sub-ass-force-style\", (String) style);\n"
        f"{ind}}} catch (Throwable ignored) {{}}\n"
        # Vulkan 在部分机型旋转暂停时易撕裂；保留用户 conf，但加 opengl 作为可回退注释在 surface 路径处理
        f"{ind}try {{ {callee}(\"gpu-context\", \"android\"); }} catch (Throwable ignored) {{}}\n"
    )
    lines.insert(insert_at, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append(f"sub options at line {insert_at} in {rel(path)}")


def inject_surface_rebind(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_SURF in text:
        OK.append(f"surface rebind already: {rel(path)}")
        return

    # Append a helper method and hook into surface size / setVideoSurfaceSize patterns
    helper = f'''
    // {MARKER_SURF}
    private void modPhaseCOnSurfaceSize(int width, int height) {{
        if (width <= 0 || height <= 0) return;
        try {{
            String size = width + "x" + height;
            try {{ setProperty("android-surface-size", size); }} catch (Throwable ignored) {{}}
            try {{ setOption("android-surface-size", size); }} catch (Throwable ignored) {{}}
            try {{ setProperty("force-render", "yes"); }} catch (Throwable ignored) {{}}
            // 暂停时旋转：强制一次渲染管线刷新，避免撕裂/残帧
            try {{
                boolean paused = false;
                try {{
                    Object p = getProperty("pause");
                    if (p instanceof Boolean) paused = (Boolean) p;
                    else if (p != null) paused = "yes".equalsIgnoreCase(String.valueOf(p)) || "true".equalsIgnoreCase(String.valueOf(p));
                }} catch (Throwable ignored) {{}}
                if (paused) {{
                    try {{ command(new String[] {{ "raw", "cycle", "force-window" }}); }} catch (Throwable ignored) {{}}
                    try {{ setProperty("force-window", "yes"); }} catch (Throwable ignored) {{}}
                    try {{ setProperty("force-render", "yes"); }} catch (Throwable ignored) {{}}
                }}
            }} catch (Throwable ignored) {{}}
        }} catch (Throwable ignored) {{}}
    }}
'''

    # insert helper before last closing brace of class
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append(f"cannot find class end in {rel(path)}")
        return
    text = text[:idx] + "\n" + helper + text[idx:]

    # Hook common surface size call sites
    patterns = [
        # surfaceChanged(SurfaceHolder holder, int format, int width, int height)
        (
            re.compile(
                r"(void\s+surfaceChanged\s*\(\s*[^)]*\)\s*\{)",
                re.M,
            ),
            r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
        (
            re.compile(
                r"(public\s+void\s+onSurfaceTextureSizeChanged\s*\(\s*[^)]*\)\s*\{)",
                re.M,
            ),
            r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
        (
            re.compile(
                r"(void\s+setVideoSurfaceSize\s*\(\s*int\s+(\w+)\s*,\s*int\s+(\w+)\s*\)\s*\{)",
                re.M,
            ),
            None,  # special
        ),
    ]

    hooked = 0
    for cre, repl in patterns:
        if repl is None:
            def repl_size(m):
                nonlocal hooked
                hooked += 1
                w, h = m.group(2), m.group(3)
                return (
                    m.group(1)
                    + f"\n        try {{ modPhaseCOnSurfaceSize({w}, {h}); }} catch (Throwable ignored) {{}}"
                )

            text2, n = cre.subn(repl_size, text, count=3)
            text = text2
            continue
        text2, n = cre.subn(repl, text, count=3)
        if n:
            hooked += n
            text = text2

    # Also patch after setVideoSurface / attach if present
    if "setVideoSurface" in text and "modPhaseCOnSurfaceSize" in text:
        # try to find surface holder frame size usage
        pass

    if hooked == 0:
        # Fallback: inject into any method that assigns surface width/height fields
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
    if hooked:
        OK.append(f"surface rebind hooked x{hooked} in {rel(path)}")
    else:
        OK.append(f"surface helper added (no standard hook site) in {rel(path)}")


def patch_video_activity_config() -> None:
    """Rotation while paused: request layout + optional player surface refresh."""
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
        # Find onConfigurationChanged
        m = re.search(
            r"(void\s+onConfigurationChanged\s*\(\s*@?NonNull\s+Configuration\s+\w+\s*\)\s*\{"
            r"|void\s+onConfigurationChanged\s*\(\s*Configuration\s+\w+\s*\)\s*\{)",
            t,
        )
        if not m:
            OK.append(f"no onConfigurationChanged: {rel(path)}")
            continue
        insert = (
            m.group(0)
            + f"""
        // {marker}: MPV pause+rotate tear mitigation
        try {{
            if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                    == com.fongmi.android.tv.setting.PlayerSetting.MPV) {{
                android.view.View pv = null;
                try {{ pv = getExoView(); }} catch (Throwable ignored) {{}}
                if (pv != null) {{
                    pv.post(() -> {{
                        try {{ pv.requestLayout(); pv.invalidate(); }} catch (Throwable ignored) {{}}
                    }});
                }}
            }}
        }} catch (Throwable ignored) {{}}
"""
        )
        t = t[: m.start()] + insert + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        OK.append(f"onConfigurationChanged patched: {rel(path)}")


def soften_vulkan_for_android(path: Path) -> None:
    """Optional: switch phase-A gpu-api vulkan -> opengl on Android for rotate stability."""
    t = path.read_text(encoding="utf-8")
    if "MPV_PHASE_C_GPU_API" in t:
        OK.append("gpu-api already softened")
        return
    # Replace injected vulkan default with opengl (more stable with surface recreate)
    nt, n = re.subn(
        r'setOption\("gpu-api",\s*"vulkan"\)',
        'setOption("gpu-api", "opengl") /* MPV_PHASE_C_GPU_API: opengl for rotate stability */',
        t,
        count=2,
    )
    if n:
        path.write_text(nt, encoding="utf-8")
        OK.append(f"gpu-api vulkan->opengl x{n} in {rel(path)}")
    else:
        OK.append("gpu-api vulkan string not found (ok if not yet injected)")


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
