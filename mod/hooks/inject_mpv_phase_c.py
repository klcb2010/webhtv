#!/usr/bin/env python3
"""
MPV 阶段 C 强化 V3：
- slang 简体优先 + applyRuntime 字体（setProperty）
- 暂停旋转：配置变更时 clearVideoSurface + 重绑（若 API 存在）
- 可重复执行；失败 exit 1
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_OPT = "MPV_PHASE_C_SUB_OPTIONS_V3"
MARKER_SURF = "MPV_PHASE_C_SURFACE_REBIND_V3"
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
    return m.group(1) if m else "setOption"


def inject_sub_options(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_OPT in text:
        OK.append(f"sub options already V3: {rel(path)}")
        return
    # strip older phase-C option blocks (best-effort)
    text = re.sub(r"[^\n]*MPV_PHASE_C_SUB_OPTIONS[\s\S]{0,2500}?try \{ callee\(\"gpu-context\"[\s\S]{0,200}?\n", "\n", text, count=1)
    text = re.sub(r"[^\n]*MPV_PHASE_C_SUB_OPTIONS[\s\S]{0,80}\n(?:[ \t]*try \{[\s\S]{0,400}?catch \(Throwable ignored\) \{\}\n)+", "\n", text, count=2)

    lines = text.splitlines(keepends=True)
    idx = -1
    callee = detect_callee(text)
    for i, ln in enumerate(lines):
        if "MPV_PHASE_A_OPTIONS" in ln:
            idx = i
        if idx < 0 and re.search(r'\(\s*"hwdec"\s*,', ln):
            idx = i
    if idx < 0:
        ERR.append(f"no inject site in {rel(path)}")
        return
    j = idx + 1
    while j < len(lines) and (
        any(x in lines[j] for x in ("setOption", "setProperty", "MPV_PHASE_A", "MPV_PHASE_C", "try {", "catch (Throwable"))
        or lines[j].strip().startswith("//")
        or lines[j].strip() == ""
    ):
        j += 1
    ind = re.match(r"^(\s*)", lines[idx]).group(1) if re.match(r"^(\s*)", lines[idx]) else "        "
    block = f"""{ind}// {MARKER_OPT}
{ind}try {{ {callee}("slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"); }} catch (Throwable ignored) {{}}
{ind}try {{
{ind}    Class<?> _pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
{ind}    try {{ _pol.getMethod("ensureFontsReady").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ _pol.getMethod("applyRuntime", Object.class).invoke(null, this); }} catch (Throwable ignored) {{}}
{ind}}} catch (Throwable ignored) {{}}
{ind}try {{ {callee}("gpu-context", "android"); }} catch (Throwable ignored) {{}}
"""
    lines.insert(j, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append(f"sub options V3 + slang via {callee}")


def inject_surface_rebind(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_SURF in text:
        OK.append("surface already V3")
        return
    text = re.sub(
        r"\n\s*// MPV_PHASE_C_SURFACE_REBIND[\s\S]*?private void modPhaseCOnSurfaceSize\([\s\S]*?\n    \}\n",
        "\n",
        text,
        count=2,
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
                    try {{ m.invoke(this, "force-render", "yes"); }} catch (Throwable ignored) {{}}
                    try {{ m.invoke(this, "force-window", "yes"); }} catch (Throwable ignored) {{}}
                }} catch (Throwable ignored) {{}}
            }}
            try {{
                Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
                pol.getMethod("applyRuntime", Object.class).invoke(null, this);
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

    text, _ = re.subn(
        r"(void\s+setVideoSurfaceSize\s*\(\s*int\s+(\w+)\s*,\s*int\s+(\w+)\s*\)\s*\{)",
        repl_size,
        text,
        count=3,
    )
    path.write_text(text, encoding="utf-8")
    OK.append(f"surface V3 hooks={hooked}")


def soften_gpu(path: Path) -> None:
    t = path.read_text(encoding="utf-8")
    nt, n = re.subn(
        r'setOption\("gpu-api",\s*"vulkan"\)',
        'setOption("gpu-api", "opengl") /* MPV_PHASE_C_GPU_API */',
        t,
        count=2,
    )
    nt, n2 = re.subn(
        r'setOption\("demuxer-max-bytes",\s*"52428800"\)',
        'setOption("demuxer-max-bytes", "16777216")',
        nt,
        count=2,
    )
    nt, n3 = re.subn(
        r'setOption\("cache-secs",\s*"10"\)',
        'setOption("cache-secs", "4")',
        nt,
        count=2,
    )
    if n or n2 or n3:
        path.write_text(nt, encoding="utf-8")
    OK.append(f"gpu/cache n={n}/{n2}/{n3}")


def patch_video_activity_config() -> None:
    marker = "MPV_PHASE_C_CONFIG_CHANGE_V3"
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
            OK.append(f"config already V3: {rel(path)}")
            continue
        t = re.sub(r"\n\s*// MPV_PHASE_C_CONFIG_CHANGE[\s\S]{0,1200}?catch \(Throwable ignored\) \{\}\n", "\n", t, count=3)
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
        // {marker}: 暂停旋转时重绑 surface，避免残帧/撕裂
        try {{
            if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                    == com.fongmi.android.tv.setting.PlayerSetting.MPV) {{
                android.view.View pv = null;
                try {{ pv = getExoView(); }} catch (Throwable ignored) {{}}
                if (pv != null) {{
                    final android.view.View v = pv;
                    v.post(() -> {{
                        try {{
                            Object player = null;
                            try {{
                                java.lang.reflect.Method gp = v.getClass().getMethod("getPlayer");
                                player = gp.invoke(v);
                            }} catch (Throwable ignored) {{}}
                            // clear + rebind surface（ExoPlayer / MpvPlayer 若实现）
                            android.view.Surface surface = null;
                            try {{
                                if (v instanceof android.view.SurfaceView) {{
                                    android.view.SurfaceHolder h = ((android.view.SurfaceView) v).getHolder();
                                    if (h != null) surface = h.getSurface();
                                }}
                            }} catch (Throwable ignored) {{}}
                            if (player != null) {{
                                try {{
                                    java.lang.reflect.Method clear = player.getClass().getMethod("clearVideoSurface");
                                    clear.invoke(player);
                                }} catch (Throwable ignored) {{}}
                                if (surface != null && surface.isValid()) {{
                                    try {{
                                        java.lang.reflect.Method setS = player.getClass().getMethod("setVideoSurface", android.view.Surface.class);
                                        setS.invoke(player, surface);
                                    }} catch (Throwable ignored) {{}}
                                }}
                                try {{
                                    Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
                                    pol.getMethod("applyRuntime", Object.class).invoke(null, player);
                                }} catch (Throwable ignored) {{}}
                                try {{
                                    java.lang.reflect.Method m2 = player.getClass().getMethod("modPhaseCOnSurfaceSize", int.class, int.class);
                                    m2.invoke(player, Math.max(v.getWidth(), 1), Math.max(v.getHeight(), 1));
                                }} catch (Throwable ignored) {{}}
                            }}
                            v.requestLayout();
                            v.invalidate();
                        }} catch (Throwable ignored) {{}}
                    }});
                    v.postDelayed(() -> {{
                        try {{ v.requestLayout(); v.invalidate(); }} catch (Throwable ignored) {{}}
                    }}, 160);
                }}
            }}
        }} catch (Throwable ignored) {{}}
"""
        )
        t = t[: m.start()] + insert + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        OK.append(f"config V3 surface rebind: {rel(path)}")


def patch_font_setting_runtime() -> None:
    """字体切换时对当前 MPV applyRuntime。"""
    marker = "MPV_FONT_RUNTIME_APPLY"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        OK.append("Setting.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if marker in t:
        OK.append("font runtime already")
        return
    # after putSubtitleFontIndex body start
    m = re.search(r"(public static void putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{)", t)
    if not m:
        OK.append("putSubtitleFontIndex not found")
        return
    insert = (
        m.group(1)
        + f"""
        // {marker}
        try {{
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();
        }} catch (Throwable ignored) {{}}
"""
    )
    t = t[: m.start()] + insert + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    OK.append("putSubtitleFontIndex ensureFontsReady")


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
    patch_font_setting_runtime()
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
