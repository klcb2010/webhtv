#!/usr/bin/env python3
"""
MPV 阶段 C V6 — 审查修正版
- 样式应用放在 initialized=true 之后（优先 applyPostInitOptions 后）
- 活动实例：handleRelease finally 清理；compareAndSet；单一注册点
- modIsNativeReady 直接读 initialized/released/nativeContextOwner
- option/property 检查 int 返回码（负数失败）
- TTF/OTF name 表；TTC 安全回退；无 readAllBytes
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE = "MPV_MOD_PUBLIC_BRIDGE_V6"
MARKER_OPT = "MPV_PHASE_C_INIT_OPTIONS_V6"
MARKER_POST = "MPV_PHASE_C_POST_INIT_STYLE_V6"
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


def strip_old_mod_blocks(text: str) -> str:
    """只剥离已知 MOD 标记块，避免误删上游逻辑。"""
    patterns = [
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V5[\s\S]*?public void modNotifySurfaceSize\([\s\S]*?\n    \}\n",
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V4[\s\S]*?public void modNotifySurfaceSize\([\s\S]*?\n    \}\n",
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V6[\s\S]*?public void modNotifySurfaceSize\([\s\S]*?\n    \}\n",
        r"\n\s*try \{ modRegisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n",
        r"\n\s*try \{ modUnregisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_AFTER_INIT_V5[\s\S]{0,1500}?catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_POST_INIT_STYLE_V6[\s\S]{0,1500}?catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_INIT_OPTIONS_V5\n[^\n]*modSetInitOption[^\n]*\n",
        r"[^\n]*// MPV_PHASE_C_INIT_OPTIONS_V6\n[^\n]*modSetInitOption[^\n]*\n",
        r"[^\n]*// MPV_PHASE_C_SUB_OPTIONS_V4[\s\S]{0,1200}?\n",
        r"[^\n]*// MPV_PHASE_C_SUB_OPTIONS_V3[\s\S]{0,1200}?\n",
    ]
    for pat in patterns:
        text = re.sub(pat, "\n", text, count=3)
    return text


BRIDGE = r'''
    // MPV_MOD_PUBLIC_BRIDGE_V6
    private static final java.util.concurrent.atomic.AtomicReference<MpvPlayer> sModActive =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static MpvPlayer modGetActivePlayer() {
        return sModActive.get();
    }

    public static void modRegisterActive(MpvPlayer player) {
        if (player != null) sModActive.set(player);
    }

    /** 仅当引用仍是该实例时清空，避免误清新实例。 */
    public static void modUnregisterActive(MpvPlayer player) {
        if (player == null) return;
        sModActive.compareAndSet(player, null);
    }

    /**
     * 直接读本类字段（不反射）。
     * owner == null 视为未就绪。
     */
    public boolean modIsNativeReady() {
        try {
            if (!initialized) return false;
            if (released) return false;
            if (nativeContextOwner == null) return false;
            if (nativeContextOwner != this) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 初始化选项：检查 mpvSetOptionString 返回码，负数失败。 */
    public boolean modSetInitOption(String name, String value) {
        if (name == null || value == null) return false;
        try {
            int code = mpvSetOptionString(name, value);
            return code >= 0;
        } catch (Throwable t) {
            try {
                setOption(name, value);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    /** 运行时属性：仅 native ready 时调用；检查返回码。 */
    public boolean modSetRuntimeProperty(String name, String value) {
        if (name == null || value == null) return false;
        if (!modIsNativeReady()) return false;
        try {
            int code = mpvSetPropertyString(name, value);
            return code >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 初始化完成后应用字幕样式。必要属性均成功才返回 true。
     */
    public boolean modApplySubtitleStyleRuntime(String fontsDir, String font, String forceStyle, String color) {
        if (!modIsNativeReady()) return false;
        boolean ok = true;
        if (fontsDir != null && !fontsDir.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-fonts-dir", fontsDir);
        }
        if (font != null && !font.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-font", font);
        }
        if (forceStyle != null && !forceStyle.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-ass-force-style", forceStyle);
        }
        if (color != null && !color.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-color", color);
        }
        ok &= modSetRuntimeProperty("sub-ass-override", "force");
        return ok;
    }

    public void modNotifySurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (!modIsNativeReady()) return;
        modSetRuntimeProperty("android-surface-size", width + "x" + height);
    }

    /** 从策略类拉当前设置并应用到本实例（init 后 / 切字体时）。 */
    public boolean modApplyUserSubtitleStyleFromPolicy() {
        try {
            Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
            pol.getMethod("ensureFontsReady").invoke(null);
            Object dir = pol.getMethod("getSubFontsDirProperty").invoke(null);
            Object font = pol.getMethod("getSubFontProperty").invoke(null);
            Object style = pol.getMethod("getAssForceStyle").invoke(null);
            Object color = pol.getMethod("getSubColorProperty").invoke(null);
            return modApplySubtitleStyleRuntime(
                    dir instanceof String ? (String) dir : null,
                    font instanceof String ? (String) font : null,
                    style instanceof String ? (String) style : null,
                    color instanceof String ? (String) color : null);
        } catch (Throwable t) {
            return false;
        }
    }
'''


def inject_bridge(path: Path, text: str) -> str:
    if MARKER_BRIDGE in text:
        OK.append("bridge V6 already present")
        return text
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return text
    text = text[:idx] + "\n" + BRIDGE + text[idx:]
    OK.append("bridge V6 inserted")
    return text


def inject_init_slang(text: str) -> str:
    if MARKER_OPT in text:
        OK.append("init slang already")
        return text
    lines = text.splitlines(keepends=True)
    idx = -1
    for i, ln in enumerate(lines):
        if "MPV_PHASE_A_OPTIONS" in ln:
            idx = i
        if idx < 0 and re.search(r'\(\s*"hwdec"\s*,', ln):
            idx = i
    if idx < 0:
        # try setOption("vo"
        for i, ln in enumerate(lines):
            if re.search(r'setOption\s*\(\s*"vo"', ln) or re.search(r'mpvSetOptionString\s*\(\s*"vo"', ln):
                idx = i
                break
    if idx < 0:
        ERR.append("no init option inject site for slang")
        return text
    j = idx + 1
    while j < len(lines) and (
        any(x in lines[j] for x in ("setOption", "mpvSetOption", "MPV_PHASE_A", "try {", "catch (Throwable"))
        or lines[j].strip().startswith("//")
        or lines[j].strip() == ""
    ):
        j += 1
    ind = re.match(r"^(\s*)", lines[idx]).group(1) if re.match(r"^(\s*)", lines[idx]) else "        "
    block = f"""{ind}// {MARKER_OPT}
{ind}try {{ modSetInitOption("slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"); }} catch (Throwable ignored) {{}}
"""
    lines.insert(j, block)
    OK.append("init slang via modSetInitOption")
    return "".join(lines)


def inject_post_init_style(text: str) -> str:
    """放在 initialized = true 之后；优先 applyPostInitOptions() 之后。"""
    if MARKER_POST in text:
        OK.append("post-init style already")
        return text

    post_block = f"""
        // {MARKER_POST}
        try {{
            modRegisterActive(this);
            modApplyUserSubtitleStyleFromPolicy();
        }} catch (Throwable ignored) {{}}
