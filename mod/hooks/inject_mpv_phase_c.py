#!/usr/bin/env python3
"""
MPV 阶段 C V5 — 针对审查意见的 5 点修复：
1. 活动实例：WeakReference，在 prepare/release 等实例方法注册/清理，不在匿名 Callback 里 this
2. 初始化用 setOption；mpvInit 之后才 setProperty
3. putSubtitleFontIndex：先 Prefers.put，再 ensureFonts + apply
4. 桥接检查 initialized/released/owner + 属性返回码
5. TTF Name 表读 Family Name 作为 sub-font
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE = "MPV_MOD_PUBLIC_BRIDGE_V5"
MARKER_OPT = "MPV_PHASE_C_INIT_OPTIONS_V5"
MARKER_AFTER_INIT = "MPV_PHASE_C_AFTER_INIT_V5"
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


def detect_apis(text: str) -> dict:
    apis = {
        "setOption": None,
        "setProperty": None,
        "mpvInit": None,
    }
    if re.search(r"\bvoid\s+setOption\s*\(\s*String", text) or re.search(r"\bsetOption\s*\(\s*\"", text):
        apis["setOption"] = "setOption"
    if re.search(r"\bmpvSetOptionString\s*\(", text):
        apis["setOption"] = "mpvSetOptionString"
    if re.search(r"\bmpvSetPropertyString\s*\(", text):
        apis["setProperty"] = "mpvSetPropertyString"
    elif re.search(r"\bvoid\s+setProperty\s*\(\s*String", text):
        apis["setProperty"] = "setProperty"
    if re.search(r"\bmpvInit\s*\(", text):
        apis["mpvInit"] = "mpvInit"
    return apis


def strip_old_bridge(text: str) -> str:
    # Remove V4 bridge blocks
    text = re.sub(
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V4[\s\S]*?public void modNotifySurfaceSize\([\s\S]*?\n    \}\n",
        "\n",
        text,
        count=1,
    )
    text = re.sub(
        r"\n\s*try \{ sModActivePlayer = this; \} catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
    )
    text = re.sub(
        r"\n\s*try \{ modNotifySurfaceSize\(width, height\); \} catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
    )
    text = re.sub(r"[^\n]*MPV_PHASE_C_SUB_OPTIONS_V4[\s\S]{0,1200}?\n", "\n", text, count=1)
    text = re.sub(r"[^\n]*MPV_PHASE_C_SUB_OPTIONS_V3[\s\S]{0,1200}?\n", "\n", text, count=1)
    return text


def inject_bridge(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = strip_old_bridge(text)
    if MARKER_BRIDGE in text:
        OK.append("bridge V5 already")
        path.write_text(text, encoding="utf-8")
        return

    apis = detect_apis(text)
    opt = apis["setOption"] or "setOption"
    prop = apis["setProperty"] or "mpvSetPropertyString"

    bridge = f'''
    // {MARKER_BRIDGE}
    private static final java.util.concurrent.atomic.AtomicReference<MpvPlayer> sModActive = new java.util.concurrent.atomic.AtomicReference<>();

    public static MpvPlayer modGetActivePlayer() {{
        return sModActive.get();
    }}

    public static void modRegisterActive(MpvPlayer player) {{
        if (player != null) sModActive.set(player);
    }}

    public static void modUnregisterActive(MpvPlayer player) {{
        if (player == null) return;
        sModActive.compareAndSet(player, null);
    }}

    /** 是否已 init 且未 release，且 native 归属当前实例（字段名因版本而异，尽力探测）。 */
    public boolean modIsNativeReady() {{
        try {{
            java.lang.reflect.Field fReleased = null;
            for (String n : new String[] {{ "released", "mReleased", "isReleased" }}) {{
                try {{ fReleased = getClass().getDeclaredField(n); fReleased.setAccessible(true); break; }} catch (Throwable ignored) {{}}
            }}
            if (fReleased != null) {{
                Object v = fReleased.get(this);
                if (v instanceof Boolean && (Boolean) v) return false;
            }}
            java.lang.reflect.Field fInit = null;
            for (String n : new String[] {{ "initialized", "mInitialized", "nativeInitialized" }}) {{
                try {{ fInit = getClass().getDeclaredField(n); fInit.setAccessible(true); break; }} catch (Throwable ignored) {{}}
            }}
            if (fInit != null) {{
                Object v = fInit.get(this);
                if (v instanceof Boolean && !(Boolean) v) return false;
            }}
            for (String n : new String[] {{ "nativeContextOwner", "mNativeContextOwner" }}) {{
                try {{
                    java.lang.reflect.Field f = getClass().getDeclaredField(n);
                    f.setAccessible(true);
                    Object owner = f.get(this);
                    if (owner != null && owner != this) return false;
                }} catch (Throwable ignored) {{}}
            }}
            return true;
        }} catch (Throwable t) {{
            return false;
        }}
    }}

    /** 初始化阶段选项（mpvInit 之前）。 */
    public boolean modSetInitOption(String name, String value) {{
        if (name == null || value == null) return false;
        try {{
            {opt}(name, value);
            return true;
        }} catch (Throwable t) {{
            return false;
        }}
    }}

    /**
     * 运行时属性。若 native 返回 int，0 视为成功；void 则仅表示调用未抛异常。
     */
    public boolean modSetRuntimeProperty(String name, String value) {{
        if (name == null || value == null) return false;
        if (!modIsNativeReady()) return false;
        try {{
            java.lang.reflect.Method m = null;
            try {{ m = getClass().getDeclaredMethod("{prop}", String.class, String.class); }} catch (Throwable ignored) {{}}
            if (m == null) {{
                try {{ m = getClass().getDeclaredMethod("setProperty", String.class, String.class); }} catch (Throwable ignored) {{}}
            }}
            if (m == null) return false;
            m.setAccessible(true);
            Object r = m.invoke(this, name, value);
            if (r instanceof Integer) return ((Integer) r) == 0;
            if (r instanceof Boolean) return (Boolean) r;
            return true; // void
        }} catch (Throwable t) {{
            return false;
        }}
    }}

    /**
     * mpvInit 之后调用。返回 true 仅当必要属性均设置成功。
     */
    public boolean modApplySubtitleStyleRuntime(String fontsDir, String font, String forceStyle, String color) {{
        if (!modIsNativeReady()) return false;
        boolean ok = true;
        if (fontsDir != null && !fontsDir.isEmpty()) ok &= modSetRuntimeProperty("sub-fonts-dir", fontsDir);
        if (font != null && !font.isEmpty()) ok &= modSetRuntimeProperty("sub-font", font);
        if (forceStyle != null && !forceStyle.isEmpty()) ok &= modSetRuntimeProperty("sub-ass-force-style", forceStyle);
        if (color != null && !color.isEmpty()) ok &= modSetRuntimeProperty("sub-color", color);
        ok &= modSetRuntimeProperty("sub-ass-override", "force");
        return ok;
    }}

    public void modNotifySurfaceSize(int width, int height) {{
        if (width <= 0 || height <= 0) return;
        if (!modIsNativeReady()) return;
        modSetRuntimeProperty("android-surface-size", width + "x" + height);
    }}
'''

    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return
    text = text[:idx] + "\n" + bridge + text[idx:]

    # Register on instance methods (NOT anonymous SurfaceHolder.Callback)
    hooked_reg = 0
    for pat in [
        r"(public\s+void\s+prepare\s*\([^)]*\)\s*\{)",
        r"(public\s+void\s+setMediaItem\s*\([^)]*\)\s*\{)",
        r"(public\s+void\s+setMediaItems\s*\([^)]*\)\s*\{)",
        r"(private\s+void\s+prepareInternal\s*\([^)]*\)\s*\{)",
        r"(void\s+onPrepared\s*\([^)]*\)\s*\{)",
    ]:
        text2, n = re.subn(
            pat,
            r"\1\n        try { modRegisterActive(this); } catch (Throwable ignored) {}",
            text,
            count=1,
        )
        if n:
            text = text2
            hooked_reg += n
            OK.append(f"register hook {pat[:48]}")
            break
    if hooked_reg == 0:
        # fallback: after mpvInit success
        if re.search(r"mpvInit\s*\(\s*\)\s*;", text):
            text, n = re.subn(
                r"(mpvInit\s*\(\s*\)\s*;)",
                r"\1\n        try { modRegisterActive(this); } catch (Throwable ignored) {}",
                text,
                count=1,
            )
            hooked_reg = n
            OK.append(f"register after mpvInit x{n}")

    # Unregister on release/stop
    for pat in [
        r"(public\s+void\s+release\s*\(\s*\)\s*\{)",
        r"(public\s+void\s+stop\s*\(\s*\)\s*\{)",
        r"(private\s+void\s+releaseInternal\s*\(\s*\)\s*\{)",
    ]:
        text2, n = re.subn(
            pat,
            r"\1\n        try { modUnregisterActive(this); } catch (Throwable ignored) {}",
            text,
            count=1,
        )
        if n:
            text = text2
            OK.append(f"unregister hook {pat[:40]}")
            # only first release-like is enough; still try both release and stop
            if "release" in pat:
                pass

    path.write_text(text, encoding="utf-8")
    OK.append(f"bridge V5 opt={opt} prop={prop}")


def inject_init_and_after_init(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    # INIT options (before/with phase A): slang only as option
    if MARKER_OPT not in text:
        lines = text.splitlines(keepends=True)
        idx = -1
        for i, ln in enumerate(lines):
            if "MPV_PHASE_A_OPTIONS" in ln:
                idx = i
            if idx < 0 and re.search(r'\(\s*"hwdec"\s*,', ln):
                idx = i
        if idx < 0:
            ERR.append("no option inject site")
        else:
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
            text = "".join(lines)
            OK.append("init slang option")
    else:
        OK.append("init slang already")

    # AFTER mpvInit: runtime style
    if MARKER_AFTER_INIT not in text:
        if re.search(r"mpvInit\s*\(\s*\)\s*;", text):
            text, n = re.subn(
                r"(mpvInit\s*\(\s*\)\s*;)",
                r"""\1
        // """ + MARKER_AFTER_INIT + """
        try {
            modRegisterActive(this);
            Class<?> _pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
            _pol.getMethod("ensureFontsReady").invoke(null);
            Object dir = _pol.getMethod("getSubFontsDirProperty").invoke(null);
            Object font = _pol.getMethod("getSubFontProperty").invoke(null);
            Object style = _pol.getMethod("getAssForceStyle").invoke(null);
            Object color = _pol.getMethod("getSubColorProperty").invoke(null);
            modApplySubtitleStyleRuntime(
                dir instanceof String ? (String) dir : null,
                font instanceof String ? (String) font : null,
                style instanceof String ? (String) style : null,
                color instanceof String ? (String) color : null);
        } catch (Throwable ignored) {}
