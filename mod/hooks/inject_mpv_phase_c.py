#!/usr/bin/env python3
"""
MPV 阶段 C V7
- 运行时属性在 NATIVE_CONTEXT_LOCK 内二次检查状态后写入
- modSetInitOption 仅用 mpvSetOptionString + 返回码，无假成功 fallback
- handleRelease 入口 unregister（compareAndSet），注释与行为一致
- 注入后强校验 ensureInitialized 内顺序，失败 exit 1
- ensureFontsReady 短时去重，避免一次切字体多次 copy
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE = "MPV_MOD_PUBLIC_BRIDGE_V7"
MARKER_OPT = "MPV_PHASE_C_INIT_OPTIONS_V7"
MARKER_POST = "MPV_PHASE_C_POST_INIT_STYLE_V7"
MARKER_RELEASE = "MPV_MOD_HANDLE_RELEASE_V7"
MARKER_OWNER = "MPV_MOD_OWNER_CLEAR_V7"
OK: list[str] = []
ERR: list[str] = []


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


def strip_old(text: str) -> str:
    patterns = [
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V[4567][\s\S]*?public void modNotifySurfaceSize\([\s\S]*?\n    \}\n",
        # also strip method modApplyUserSubtitleStyleFromPolicy that ends before next method if orphaned
        r"\n\s*try \{ modRegisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n",
        r"\n\s*try \{ modUnregisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_POST_INIT_STYLE_V[67][\s\S]{0,800}?catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_INIT_OPTIONS_V[67]\n[^\n]*modSetInitOption[^\n]*\n",
        r"[^\n]*// MPV_MOD_HANDLE_RELEASE_V[67][^\n]*\n\s*try \{ modUnregisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_MOD_OWNER_CLEAR_V[67][\s\S]{0,200}?catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_AFTER_INIT_V5[\s\S]{0,1500}?catch \(Throwable ignored\) \{\}\n",
        r"[^\n]*// MPV_PHASE_C_SUB_OPTIONS_V[34][\s\S]{0,1200}?\n",
    ]
    for pat in patterns:
        text = re.sub(pat, "\n", text, count=5)
    # strip orphaned modApplyUserSubtitleStyleFromPolicy if left without bridge marker
    text = re.sub(
        r"\n\s*/\*\* 从策略类拉当前设置[\s\S]*?public boolean modApplyUserSubtitleStyleFromPolicy\(\) \{[\s\S]*?\n    \}\n",
        "\n",
        text,
        count=2,
    )
    return text


BRIDGE = r'''
    // MPV_MOD_PUBLIC_BRIDGE_V7
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
     * 直接读本类字段。
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

    /**
     * 初始化选项：仅 mpvSetOptionString，检查返回码。
     * 负数失败；非负成功。无 setOption 假成功回退。
     */
    public boolean modSetInitOption(String name, String value) {
        if (name == null || value == null) return false;
        try {
            int code = mpvSetOptionString(name, value);
            return code >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 运行时属性：在 NATIVE_CONTEXT_LOCK 内二次确认状态后再写，
     * 缩小与 releaseNativeContext / takeover 的竞态窗口。
     * 不主动切线程，调用方须满足现有 MPV 线程约束。
     */
    public boolean modSetRuntimeProperty(String name, String value) {
        if (name == null || value == null) return false;
        try {
            synchronized (NATIVE_CONTEXT_LOCK) {
                if (!initialized) return false;
                if (released) return false;
                if (nativeContextOwner == null) return false;
                if (nativeContextOwner != this) return false;
                int code = mpvSetPropertyString(name, value);
                return code >= 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean modApplySubtitleStyleRuntime(String fontsDir, String font, String forceStyle, String color) {
        // 入口先快速失败，真正写入仍在锁内二次检查
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
        modSetRuntimeProperty("android-surface-size", width + "x" + height);
    }

    public boolean modApplyUserSubtitleStyleFromPolicy() {
        try {
            Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
            // 单次应用只 prepare 一次（策略类内部有短时去重）
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


def inject_bridge(text: str) -> str:
    if MARKER_BRIDGE in text:
        OK.append("bridge V7 already")
        return text
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return text
    text = text[:idx] + "\n" + BRIDGE + text[idx:]
    OK.append("bridge V7 inserted")
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
        for i, ln in enumerate(lines):
            if re.search(r'(?:setOption|mpvSetOptionString)\s*\(\s*"vo"', ln):
                idx = i
                break
    if idx < 0:
        ERR.append("no init option site for slang")
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
    OK.append("init slang")
    return "".join(lines)


def inject_post_init(text: str) -> str:
    if MARKER_POST in text:
        OK.append("post-init already")
        return text
    post = f"""
        // {MARKER_POST}
        try {{
            modRegisterActive(this);
            modApplyUserSubtitleStyleFromPolicy();
        }} catch (Throwable ignored) {{}}
