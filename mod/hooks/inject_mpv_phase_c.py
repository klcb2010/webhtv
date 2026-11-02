#!/usr/bin/env python3
"""
MPV 阶段 C V4（针对 private API）：
在 MpvPlayer 类内部注入 public 方法，直接调用同类 private setOption / mpvSetPropertyString。
外部 MpvSubtitleStylePolicy 只调 public 入口，不再 getMethod 私有方法。
旋转：去掉 PlayerView 误当 SurfaceView + clearVideoSurface 不恢复的逻辑。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE = "MPV_MOD_PUBLIC_BRIDGE_V4"
MARKER_OPT = "MPV_PHASE_C_SUB_OPTIONS_V4"
MARKER_SURF = "MPV_PHASE_C_SURFACE_V4"
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


def detect_private_setters(text: str) -> tuple[str | None, str | None]:
    """Return (setOptionName, setPropertyName) if found as methods in class."""
    opt = None
    prop = None
    # private void setOption(String
    if re.search(r"(?:private|protected)?\s+void\s+setOption\s*\(\s*String", text):
        opt = "setOption"
    if re.search(r"(?:private|protected)?\s+void\s+mpvSetPropertyString\s*\(\s*String", text):
        prop = "mpvSetPropertyString"
    elif re.search(r"(?:private|protected)?\s+void\s+setProperty\s*\(\s*String", text):
        prop = "setProperty"
    # also boolean returning
    if prop is None and re.search(r"mpvSetPropertyString\s*\(", text):
        prop = "mpvSetPropertyString"
    if opt is None and re.search(r"\bsetOption\s*\(\s*\"", text):
        opt = "setOption"
    return opt, prop


def inject_public_bridge(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_BRIDGE in text:
        OK.append(f"bridge already: {rel(path)}")
        return
    opt, prop = detect_private_setters(text)
    if not opt and not prop:
        ERR.append(f"no setOption/mpvSetPropertyString found in {rel(path)}")
        return

    # Prefer property for runtime; option for init-time slang
    set_runtime = prop or opt
    set_init = opt or prop

    bridge = f'''
    // {MARKER_BRIDGE}
    /** MOD: 当前活跃实例，供设置页切字体时写回运行时属性（非反射 private）。 */
    public static volatile MpvPlayer sModActivePlayer;

    /** MOD: 供 MpvSubtitleStylePolicy / Setting 调用的公开运行时入口。 */
    public void modApplySubtitleStyleRuntime(String fontsDir, String font, String forceStyle, String color) {{
        if (fontsDir != null && !fontsDir.isEmpty()) {{
            modSetRuntimeProp("sub-fonts-dir", fontsDir);
        }}
        if (font != null && !font.isEmpty()) {{
            modSetRuntimeProp("sub-font", font);
        }}
        if (forceStyle != null && !forceStyle.isEmpty()) {{
            modSetRuntimeProp("sub-ass-force-style", forceStyle);
        }}
        if (color != null && !color.isEmpty()) {{
            modSetRuntimeProp("sub-color", color);
        }}
        modSetRuntimeProp("sub-ass-override", "force");
        modSetRuntimeProp("embeddedfonts", "yes");
    }}

    public void modSetRuntimeProp(String name, String value) {{
        if (name == null || value == null) return;
        try {{
'''
    if prop:
        bridge += f'''            try {{ {prop}(name, value); return; }} catch (Throwable ignored) {{}}
'''
    if opt and opt != prop:
        bridge += f'''            try {{ {opt}(name, value); return; }} catch (Throwable ignored) {{}}
'''
    bridge += '''        } catch (Throwable ignored) {
        }
    }

    public void modSetInitOption(String name, String value) {
        if (name == null || value == null) return;
        try {
'''
    if opt:
        bridge += f'''            try {{ {opt}(name, value); return; }} catch (Throwable ignored) {{}}
'''
    if prop and prop != opt:
        bridge += f'''            try {{ {prop}(name, value); return; }} catch (Throwable ignored) {{}}
'''
    bridge += '''        } catch (Throwable ignored) {
        }
    }

    public void modNotifySurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        String size = width + "x" + height;
        modSetRuntimeProp("android-surface-size", size);
    }
'''

    # Register active player: find constructor or prepare/init method
    # Insert after "class MpvPlayer" opening - better at end before last }
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return
    text = text[:idx] + "\n" + bridge + text[idx:]

    # Hook sModActivePlayer = this on first load / setVideoSurface
    # After construction is hard; set on setVideoSurface / surfaceCreated
    if "sModActivePlayer = this" not in text:
        for pat in [
            r"(void\s+setVideoSurface\s*\([^)]*\)\s*\{)",
            r"(void\s+surfaceCreated\s*\([^)]*\)\s*\{)",
            r"(public\s+void\s+prepare\s*\([^)]*\)\s*\{)",
        ]:
            text2, n = re.subn(
                pat,
                r"\1\n        try { sModActivePlayer = this; } catch (Throwable ignored) {}",
                text,
                count=1,
            )
            if n:
                text = text2
                OK.append(f"sModActivePlayer hook via {pat[:40]}")
                break

    # surface size hooks call modNotifySurfaceSize (internal, safe)
    for cre, repl in [
        (
            re.compile(r"(void\s+surfaceChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
            r"\1\n        try { modNotifySurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
        (
            re.compile(r"(public\s+void\s+onSurfaceTextureSizeChanged\s*\(\s*[^)]*\)\s*\{)", re.M),
            r"\1\n        try { modNotifySurfaceSize(width, height); } catch (Throwable ignored) {}",
        ),
    ]:
        text, n = cre.subn(repl, text, count=2)
        if n:
            OK.append(f"surface size hook x{n}")

    path.write_text(text, encoding="utf-8")
    OK.append(f"public bridge injected opt={opt} prop={prop}")


def inject_init_slang_and_style(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER_OPT in text:
        OK.append("init slang already")
        return
    lines = text.splitlines(keepends=True)
    idx = -1
    for i, ln in enumerate(lines):
        if "MPV_PHASE_A_OPTIONS" in ln:
            idx = i
        if idx < 0 and re.search(r'\(\s*"hwdec"\s*,', ln):
            idx = i
    if idx < 0:
        ERR.append("no hwdec/option inject site")
        return
    j = idx + 1
    while j < len(lines) and (
        any(x in lines[j] for x in ("setOption", "setProperty", "MPV_PHASE_A", "MPV_PHASE_C", "try {", "catch (Throwable"))
        or lines[j].strip().startswith("//")
        or lines[j].strip() == ""
    ):
        j += 1
    ind = re.match(r"^(\s*)", lines[idx]).group(1) if re.match(r"^(\s*)", lines[idx]) else "        "
    # 类内部直接调 modSetInitOption / modApply — 初始化时 this 可用
    block = f"""{ind}// {MARKER_OPT}
{ind}try {{ modSetInitOption("slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"); }} catch (Throwable ignored) {{}}
{ind}try {{
{ind}    Class<?> _pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
{ind}    try {{ _pol.getMethod("ensureFontsReady").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    Object dir = null, font = null, style = null, color = null;
{ind}    try {{ dir = _pol.getMethod("getSubFontsDirProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ font = _pol.getMethod("getSubFontProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ style = _pol.getMethod("getAssForceStyle").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    try {{ color = _pol.getMethod("getSubColorProperty").invoke(null); }} catch (Throwable ignored) {{}}
{ind}    modApplySubtitleStyleRuntime(
{ind}        dir instanceof String ? (String) dir : null,
{ind}        font instanceof String ? (String) font : null,
{ind}        style instanceof String ? (String) style : null,
{ind}        color instanceof String ? (String) color : null);
{ind}}} catch (Throwable ignored) {{}}
"""
    lines.insert(j, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append("init slang + style via public bridge")


def patch_policy_use_public_bridge() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    if not path.is_file():
        # copy from mod tree if only in mod
        src = Path(__file__).resolve().parent.parent / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
        ERR.append(f"policy missing at {path}")
        return
    # overwrite applyRuntime to use public bridge
    text = path.read_text(encoding="utf-8")
    if "modApplySubtitleStyleRuntime" in text and "sModActivePlayer" in text:
        OK.append("policy already uses public bridge")
        return
    # replace applyRuntime method body by rewriting whole file section
    path.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("MpvSubtitleStylePolicy rewritten for public bridge")


POLICY_JAVA = r'''package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.subtitle.SubtitleFontManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * MPV 字幕样式：准备字体文件 + 通过 MpvPlayer 公开桥接方法写入运行时属性。
 * 不反射 private setOption / mpvSetPropertyString。
 */
public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";

    private MpvSubtitleStylePolicy() {
    }

    public static File ensureFontsReady() {
        try {
            SubtitleFontManager.prepareAllFonts();
        } catch (Throwable ignored) {
        }
        File dir = null;
        try {
            dir = SubtitleFontManager.getFontsDir();
        } catch (Throwable ignored) {
        }
        if (dir == null) {
            try {
                dir = new File(App.get().getFilesDir(), "subtitle_fonts");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        if (dir == null) return null;
        try {
            int idx = Setting.getSubtitleFontIndex();
            String id = SubtitleFontManager.idOf(idx);
            File src = SubtitleFontManager.getFontFile(idx);
            if (src != null && src.isFile()) {
                forceCopy(src, new File(dir, "subfont.ttf"));
                if (id != null && !id.isEmpty() && !"system".equals(id)) {
                    forceCopy(src, new File(dir, id + ".ttf"));
                }
            }
        } catch (Throwable ignored) {
        }
        return dir;
    }

    private static void forceCopy(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    public static String getAssForceStyle() {
        ensureFontsReady();
        int argb = Setting.getSubtitleColorArgb();
        String primary = toAssColour(argb);
        String font = getSubFontProperty();
        return "FontName=" + font
                + ",PrimaryColour=" + primary
                + ",SecondaryColour=" + primary
                + ",OutlineColour=&H000000&,BackColour=&H000000&"
                + ",Outline=2,Shadow=1,BorderStyle=1";
    }

    public static String getSubColorProperty() {
        int argb = Setting.getSubtitleColorArgb();
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return String.format(java.util.Locale.US, "%.3f/%.3f/%.3f", r, g, b);
    }

    public static String getSubBorderColorProperty() {
        return "0.0/0.0/0.0";
    }

    /** 文件主名与目录内 id.ttf 对齐；系统默认 sans-serif */
    public static String getSubFontProperty() {
        ensureFontsReady();
        try {
            int idx = Setting.getSubtitleFontIndex();
            String id = SubtitleFontManager.idOf(idx);
            if (id != null && "system".equals(id)) return "sans-serif";
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {
        }
        return "yiqicukai";
    }

    public static String getSubFontsDirProperty() {
        File dir = ensureFontsReady();
        return (dir != null && dir.isDirectory()) ? dir.getAbsolutePath() : "";
    }

    /**
     * 对「当前活跃」MPV 实例应用样式。优先 sModActivePlayer 公开 API。
     * @return true 表示已调用到公开桥接；false 表示当前无活跃 MPV（例如正在用 EXO）
     */
    public static boolean applyRuntimeToActive() {
        ensureFontsReady();
        Object active = null;
        try {
            Class<?> cl = Class.forName("androidx.media3.mpvplayer.MpvPlayer");
            active = cl.getField("sModActivePlayer").get(null);
        } catch (Throwable ignored) {
        }
        if (active == null) {
            try {
                Class<?> cl = Class.forName("com.fongmi.android.tv.player.mpv.MpvPlayer");
                active = cl.getField("sModActivePlayer").get(null);
            } catch (Throwable ignored) {
            }
        }
        if (active == null) return false;
        return applyRuntime(active);
    }

    /**
     * @param player 必须是 MpvPlayer 实例（含 modApplySubtitleStyleRuntime）
     * @return 是否成功调用公开方法
     */
    public static boolean applyRuntime(Object player) {
        if (player == null) return false;
        ensureFontsReady();
        String dir = getSubFontsDirProperty();
        String font = getSubFontProperty();
        String style = getAssForceStyle();
        String color = getSubColorProperty();
        try {
            java.lang.reflect.Method m = player.getClass().getMethod(
                    "modApplySubtitleStyleRuntime", String.class, String.class, String.class, String.class);
            m.invoke(player, dir, font, style, color);
            return true;
        } catch (Throwable t) {
            // 公开方法不存在则失败，不再假装成功
            return false;
        }
    }

    private static String toAssColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = (argb & 0xFF);
        return String.format(java.util.Locale.US, "&H00%02X%02X%02X&", b, g, r);
    }
}
'''


def patch_setting_font_apply() -> None:
    marker = "MPV_FONT_RUNTIME_APPLY_V4"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        OK.append("Setting missing")
        return
    t = path.read_text(encoding="utf-8")
    if marker in t:
        OK.append("font apply already V4")
        return
    # remove weak V1 ensure-only if present
    t = re.sub(
        r"\n\s*// MPV_FONT_RUNTIME_APPLY[\s\S]{0,200}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
        count=2,
    )
    m = re.search(r"(public static void putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{)", t)
    if not m:
        OK.append("putSubtitleFontIndex not found")
        return
    insert = (
        m.group(1)
        + f"""
        // {marker}: 准备字体 + 写入当前 MPV 公开桥接（播放中立即生效）
        try {{
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
        }} catch (Throwable ignored) {{}}
"""
    )
    t = t[: m.start()] + insert + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    OK.append("putSubtitleFontIndex -> applyRuntimeToActive")


def patch_video_activity_config_safe() -> None:
    """去掉 clearVideoSurface 误用；仅 requestLayout + 通知 surface size。"""
    marker = "MPV_PHASE_C_CONFIG_CHANGE_V4"
    targets = [
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java",
    ]
    for path in targets:
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        # remove old dangerous blocks
        t = re.sub(
            r"\n\s*// MPV_PHASE_C_CONFIG_CHANGE[\s\S]{0,2000}?catch \(Throwable ignored\) \{\}\n",
            "\n",
            t,
            count=4,
        )
        if marker in t:
            OK.append(f"config V4 already {rel(path)}")
            path.write_text(t, encoding="utf-8")
            continue
        m = re.search(
            r"(void\s+onConfigurationChanged\s*\(\s*(?:@\w+\s+)*Configuration\s+\w+\s*\)\s*\{)",
            t,
        )
        if not m:
            OK.append(f"no onConfigurationChanged: {rel(path)}")
            path.write_text(t, encoding="utf-8")
            continue
        insert = (
            m.group(0)
            + f"""
        // {marker}: 不 clearVideoSurface；只通知尺寸 + layout（Surface 由 MpvPlayer 回调管理）
        try {{
            if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                    == com.fongmi.android.tv.setting.PlayerSetting.MPV) {{
                android.view.View pv = null;
                try {{ pv = getExoView(); }} catch (Throwable ignored) {{}}
                if (pv != null) {{
                    final android.view.View v = pv;
                    v.post(() -> {{
                        try {{
                            v.requestLayout();
                            v.invalidate();
                            int w = Math.max(v.getWidth(), 1);
                            int h = Math.max(v.getHeight(), 1);
                            try {{
                                Class<?> cl = Class.forName("androidx.media3.mpvplayer.MpvPlayer");
                                Object active = cl.getField("sModActivePlayer").get(null);
                                if (active != null) {{
                                    try {{
                                        java.lang.reflect.Method m2 = active.getClass().getMethod("modNotifySurfaceSize", int.class, int.class);
                                        m2.invoke(active, w, h);
                                    }} catch (Throwable ignored) {{}}
                                }}
                            }} catch (Throwable ignored) {{}}
                        }} catch (Throwable ignored) {{}}
                    }});
                }}
            }}
        }} catch (Throwable ignored) {{}}
"""
        )
        t = t[: m.start()] + insert + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        OK.append(f"config V4 safe (no clearVideoSurface): {rel(path)}")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c V4 ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
    else:
        inject_public_bridge(path)
        inject_init_slang_and_style(path)
    # policy lives in mod and is copied by apply.sh before hooks — also write to ROOT if present after copy
    pol_root = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    pol_root.parent.mkdir(parents=True, exist_ok=True)
    pol_root.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("wrote MpvSubtitleStylePolicy to ROOT")
    patch_setting_font_apply()
    patch_video_activity_config_safe()
    print("---- results ----")
    for x in OK:
        print(" OK", x)
    for x in ERR:
        print(" FAIL", x)
    if ERR:
        print("[mod] inject_mpv_phase_c FAILED")
        return 1
    # verify bridge in MpvPlayer
    if path and path.is_file():
        vt = path.read_text(encoding="utf-8")
        if MARKER_BRIDGE not in vt or "modApplySubtitleStyleRuntime" not in vt:
            print("[mod] VERIFY FAIL bridge missing in MpvPlayer")
            return 1
    print("[mod] inject_mpv_phase_c SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