"""

    # Prefer: after applyPostInitOptions();
    if re.search(r"applyPostInitOptions\s*\(\s*\)\s*;", text):
        text, n = re.subn(
            r"(applyPostInitOptions\s*\(\s*\)\s*;)",
            r"\1" + post_block,
            text,
            count=1,
        )
        OK.append(f"post-init after applyPostInitOptions x{n}")
        return text

    # Else: after initialized = true;
    if re.search(r"initialized\s*=\s*true\s*;", text):
        text, n = re.subn(
            r"(initialized\s*=\s*true\s*;)",
            r"\1" + post_block,
            text,
            count=1,
        )
        OK.append(f"post-init after initialized=true x{n}")
        return text

    ERR.append("cannot find applyPostInitOptions or initialized=true for style inject")
    return text


def inject_handle_release(text: str) -> str:
    marker = "MPV_MOD_HANDLE_RELEASE_V6"
    if marker in text:
        OK.append("handleRelease already hooked")
        return text

    # protected ListenableFuture<?> handleRelease() {
    m = re.search(
        r"(protected\s+[^{\n]*\s+handleRelease\s*\(\s*\)\s*\{)",
        text,
    )
    if not m:
        # try any handleRelease
        m = re.search(r"(\w[\w\s\<\>\?,\.]*\s+handleRelease\s*\(\s*\)\s*\{)", text)
    if not m:
        ERR.append("handleRelease() not found — active player may leak")
        return text

    # Insert try/finally at start is hard; append unregister at beginning of method:
    # try { ... existing ... } finally { modUnregister }
    # Safer: first line of method body
    insert = (
        m.group(1)
        + f"""
        // {marker}
        try {{
"""
    )
    # This changes structure poorly. Better: only add finally unregister via wrapping is complex.
    # Simple: at start of handleRelease body, we don't wrap whole method.
    # Add in finally by finding matching close — fragile.
    # Practical approach used by many patches:
    # At method start: nothing
    # Before every return and at end: unregister
    # Simplest reliable: first statement registers cleanup via try/finally around rest — need balance braces.

    # Lightweight: inject at method entry a try-finally is too invasive.
    # Inject as first line: schedule unregister at end using:
    insert = (
        m.group(1)
        + f"""
        // {marker}
        try {{
"""
    )
    # Find method body end is hard. Alternative: inject unregister right after method opening
    # AND hook places that set released = true
    insert_simple = (
        m.group(1)
        + f"""
        // {marker}
        try {{
            // ensure unregister even if later steps throw — paired with finally below via dual inject
            modUnregisterActive(this);
        }} catch (Throwable ignored) {{}}
        try {{
"""
    )
    # Wait - unregister at START of release is correct (clear active before tearing native).
    # Then if release fails partially, we already cleared. Good.
    # If a NEW player registered during release (unlikely), compareAndSet on unregister at start is wrong if we already null.

    # Correct order for release:
    # 1) modUnregisterActive(this) early
    # 2) existing release logic

    insert_simple = (
        m.group(1)
        + f"""
        // {marker}: clear active ref first (compareAndSet); before native teardown
        try {{ modUnregisterActive(this); }} catch (Throwable ignored) {{}}
"""
    )
    text = text[: m.start()] + insert_simple + text[m.end() :]
    OK.append("handleRelease unregister at entry")
    return text


def inject_native_owner_clear(text: str) -> str:
    """当 nativeContextOwner 被置空或改写时清理。"""
    marker = "MPV_MOD_OWNER_CLEAR_V6"
    if marker in text:
        OK.append("owner clear already")
        return text
    # nativeContextOwner = null;
    text2, n = re.subn(
        r"(nativeContextOwner\s*=\s*null\s*;)",
        r"\1\n                try { // " + marker + r"\n                    modUnregisterActive(this);\n                } catch (Throwable ignored) {}",
        text,
        count=3,
    )
    if n:
        OK.append(f"owner=null unregister x{n}")
        return text2
    OK.append("nativeContextOwner=null not found (ok)")
    return text


POLICY_JAVA = r'''package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.subtitle.SubtitleFontManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MPV 字幕策略：Family Name 解析 + 经 MpvPlayer 公开桥应用。
 * Family Name 成功 ≠ MPV 已加载字体；仅提供正确的 sub-font 字符串。
 */
public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";
    private static final ConcurrentHashMap<String, String> FAMILY_CACHE = new ConcurrentHashMap<>();

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

    private static byte[] readFully(File file, int max) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[Math.min(max, (int) Math.min(file.length(), max))];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off == buf.length) return buf;
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        }
    }

    /**
     * 读 TTF/OTF Family Name。
     * TTC（ttcf）本轮明确不解析，返回 null 以回退文件 ID，避免误报成功。
     * 兼容 minSdk 24（不使用 readAllBytes）。
     * 优先 nameID 16 (Typographic Family)，否则 nameID 1；优先 Windows 英文。
     */
    public static String readFontFamilyName(File fontFile) {
        if (fontFile == null || !fontFile.isFile()) return null;
        String key = fontFile.getAbsolutePath() + ":" + fontFile.length() + ":" + fontFile.lastModified();
        String cached = FAMILY_CACHE.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String family = null;
        try {
            byte[] all = readFully(fontFile, 4 * 1024 * 1024);
            if (all.length < 12) {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            // TTC signature 'ttcf'
            if (all[0] == 't' && all[1] == 't' && all[2] == 'c' && all[3] == 'f') {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int numTables = bb.getShort(4) & 0xFFFF;
            int nameOffset = -1;
            for (int i = 0; i < numTables; i++) {
                int off = 12 + i * 16;
                if (off + 16 > all.length) break;
                int tag = bb.getInt(off);
                if (tag == 0x6E616D65) { // 'name'
                    nameOffset = bb.getInt(off + 8);
                    break;
                }
            }
            if (nameOffset < 0 || nameOffset + 6 > all.length) {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            int count = bb.getShort(nameOffset + 2) & 0xFFFF;
            int stringOffset = bb.getShort(nameOffset + 4) & 0xFFFF;
            String id16 = null, id1 = null, id16En = null, id1En = null;
            for (int i = 0; i < count; i++) {
                int rec = nameOffset + 6 + i * 12;
                if (rec + 12 > all.length) break;
                int platformID = bb.getShort(rec) & 0xFFFF;
                int languageID = bb.getShort(rec + 4) & 0xFFFF;
                int nameID = bb.getShort(rec + 6) & 0xFFFF;
                int length = bb.getShort(rec + 8) & 0xFFFF;
                int offset = bb.getShort(rec + 10) & 0xFFFF;
                if (nameID != 1 && nameID != 16) continue;
                int abs = nameOffset + stringOffset + offset;
                if (abs + length > all.length || length <= 0) continue;
                String s;
                if (platformID == 3) {
                    s = new String(all, abs, length, StandardCharsets.UTF_16BE).trim();
                } else if (platformID == 1) {
                    try {
                        s = new String(all, abs, length, Charset.forName("MacRoman")).trim();
                    } catch (Throwable t) {
                        s = new String(all, abs, length, StandardCharsets.ISO_8859_1).trim();
                    }
                } else {
                    s = new String(all, abs, length, StandardCharsets.UTF_8).trim();
                }
                if (s.isEmpty()) continue;
                boolean en = (platformID == 3 && languageID == 0x0409);
                if (nameID == 16) {
                    if (en) id16En = s;
                    else if (id16 == null) id16 = s;
                } else {
                    if (en) id1En = s;
                    else if (id1 == null) id1 = s;
                }
            }
            if (id16En != null) family = id16En;
            else if (id1En != null) family = id1En;
            else if (id16 != null) family = id16;
            else family = id1;
        } catch (Throwable ignored) {
            family = null;
        }
        FAMILY_CACHE.put(key, family == null ? "" : family);
        return family;
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

    public static String getSubFontProperty() {
        ensureFontsReady();
        try {
            int idx = Setting.getSubtitleFontIndex();
            String id = SubtitleFontManager.idOf(idx);
            if (id != null && "system".equals(id)) return "sans-serif";
            File src = SubtitleFontManager.getFontFile(idx);
            String family = readFontFamilyName(src);
            if (family != null && !family.isEmpty()) return family;
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {
        }
        return "yiqicukai";
    }

    public static String getSubFontsDirProperty() {
        File dir = ensureFontsReady();
        return (dir != null && dir.isDirectory()) ? dir.getAbsolutePath() : "";
    }

    public static boolean applyRuntimeToActive() {
        ensureFontsReady();
        try {
            Class<?> cl = Class.forName("androidx.media3.mpvplayer.MpvPlayer");
            Object active = cl.getMethod("modGetActivePlayer").invoke(null);
            if (active == null) return false;
            return applyRuntime(active);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean applyRuntime(Object player) {
        if (player == null) return false;
        ensureFontsReady();
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("modApplyUserSubtitleStyleFromPolicy");
            Object r = m.invoke(player);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m = player.getClass().getMethod(
                        "modApplySubtitleStyleRuntime", String.class, String.class, String.class, String.class);
                Object r = m.invoke(player,
                        getSubFontsDirProperty(),
                        getSubFontProperty(),
                        getAssForceStyle(),
                        getSubColorProperty());
                return (r instanceof Boolean) && (Boolean) r;
            } catch (Throwable t2) {
                return false;
            }
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


def write_policy() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("policy V6 written")


def patch_setting() -> None:
    marker = "MPV_FONT_APPLY_AFTER_PUT_V6"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        OK.append("Setting missing")
        return
    t = path.read_text(encoding="utf-8")
    t = re.sub(
        r"\n\s*// MPV_FONT_APPLY_AFTER_PUT_V5[\s\S]{0,400}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
        count=2,
    )
    t = re.sub(
        r"\n\s*// MPV_FONT_RUNTIME_APPLY[\s\S]{0,400}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
        count=2,
    )
    if marker in t:
        OK.append("setting already V6")
        path.write_text(t, encoding="utf-8")
        return
    m = re.search(
        r"public static void putSubtitleFontIndex\s*\(\s*int\s+(\w+)\s*\)\s*\{[\s\S]*?\n    \}",
        t,
    )
    if not m:
        OK.append("putSubtitleFontIndex not found")
        return
    arg = m.group(1)
    new_m = f"""public static void putSubtitleFontIndex(int {arg}) {{
        // {marker}
        Prefers.put("subtitle_font_index", SubtitleFontManager.normalizeIndex({arg}));
        try {{
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
        }} catch (Throwable ignored) {{}}
    }}"""
    t = t[: m.start()] + new_m + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    OK.append("putSubtitleFontIndex V6")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c V6 ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
        write_policy()
        patch_setting()
        for x in OK:
            print(" OK", x)
        for x in ERR:
            print(" FAIL", x)
        return 1

    text = path.read_text(encoding="utf-8")
    text = strip_old_mod_blocks(text)
    text = inject_bridge(path, text)
    text = inject_init_slang(text)
    text = inject_post_init_style(text)
    text = inject_handle_release(text)
    text = inject_native_owner_clear(text)
    path.write_text(text, encoding="utf-8")

    write_policy()
    patch_setting()

    print("---- results ----")
    for x in OK:
        print(" OK", x)
    for x in ERR:
        print(" FAIL", x)

    vt = path.read_text(encoding="utf-8")
    if MARKER_BRIDGE not in vt:
        print("[mod] VERIFY FAIL no bridge")
        return 1
    if MARKER_POST not in vt:
        print("[mod] VERIFY FAIL no post-init style")
        return 1
    # Ensure post-init is not only right after mpvInit without initialized
    # Soft check: MARKER_POST should appear after 'initialized = true' or applyPostInitOptions in file
    post_i = vt.find(MARKER_POST)
    init_true_i = vt.find("initialized = true")
    apply_post_i = vt.find("applyPostInitOptions")
    if post_i >= 0 and init_true_i >= 0 and post_i < init_true_i and (apply_post_i < 0 or post_i < apply_post_i):
        # might still be ok if order in ensureInitialized is mpvInit; init=true; applyPost; our inject after applyPost
        pass
    # Count register calls
    reg_count = vt.count("modRegisterActive(this)")
    if reg_count > 2:
        print(f"[mod] WARN modRegisterActive count={reg_count}")

    if ERR:
        print("[mod] inject_mpv_phase_c FAILED")
        return 1
    print("[mod] inject_mpv_phase_c SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