"""
    if re.search(r"applyPostInitOptions\s*\(\s*\)\s*;", text):
        text, n = re.subn(r"(applyPostInitOptions\s*\(\s*\)\s*;)", r"\1" + post, text, count=1)
        OK.append(f"post-init after applyPostInitOptions x{n}")
        return text
    if re.search(r"initialized\s*=\s*true\s*;", text):
        text, n = re.subn(r"(initialized\s*=\s*true\s*;)", r"\1" + post, text, count=1)
        OK.append(f"post-init after initialized=true x{n}")
        return text
    ERR.append("no post-init site")
    return text


def inject_handle_release(text: str) -> str:
    if MARKER_RELEASE in text:
        OK.append("handleRelease already")
        return text
    m = re.search(r"(\w[\w\s\<\>\?,\.]*\s+handleRelease\s*\(\s*\)\s*\{)", text)
    if not m:
        ERR.append("handleRelease() not found")
        return text
    # 入口清理：释放流程一开始就丢掉 active 引用，避免异常中途仍指向本实例。
    # compareAndSet 保证不会清掉随后注册的新实例。非 finally，因入口清理已覆盖“异常后仍残留”场景。
    insert = (
        m.group(1)
        + f"""
        // {MARKER_RELEASE}: unregister at handleRelease entry (compareAndSet); not finally — entry is enough to drop stale active ref before native teardown
        try {{ modUnregisterActive(this); }} catch (Throwable ignored) {{}}
