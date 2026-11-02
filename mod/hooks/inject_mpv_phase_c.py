#!/usr/bin/env python3
"""
MPV 阶段 C 强化：
- 字幕字体：setOption + MpvSubtitleStylePolicy.ensureFontsReady（subfont.ttf）
- 暂停旋转：surface 尺寸 + Activity 配置变更时强制 layout/invalidate
- 仅用 setOption / 反射，保证可编译
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_OPT = "MPV_PHASE_C_SUB_OPTIONS_V2"
MARKER_SURF = "MPV_PHASE_C_SURFACE_REBIND_V2"
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
    return "setOption"


def inject_sub_options(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_OPT in text:
        OK.append(f"sub options already: {rel(path)}")
        return
    # remove old phase C sub markers if broken
    text = re.sub(r"[^\n]*MPV_PHASE_C_SUB_OPTIONS[^\n]*\n(?:[^\n]*\n){0,40}?", "", text, count=1)

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
        or "try {" in lines[j]
        or "MPV_PHASE_C" in lines[j]
    ):
        j += 1
    ind = re.match(r"^(\s*)", lines[idx]).group(1) if re.match(r"^(\s*)", lines[idx]) else "        "
    block = f"""{ind}// {MARKER_OPT}
{ind}try {{
{ind}    Class<?> _pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
{ind}    try {{ _pol.getMethod("ensureFontsReady").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    Object _dir = null, _font = null, _style = null, _color = null;
{ind}    try {{ _dir = _pol.getMethod("getSubFontsDirProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ _font = _pol.getMethod("getSubFontProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ _style = _pol.getMethod("getAssForceStyle").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ _color = _pol.getMethod("getSubColorProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    {callee}("sub-ass-override", "force");
{ind}    {callee}("embeddedfonts", "yes");
{ind}    if (_dir instanceof String && !((String) _dir).isEmpty()) {callee}("sub-fonts-dir", (String) _dir);
{ind}    if (_font instanceof String && !((String) _font).isEmpty()) {callee}("sub-font", (String) _font);
{ind}    if (_style instanceof String && !((String) _style).isEmpty()) {callee}("sub-ass-force-style", (String) _style);
{ind}    if (_color instanceof String && !((String) _color).isEmpty()) {callee}("sub-color", (String) _color);
{ind}    try {{ {callee}("sub-border-color", "0.0/0.0/0.0"); }} catch (Throwable ignored) {{}}
{ind}    try {{ {callee}("sub-border-size", "2"); }} catch (Throwable ignored) {{}}
{ind}}} catch (Throwable ignored) {{
{ind}    try {{ {callee}("sub-ass-override", "force"); }} catch (Throwable ignored2) {{}}
{ind}    try {{ {callee}("embeddedfonts", "yes"); }} catch (Throwable ignored2) {{}}
{ind}}}
{ind}try {{ {callee}("gpu-context", "android"); }} catch (Throwable ignored) {{}}
"""
    lines.insert(j, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append(f"sub options V2 via {callee} in {rel(path)}")


def inject_surface_rebind(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_SURF in text:
        OK.append(f"surface already: {rel(path)}")
        return
    # strip old helper if present
    text = re.sub(
        r"\n\s*// MPV_PHASE_C_SURFACE_REBIND[\s\S]*?private void modPhaseCOnSurfaceSize\([\s\S]*?\n    \}\n",
        "\n",
        text,
        count=1,
    )

    helper = f'''
    // {MARKER_SURF}
    private void modPhaseCOnSurfaceSize(int width, int height) {{
        if (width <= 0 || height <= 0) return;
        final String size = width + "x" + height;
        try {{
            for (String name : new String[] {{ "setOption", "setProperty" }}) {{
                try {{
                    java.lang.reflect.Method m = getClass().getMethod(name, String.class, String.class);
                    try {{ m.invoke(this, "android-surface-size", size); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "osd-width", String.valueOf(width)); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "osd-height", String.valueOf(height)); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "force-render", "yes"); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "force-window", "yes"); }} catch (Throwable ignored) {{}}
                }} catch (Throwable ignored) {{}}
            }}
            // 暂停时旋转：轻触 pause 触发 VO 刷新（再设回暂停）
            try {{
                java.lang.reflect.Method gp = null;
                try {{ gp = getClass().getMethod("getProperty", String.class); }} catch (Throwable ignored) {{}}
                boolean paused = false;
                if (gp != null) {{
                    Object p = gp.invoke(this, "pause");
                    if (p instanceof Boolean) paused = (Boolean) p;
                    else if (p != null) {{
                        String s = String.valueOf(p);
                        paused = "yes".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
                    }}
                }}
                if (paused) {{
                    for (String name : new String[] {{ "setOption", "setProperty" }}) {{
                        try {{
                            java.lang.reflect.Method m = getClass().getMethod(name, String.class, String.class);
                            m.invoke(this, "pause", "no");
                            m.invoke(this, "force-render", "yes");
                            m.invoke(this, "pause", "yes");
                        }} catch (Throwable ignored) {{}}
                    }}
                }}
            }} catch (Throwable ignored) {{}}
        }} catch (Throwable ignored) {{}}
    }}
'''
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("no class end")
        return
    text = text[:idx] + "\n" + helper + text[idx:]

    hooked = 0
    for cre, repl in [
        (re.compile(r"(void\s+surfaceChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
         r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}"),
        (re.compile(r"(public\s+void\s+onSurfaceTextureSizeChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
         r"\1\n        try { modPhaseCOnSurfaceSize(width, height); } catch (Throwable ignored) {}"),
    ]:
        text, n = cre.subn(repl, text, count=3)
        hooked += n

    def repl_size(m):
        nonlocal hooked
        hooked += 1
        return m.group(1) + f"\n        try {{ modPhaseCOnSurfaceSize({m.group(2)}, {m.group(3)}); }} catch (Throwable ignored) {{}}"

    text, n = re.subn(
        r"(void\s+setVideoSurfaceSize\s*\(\s*int\s+(\w+)\s*,\s*int\s+(\w+)\s*\)\s*\{)",
        repl_size,
        text,
        count=3,
    )
    path.write_text(text, encoding="utf-8")
    OK.append(f"surface V2 hooks={hooked} in {rel(path)}")


def soften_gpu(path: Path) -> None:
    t = path.read_text(encoding="utf-8")
    nt, n = re.subn(
        r'setOption\("gpu-api",\s*"vulkan"\)',
        'setOption("gpu-api", "opengl") /* MPV_PHASE_C_GPU_API */',
        t,
        count=2,
    )
    # faster switch: smaller demuxer cache
    nt2, n2 = re.subn(
        r'setOption\("demuxer-max-bytes",\s*"52428800"\)',
        'setOption("demuxer-max-bytes", "16777216") /* faster engine switch */',
        nt,
        count=2,
    )
    nt3, n3 = re.subn(
        r'setOption\("cache-secs",\s*"10"\)',
        'setOption("cache-secs", "4") /* faster engine switch */',
        nt2,
        count=2,
    )
    if n or n2 or n3:
        path.write_text(nt3, encoding="utf-8")
    OK.append(f"gpu/cache tweak vulkan->{n} demux->{n2} cache->{n3}")


def patch_video_activity_config() -> None:
    marker = "MPV_PHASE_C_CONFIG_CHANGE_V2"
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
        # remove old marker blocks roughly
        t = re.sub(r"\n\s*// MPV_PHASE_C_CONFIG_CHANGE[\s\S]{0,800}?catch \(Throwable ignored\) \{\}\n", "\n", t, count=2)
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
                    final int w = Math.max(v.getWidth(), 1);
                    final int h = Math.max(v.getHeight(), 1);
                    v.post(() -> {{
                        try {{
                            v.requestLayout();
                            v.invalidate();
                            // 通知底层 surface 尺寸（若 Player 暴露了 setVideoSurfaceSize）
                            try {{
                                Object pl = null;
                                try {{
                                    java.lang.reflect.Method gp = v.getClass().getMethod("getPlayer");
                                    pl = gp.invoke(v);
                                }} catch (Throwable ignored) {{}}
                                if (pl != null) {{
                                    try {{
                                        java.lang.reflect.Method m2 = pl.getClass().getMethod("modPhaseCOnSurfaceSize", int.class, int.class);
                                        m2.invoke(pl, w, h);
                                    }} catch (Throwable ignored) {{
                                        try {{
                                            java.lang.reflect.Method m3 = pl.getClass().getMethod("setVideoSurfaceSize", int.class, int.class);
                                            m3.invoke(pl, w, h);
                                        }} catch (Throwable ignored2) {{}}
                                    }}
                                }}
                            }} catch (Throwable ignored) {{}}
                        }} catch (Throwable ignored) {{}}
                    }});
                    v.postDelayed(() -> {{
                        try {{ v.requestLayout(); v.invalidate(); }} catch (Throwable ignored) {{}}
                    }}, 120);
                }}
            }}
        }} catch (Throwable ignored) {{}}
"""
        )
        t = t[: m.start()] + insert + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        OK.append(f"config V2: {rel(path)}")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
    else:
        inject_sub_options(path)
        inject_surface_rebind(path)
        soften_gpu(path)
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
