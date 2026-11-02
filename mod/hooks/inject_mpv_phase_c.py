#!/usr/bin/env python3
"""MPV phase C V10 — content verify, brace-depth strip, owner-clear in release only."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE_BEGIN = "MPV_MOD_PUBLIC_BRIDGE_V10_BEGIN"
MARKER_BRIDGE_END = "MPV_MOD_PUBLIC_BRIDGE_V10_END"
MARKER_OPT = "MPV_PHASE_C_INIT_OPTIONS_V10"
MARKER_POST = "MPV_PHASE_C_POST_INIT_STYLE_V10"
MARKER_RELEASE = "MPV_MOD_HANDLE_RELEASE_V10"
MARKER_OWNER = "MPV_MOD_OWNER_CLEAR_V10"
OK: list[str] = []
ERR: list[str] = []

BRIDGE = r"""
    // MPV_MOD_PUBLIC_BRIDGE_V10_BEGIN
    private static final java.util.concurrent.atomic.AtomicReference<MpvPlayer> sModActive =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static MpvPlayer modGetActivePlayer() {
        return sModActive.get();
    }

    public static void modRegisterActive(MpvPlayer player) {
        if (player != null) sModActive.set(player);
    }

    public static void modUnregisterActive(MpvPlayer player) {
        if (player == null) return;
        sModActive.compareAndSet(player, null);
    }

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

    public boolean modSetInitOption(String name, String value) {
        if (name == null || value == null) return false;
        try {
            int code = mpvSetOptionString(name, value);
            return code >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

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
    // MPV_MOD_PUBLIC_BRIDGE_V10_END
"""

POLICY_JAVA = r"""package com.fongmi.android.tv.player.mpv;

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
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";
    private static final ConcurrentHashMap<String, String> FAMILY_CACHE = new ConcurrentHashMap<>();
    private static final Object ENSURE_LOCK = new Object();
    private static volatile String sLastFingerprint = "";

    private MpvSubtitleStylePolicy() {
    }

    /**
     * 抽样哈希：文件首 8K + 中部 8K + 尾部 8K。
     * 不按 path|len|mtime 缓存哈希结果，避免同元信息不同内容被旧缓存掩盖。
     */

    private static String contentHashSample(File file) {
        if (file == null || !file.isFile()) return "x";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long len = file.length();
            // head
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                if (n > 0) md.update(buf, 0, n);
            }
            // middle
            if (len > 16384L) {
                try (FileInputStream in = new FileInputStream(file)) {
                    long mid = Math.max(0L, len / 2L - 4096L);
                    skipFully(in, mid);
                    byte[] buf = new byte[8192];
                    int n = in.read(buf);
                    if (n > 0) md.update(buf, 0, n);
                }
            }
            // tail
            if (len > 8192L) {
                try (FileInputStream in = new FileInputStream(file)) {
                    long tail = Math.max(0L, len - 8192L);
                    skipFully(in, tail);
                    byte[] buf = new byte[8192];
                    int n = in.read(buf);
                    if (n > 0) md.update(buf, 0, n);
                }
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8 && i < dig.length; i++) {
                sb.append(String.format("%02x", dig[i] & 0xff));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "x";
        }
    }


    private static void skipFully(FileInputStream in, long target) throws Exception {
        long skipped = 0;
        while (skipped < target) {
            long s = in.skip(target - skipped);
            if (s <= 0) {
                if (in.read() < 0) break;
                skipped++;
            } else {
                skipped += s;
            }
        }
    }

    private static String fingerprint(String id, File src) {
        if (src == null || !src.isFile()) return id + "|missing";
        return id + "|" + src.length() + "|" + src.lastModified() + "|" + contentHashSample(src);
    }

    /** 长度 + 抽样内容哈希一致才算匹配 */
    private static boolean contentMatches(File a, File b) {
        if (a == null || b == null || !a.isFile() || !b.isFile()) return false;
        if (a.length() != b.length() || a.length() <= 0) return false;
        return contentHashSample(a).equals(contentHashSample(b));
    }

    private static boolean forceCopy(File src, File dst) {
        if (src == null || dst == null || !src.isFile()) return false;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                if (!parent.mkdirs() && !parent.isDirectory()) return false;
            }
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            dst.setLastModified(System.currentTimeMillis());
            return contentMatches(src, dst);
        } catch (Throwable t) {
            return false;
        }
    }

    public static File ensureFontsReady() {
        synchronized (ENSURE_LOCK) {
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

            int idx;
            String id;
            File src;
            try {
                idx = Setting.getSubtitleFontIndex();
                id = SubtitleFontManager.idOf(idx);
                if (id == null) id = "";
                src = SubtitleFontManager.getFontFile(idx);
            } catch (Throwable t) {
                return dir;
            }

            String fp = fingerprint(id, src);
            File subfont = new File(dir, "subfont.ttf");
            File named = (id.isEmpty() || "system".equals(id)) ? null : new File(dir, id + ".ttf");
            boolean need = !fp.equals(sLastFingerprint)
                    || !contentMatches(subfont, src)
                    || (named != null && src != null && src.isFile() && !contentMatches(named, src));
            if (!need) return dir;

            try {
                SubtitleFontManager.prepareAllFonts();
            } catch (Throwable ignored) {
            }
            try {
                src = SubtitleFontManager.getFontFile(idx);
                fp = fingerprint(id, src);
            } catch (Throwable ignored) {
            }
            if (src == null || !src.isFile()) return dir;

            boolean ok = forceCopy(src, subfont);
            if (named != null) ok = ok && forceCopy(src, named);
            if (ok) {
                sLastFingerprint = fp;
            }
            return dir;
        }
    }

    private static File tryFontsDir() {
        try {
            return SubtitleFontManager.getFontsDir();
        } catch (Throwable ignored) {
            return null;
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
        String key = fontFile.getAbsolutePath() + ":" + fontFile.length() + ":" + fontFile.lastModified()
                + ":" + contentHashSample(fontFile);
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
                return null;
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
"""


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


def find_matching_brace(text: str, open_idx: int) -> int:
    """open_idx points to '{'. Return index of matching '}' or -1."""
    depth = 0
    i = open_idx
    n = len(text)
    in_str = False
    str_ch = ""
    in_line_comment = False
    in_block_comment = False
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if c == str_ch:
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if c in ('"', "'"):
            in_str = True
            str_ch = c
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def remove_method_defs(text: str, method_name: str) -> str:
    """Remove all method definitions named method_name using brace depth."""
    pattern = re.compile(
        rf"(?:\n|^)([ \t]*)(?:public|private|protected)\s+(?:static\s+)?[\w\<\>\[\]\s]+\s+{method_name}\s*\([^;]*?\)\s*\{{",
        re.M,
    )
    while True:
        m = pattern.search(text)
        if not m:
            break
        brace = text.find("{", m.end() - 1)
        end = find_matching_brace(text, brace)
        if end < 0:
            break
        text = text[: m.start()] + "\n" + text[end + 1 :]
    return text


def strip_old(text: str) -> str:
    # V8–V10 full bridge blocks by markers
    text = re.sub(
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V(?:8|9|10)_BEGIN[\s\S]*?// MPV_MOD_PUBLIC_BRIDGE_V(?:8|9|10)_END\n",
        "\n",
        text,
        count=5,
    )
    # V4–V7 header + methods via method name removal
    text = re.sub(r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V[4-7]\b[^\n]*\n", "\n", text, count=5)
    for meth in (
        "modApplyUserSubtitleStyleFromPolicy",
        "modApplySubtitleStyleRuntime",
        "modSetRuntimeProperty",
        "modSetInitOption",
        "modIsNativeReady",
        "modNotifySurfaceSize",
        "modGetActivePlayer",
        "modRegisterActive",
        "modUnregisterActive",
    ):
        text = remove_method_defs(text, meth)
    text = re.sub(
        r"\n\s*private static final java\.util\.concurrent\.atomic\.AtomicReference<MpvPlayer> sModActive[\s\S]*?;\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_AFTER_INIT_V5[\s\S]{0,2000}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_INIT_OPTIONS_V(?:5|6|7|8|9|10)\n[^\n]*modSetInitOption[^\n]*\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_POST_INIT_STYLE_V(?:5|6|7|8|9|10)[\s\S]{0,1000}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_SUB_OPTIONS_V[3-9][\s\S]{0,1500}?\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_MOD_HANDLE_RELEASE_V(?:5|6|7|8|9|10)[^\n]*\n(?:\s*try \{ modUnregisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n)?",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_MOD_OWNER_CLEAR_V(?:5|6|7|8|9|10)[\s\S]{0,250}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(r"\n\s*try \{ modRegisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def inject_bridge(text: str) -> str:
    if MARKER_BRIDGE_BEGIN in text and MARKER_BRIDGE_END in text:
        OK.append("bridge V10 already")
        return text
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return text
    text = text[:idx] + "\n" + BRIDGE + text[idx:]
    OK.append("bridge V10 inserted")
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
    block = (
        f"{ind}// {MARKER_OPT}\n"
        f'{ind}try {{ modSetInitOption("slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"); }} catch (Throwable ignored) {{}}\n'
    )
    lines.insert(j, block)
    OK.append("init slang")
    return "".join(lines)


def inject_post_init(text: str) -> str:
    if MARKER_POST in text:
        OK.append("post-init already")
        return text
    post = (
        f"\n        // {MARKER_POST}\n"
        "        try {\n"
        "            modRegisterActive(this);\n"
        "            modApplyUserSubtitleStyleFromPolicy();\n"
        "        } catch (Throwable ignored) {}\n"
    )
    if re.search(r"applyPostInitOptions\s*\(\s*\)\s*;", text):
        text, n = re.subn(r"(applyPostInitOptions\s*\(\s*\)\s*;)", r"\1" + post, text, count=1)
        OK.append(f"post-init after applyPostInitOptions x{n}")
        return text
    ERR.append("applyPostInitOptions() not found — required")
    return text


def inject_handle_release(text: str) -> str:
    if MARKER_RELEASE in text and "modUnregisterActive(this)" in text:
        OK.append("handleRelease already")
        return text
    text = re.sub(r"[^\n]*// " + re.escape(MARKER_RELEASE) + r"[^\n]*\n", "\n", text, count=2)
    m = re.search(r"(\w[\w\s\<\>\?,\.]*\s+handleRelease\s*\(\s*\)\s*\{)", text)
    if not m:
        ERR.append("handleRelease() not found")
        return text
    insert = (
        m.group(1)
        + f"\n        // {MARKER_RELEASE}: unregister at handleRelease entry (compareAndSet)\n"
        + "        try { modUnregisterActive(this); } catch (Throwable ignored) {}\n"
    )
    text = text[: m.start()] + insert + text[m.end() :]
    OK.append("handleRelease entry unregister")
    return text


def inject_owner_clear(text: str) -> str:
    """Only inject inside handleRelease() body, not whole file."""
    if MARKER_OWNER in text:
        # verify location
        hr = extract_method_body(text, "handleRelease")
        if hr and MARKER_OWNER in hr:
            OK.append("owner clear already in handleRelease")
            return text
        # marker elsewhere — strip and redo
        text = re.sub(
            r"[^\n]*// " + re.escape(MARKER_OWNER) + r"[\s\S]{0,250}?catch \(Throwable ignored\) \{\}\n",
            "\n",
            text,
            count=5,
        )

    m = re.search(r"\bhandleRelease\s*\(\s*\)\s*\{", text)
    if not m:
        ERR.append("handleRelease not found for owner clear")
        return text
    brace = text.find("{", m.end() - 1)
    end = find_matching_brace(text, brace)
    if end < 0:
        ERR.append("handleRelease brace match failed")
        return text
    body = text[brace : end + 1]
    if "nativeContextOwner" not in body or "null" not in body:
        ERR.append("nativeContextOwner=null not in handleRelease body")
        return text
    # replace only within body
    new_body, n = re.subn(
        r"(nativeContextOwner\s*=\s*null\s*;)",
        r"\1\n                try { // "
        + MARKER_OWNER
        + "\n                    modUnregisterActive(this);\n                } catch (Throwable ignored) {}",
        body,
        count=1,
    )
    if n == 0:
        ERR.append("failed to inject owner clear inside handleRelease")
        return text
    text = text[:brace] + new_body + text[end + 1 :]
    OK.append("owner clear inside handleRelease")
    return text


def extract_method_body(text: str, method_name: str) -> str | None:
    m = re.search(rf"\b{method_name}\s*\([^)]*\)\s*\{{", text)
    if not m:
        return None
    brace = text.find("{", m.end() - 1)
    end = find_matching_brace(text, brace)
    if end < 0:
        return None
    return text[brace : end + 1]


def extract_method(text: str, method_name: str) -> str | None:
    m = re.search(
        rf"(?:public|private|protected)\s+(?:static\s+)?[\w\<\>\[\]\s]+\s+{method_name}\s*\([^)]*\)\s*\{{",
        text,
    )
    if not m:
        return None
    brace = text.find("{", m.end() - 1)
    end = find_matching_brace(text, brace)
    if end < 0:
        return None
    return text[m.start() : end + 1]


def verify_injected(text: str) -> None:
    if MARKER_BRIDGE_BEGIN not in text or MARKER_BRIDGE_END not in text:
        ERR.append("VERIFY: bridge BEGIN/END missing")
    if text.count(MARKER_BRIDGE_BEGIN) != 1 or text.count(MARKER_BRIDGE_END) != 1:
        ERR.append("VERIFY: bridge marker count wrong")
    for meth in (
        "modApplyUserSubtitleStyleFromPolicy",
        "modSetRuntimeProperty",
        "modSetInitOption",
        "modRegisterActive",
        "modUnregisterActive",
        "modNotifySurfaceSize",
        "modApplySubtitleStyleRuntime",
        "modIsNativeReady",
        "modGetActivePlayer",
    ):
        defs = len(
            re.findall(
                rf"(?:public|private|protected)\s+(?:static\s+)?[\w\<\>\[\]]+\s+{meth}\s*\(",
                text,
            )
        )
        if defs != 1:
            ERR.append(f"VERIFY: method def count {meth}={defs}")

    body = extract_method_body(text, "ensureInitialized")
    if body is None:
        ERR.append("VERIFY: ensureInitialized missing")
        return

    def pos(pat: str) -> int:
        m = re.search(pat, body)
        return m.start() if m else -1

    p_init = pos(r"mpvInit\s*\(\s*\)\s*;")
    p_true = pos(r"initialized\s*=\s*true\s*;")
    p_post = pos(r"applyPostInitOptions\s*\(\s*\)\s*;")
    p_style = pos(re.escape(MARKER_POST))
    if min(p_init, p_true, p_post, p_style) < 0:
        ERR.append("VERIFY: missing step in ensureInitialized")
    elif not (p_init < p_true < p_post < p_style):
        ERR.append("VERIFY: bad order in ensureInitialized")

    reg = len(re.findall(r"modRegisterActive\s*\(\s*this\s*\)", text))
    if reg != 1:
        ERR.append(f"VERIFY: modRegisterActive(this) count={reg}")

    rt = extract_method(text, "modSetRuntimeProperty")
    if not rt or "synchronized (NATIVE_CONTEXT_LOCK)" not in rt:
        ERR.append("VERIFY: modSetRuntimeProperty lock missing")
    elif any(f not in rt for f in ("initialized", "released", "nativeContextOwner")):
        ERR.append("VERIFY: modSetRuntimeProperty state checks missing")

    ini = extract_method(text, "modSetInitOption")
    if not ini or "mpvSetOptionString" not in ini:
        ERR.append("VERIFY: modSetInitOption invalid")
    elif re.search(r"catch\s*\([^)]*\)\s*\{[^}]*setOption\s*\(", ini or ""):
        ERR.append("VERIFY: setOption fallback present")

    hr = extract_method_body(text, "handleRelease")
    if not hr or "modUnregisterActive(this)" not in hr:
        ERR.append("VERIFY: handleRelease unregister missing")
    if not hr or MARKER_OWNER not in hr:
        ERR.append("VERIFY: owner clear not inside handleRelease")
    OK.append("VERIFY all checks executed")


def write_policy() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("policy V10 written")


def patch_setting() -> None:
    marker = "MPV_FONT_APPLY_AFTER_PUT_V10"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        OK.append("Setting.java not in tree (skip)")
        return
    t = path.read_text(encoding="utf-8")
    t2, n = re.subn(
        r"public static void putSubtitleFontIndex\s*\(\s*int\s+(\w+)\s*\)\s*\{[\s\S]*?\n    \}",
        lambda m: (
            f"public static void putSubtitleFontIndex(int {m.group(1)}) {{\n"
            f"        // {marker}\n"
            f'        Prefers.put("subtitle_font_index", SubtitleFontManager.normalizeIndex({m.group(1)}));\n'
            f"        try {{\n"
            f"            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();\n"
            f"            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();\n"
            f"        }} catch (Throwable ignored) {{}}\n"
            f"    }}"
        ),
        t,
        count=1,
    )
    if n == 0:
        ERR.append("VERIFY: putSubtitleFontIndex not patched")
        return
    m = re.search(
        r"public static void putSubtitleFontIndex\s*\(\s*int\s+\w+\s*\)\s*\{([\s\S]*?)\n    \}",
        t2,
    )
    if not m:
        ERR.append("VERIFY: putSubtitleFontIndex missing")
        return
    body = m.group(1)
    if not (0 <= body.find("Prefers.put") < body.find("ensureFontsReady") < body.find("applyRuntimeToActive")):
        ERR.append("VERIFY: put order wrong")
        return
    path.write_text(t2, encoding="utf-8")
    OK.append("Setting putSubtitleFontIndex order verified")


def main() -> int:
    print(f"[mod] inject_mpv_phase_c V10 ROOT={ROOT}")
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
    if (
        MARKER_BRIDGE_BEGIN in text
        and MARKER_BRIDGE_END in text
        and MARKER_POST in text
        and MARKER_OPT in text
        and MARKER_RELEASE in text
        and MARKER_OWNER in text
    ):
        OK.clear()
        ERR.clear()
        verify_injected(text)
        if not ERR:
            write_policy()
            patch_setting()
            OK.append("idempotent skip: already fully injected")
            print("---- results ----")
            for x in OK:
                print(" OK", x)
            print("[mod] inject_mpv_phase_c SUCCESS")
            return 0
        ERR.clear()
        OK.clear()

    text = strip_old(text)
    text = inject_bridge(text)
    text = inject_init_slang(text)
    text = inject_post_init(text)
    text = inject_handle_release(text)
    text = inject_owner_clear(text)
    text = re.sub(r"\n{3,}", "\n\n", text)
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