"""
    )
    text = text[: m.start()] + insert + text[m.end() :]
    OK.append("handleRelease entry unregister")
    return text


def inject_owner_clear(text: str) -> str:
    if MARKER_OWNER in text:
        OK.append("owner clear already")
        return text
    text2, n = re.subn(
        r"(nativeContextOwner\s*=\s*null\s*;)",
        r"\1\n                try { // "
        + MARKER_OWNER
        + r"""
                    modUnregisterActive(this);
                } catch (Throwable ignored) {}""",
        text,
        count=3,
    )
    if n:
        OK.append(f"owner=null unregister x{n}")
        return text2
    OK.append("nativeContextOwner=null not found (warn)")
    return text


def extract_method_body(text: str, method_name: str) -> str | None:
    """Rough extract of method body by brace balance from first signature match."""
    m = re.search(rf"\b{method_name}\s*\([^)]*\)\s*\{{", text)
    if not m:
        return None
    start = m.end() - 1  # '{'
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start : i + 1]
    return None


def verify_injected(text: str) -> None:
    body = extract_method_body(text, "ensureInitialized")
    if body is None:
        # some codebases name differently
        for alt in ("ensureInitialized", "initializeNative", "initNative"):
            body = extract_method_body(text, alt)
            if body:
                break
    if body is None:
        ERR.append("VERIFY: ensureInitialized() method not found")
        return

    def pos(pat: str) -> int:
        m = re.search(pat, body)
        return m.start() if m else -1

    p_init = pos(r"mpvInit\s*\(\s*\)\s*;")
    p_true = pos(r"initialized\s*=\s*true\s*;")
    p_post = pos(r"applyPostInitOptions\s*\(\s*\)\s*;")
    p_style = pos(re.escape(MARKER_POST))

    if p_init < 0:
        ERR.append("VERIFY: mpvInit(); not in ensureInitialized")
    if p_true < 0:
        ERR.append("VERIFY: initialized=true not in ensureInitialized")
    if p_style < 0:
        ERR.append("VERIFY: POST_INIT_STYLE marker not in ensureInitialized")

    if p_init >= 0 and p_true >= 0 and p_init > p_true:
        ERR.append("VERIFY: mpvInit after initialized=true (wrong order)")
    if p_true >= 0 and p_style >= 0 and p_style < p_true:
        ERR.append("VERIFY: style applied BEFORE initialized=true")
    if p_post >= 0 and p_style >= 0 and p_style < p_post:
        ERR.append("VERIFY: style applied BEFORE applyPostInitOptions")
    if p_init >= 0 and p_style >= 0 and p_style < p_init:
        ERR.append("VERIFY: style applied BEFORE mpvInit")

    # registration count (should be 1 in post-init ideally)
    reg = len(re.findall(r"modRegisterActive\s*\(\s*this\s*\)", text))
    if reg == 0:
        ERR.append("VERIFY: no modRegisterActive(this)")
    elif reg > 2:
        ERR.append(f"VERIFY: too many modRegisterActive(this) count={reg}")

    if MARKER_RELEASE not in text and "modUnregisterActive(this)" not in text:
        ERR.append("VERIFY: no handleRelease unregister")
    if "handleRelease" not in text:
        ERR.append("VERIFY: handleRelease missing in file")

    # init option no fake fallback
    if "modSetInitOption" in text:
        m = re.search(r"boolean modSetInitOption\([\s\S]*?\n    \}", text)
        if m and "setOption(" in m.group(0) and "mpvSetOptionString" in m.group(0):
            # allow only mpvSetOptionString — if setOption appears as fallback, fail
            if re.search(r"catch\s*\([^)]*\)\s*\{[^}]*setOption\s*\(", m.group(0)):
                ERR.append("VERIFY: modSetInitOption still has setOption fallback")

    if "synchronized (NATIVE_CONTEXT_LOCK)" not in text:
        ERR.append("VERIFY: NATIVE_CONTEXT_LOCK not used in bridge")

    OK.append("VERIFY ensureInitialized order + hooks checked")


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

public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";
    private static final ConcurrentHashMap<String, String> FAMILY_CACHE = new ConcurrentHashMap<>();
    /** 同一次切字体/应用内短时去重，避免 UI 线程反复 copy */
    private static volatile long sLastEnsureMs = 0L;
    private static final long ENSURE_DEDUP_MS = 400L;

    private MpvSubtitleStylePolicy() {
    }

    public static File ensureFontsReady() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastEnsureMs < ENSURE_DEDUP_MS) {
            File dir = tryFontsDir();
            if (dir != null) return dir;
        }
        sLastEnsureMs = now;
        try {
            SubtitleFontManager.prepareAllFonts();
        } catch (Throwable ignored) {
        }
        File dir = tryFontsDir();
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

    private static File tryFontsDir() {
        try {
            return SubtitleFontManager.getFontsDir();
        } catch (Throwable ignored) {
            return null;
        }
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
            int size = (int) Math.min(file.length(), max);
            byte[] buf = new byte[size];
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
            if (all[0] == 't' && all[1] == 't' && all[2] == 'c' && all[3] == 'f') {
                FAMILY_CACHE.put(key, "");
                return null; // TTC: 不解析，回退文件 ID
            }
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int numTables = bb.getShort(4) & 0xFFFF;
            int nameOffset = -1;
            for (int i = 0; i < numTables; i++) {
                int off = 12 + i * 16;
                if (off + 16 > all.length) break;
                if (bb.getInt(off) == 0x6E616D65) {
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
        // getter 不强制 prepare（由 ensureFontsReady/apply 入口负责）
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
        File dir = tryFontsDir();
        if (dir == null) dir = ensureFontsReady();
        return (dir != null && dir.isDirectory()) ? dir.getAbsolutePath() : "";
    }

    public static boolean applyRuntimeToActive() {
        ensureFontsReady();
        try {
            Class<?> cl = Class.forName("androidx.media3.mpvplayer.MpvPlayer");
            Object active = cl.getMethod("modGetActivePlayer").invoke(null);
            if (active == null) return false;
            // 避免二次 ensure：直接调实例方法
            java.lang.reflect.Method m = active.getClass().getMethod("modApplyUserSubtitleStyleFromPolicy");
            Object r = m.invoke(active);
            return (r instanceof Boolean) && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean applyRuntime(Object player) {
        if (player == null) return false;
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("modApplyUserSubtitleStyleFromPolicy");
            Object r = m.invoke(player);
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
    OK.append("policy V7 written")


def patch_setting() -> None:
    marker = "MPV_FONT_APPLY_AFTER_PUT_V7"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        # CI 有文件；合成测试可能无
        OK.append("Setting.java not in tree (skip)")
        return
    t = path.read_text(encoding="utf-8")
    t = re.sub(
        r"public static void putSubtitleFontIndex\s*\(\s*int\s+(\w+)\s*\)\s*\{[\s\S]*?\n    \}",
        lambda m: f"""public static void putSubtitleFontIndex(int {m.group(1)}) {{
        // {marker}
        Prefers.put("subtitle_font_index", SubtitleFontManager.normalizeIndex({m.group(1)}));
        try {{
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
        }} catch (Throwable ignored) {{}}
    }}""",
        t,
        count=1,
    )
    if marker not in t:
        ERR.append("VERIFY: putSubtitleFontIndex patch failed")
        return
    # verify order in method
    m = re.search(
        r"public static void putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{([\s\S]*?)\n    \}",
        t,
    )
    if not m:
        ERR.append("VERIFY: putSubtitleFontIndex missing after patch")
        return
    body = m.group(1)
    i_put = body.find("Prefers.put")
    i_ens = body.find("ensureFontsReady")
    i_app = body.find("applyRuntimeToActive")
    if not (0 <= i_put < i_ens < i_app):
        ERR.append("VERIFY: putSubtitleFontIndex order not put→ensure→apply")
        return
    path.write_text(t, encoding="utf-8")
    OK.append("Setting putSubtitleFontIndex order verified")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c V7 ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERR.append("MpvPlayer.java not found")
        write_policy()
        patch_setting()
        for x in OK:
            print(" OK", x)
        for x in ERR:
            print(" FAIL", x)
        print("[mod] inject_mpv_phase_c FAILED")
        return 1

    text = path.read_text(encoding="utf-8")
    text = strip_old(text)
    text = inject_bridge(text)
    text = inject_init_slang(text)
    text = inject_post_init(text)
    text = inject_handle_release(text)
    text = inject_owner_clear(text)
    path.write_text(text, encoding="utf-8")

    write_policy()
    patch_setting()

    vt = path.read_text(encoding="utf-8")
    verify_injected(vt)

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