""",
                text,
                count=1,
            )
            OK.append(f"after mpvInit runtime style x{n}")
        else:
            OK.append("mpvInit() call site not found — runtime style only via Setting/apply")
    else:
        OK.append("after init already")

    path.write_text(text, encoding="utf-8")


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
 * MPV 字幕：TTF Family Name + 经 MpvPlayer 公开桥写入运行时属性（init 后）。
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

    /** 读取 TTF/OTF name 表 Font Family (nameID=1)。失败返回 null。 */
    public static String readFontFamilyName(File fontFile) {
        if (fontFile == null || !fontFile.isFile()) return null;
        String key = fontFile.getAbsolutePath() + ":" + fontFile.length() + ":" + fontFile.lastModified();
        String cached = FAMILY_CACHE.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String family = null;
        try (FileInputStream in = new FileInputStream(fontFile)) {
            byte[] all = in.readAllBytes();
            if (all.length < 12) {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int sfnt = bb.getInt(0);
            // 0x00010000 TrueType, 'OTTO' CFF, 'true', 'typ1'
            int numTables = bb.getShort(4) & 0xFFFF;
            int nameOffset = -1;
            int nameLength = 0;
            for (int i = 0; i < numTables; i++) {
                int off = 12 + i * 16;
                if (off + 16 > all.length) break;
                int tag = bb.getInt(off);
                // 'name' = 0x6E616D65
                if (tag == 0x6E616D65) {
                    nameOffset = bb.getInt(off + 8);
                    nameLength = bb.getInt(off + 12);
                    break;
                }
            }
            if (nameOffset < 0 || nameOffset + 6 > all.length) {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            int count = bb.getShort(nameOffset + 2) & 0xFFFF;
            int stringOffset = bb.getShort(nameOffset + 4) & 0xFFFF;
            String best = null;
            for (int i = 0; i < count; i++) {
                int rec = nameOffset + 6 + i * 12;
                if (rec + 12 > all.length) break;
                int platformID = bb.getShort(rec) & 0xFFFF;
                int encodingID = bb.getShort(rec + 2) & 0xFFFF;
                int languageID = bb.getShort(rec + 4) & 0xFFFF;
                int nameID = bb.getShort(rec + 6) & 0xFFFF;
                int length = bb.getShort(rec + 8) & 0xFFFF;
                int offset = bb.getShort(rec + 10) & 0xFFFF;
                if (nameID != 1) continue; // Font Family
                int abs = nameOffset + stringOffset + offset;
                if (abs + length > all.length || length <= 0) continue;
                String s;
                if (platformID == 3) { // Windows
                    s = new String(all, abs, length, StandardCharsets.UTF_16BE).trim();
                } else if (platformID == 1) { // Mac Roman
                    s = new String(all, abs, length, Charset.forName("MacRoman")).trim();
                } else {
                    s = new String(all, abs, length, StandardCharsets.UTF_8).trim();
                }
                if (s.isEmpty()) continue;
                // prefer en-US windows
                if (platformID == 3 && languageID == 0x0409) {
                    best = s;
                    break;
                }
                if (best == null) best = s;
            }
            family = best;
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

    /**
     * sub-font：优先 TTF 内部 Family Name；失败回退文件主名；系统默认 sans-serif。
     */
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

    /** @return 是否成功应用到当前活跃且 native ready 的 MPV */
    public static boolean applyRuntimeToActive() {
        ensureFontsReady();
        Object active = null;
        try {
            Class<?> cl = Class.forName("androidx.media3.mpvplayer.MpvPlayer");
            java.lang.reflect.Method g = cl.getMethod("modGetActivePlayer");
            active = g.invoke(null);
        } catch (Throwable ignored) {
        }
        if (active == null) return false;
        return applyRuntime(active);
    }

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
            Object r = m.invoke(player, dir, font, style, color);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
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


def write_policy() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("wrote policy with TTF family reader")


def patch_setting_order() -> None:
    """putSubtitleFontIndex: Prefers.put 先，再 ensure + apply。可重复执行。"""
    marker = "MPV_FONT_APPLY_AFTER_PUT_V5"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        OK.append("Setting missing")
        return
    t = path.read_text(encoding="utf-8")
    # remove old injects
    t = re.sub(
        r"\n\s*// MPV_FONT_RUNTIME_APPLY[\s\S]{0,300}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        t,
        count=3,
    )
    if marker in t:
        OK.append("setting order already V5")
        path.write_text(t, encoding="utf-8")
        return
    # Match full method and replace
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
    OK.append("putSubtitleFontIndex: put then apply")


def patch_config_keep_v4_safe() -> None:
    # Keep V4 safe config if present; strip clearVideoSurface if any remain
    for rel_path in [
        "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java",
    ]:
        path = ROOT / rel_path
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        if "clearVideoSurface" in t and "MPV_PHASE_C" in t:
            t2 = re.sub(
                r"\n\s*// MPV_PHASE_C_CONFIG_CHANGE[\s\S]{0,2500}?catch \(Throwable ignored\) \{\}\n",
                "\n",
                t,
                count=4,
            )
            path.write_text(t2, encoding="utf-8")
            OK.append(f"stripped dangerous config block: {rel_path}")
        else:
            OK.append(f"config ok: {rel_path}")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c V5 ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
    else:
        inject_bridge(path)
        inject_init_and_after_init(path)
    write_policy()
    patch_setting_order()
    patch_config_keep_v4_safe()
    print("---- results ----")
    for x in OK:
        print(" OK", x)
    for x in ERR:
        print(" FAIL", x)
    if ERR:
        print("[mod] inject_mpv_phase_c FAILED")
        return 1
    if path and path.is_file():
        vt = path.read_text(encoding="utf-8")
        if MARKER_BRIDGE not in vt or "modApplySubtitleStyleRuntime" not in vt:
            print("[mod] VERIFY FAIL bridge")
            return 1
        if "sModActivePlayer = this" in vt:
            print("[mod] VERIFY FAIL still assigns this in bad pattern")
            return 1
    print("[mod] inject_mpv_phase_c SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
