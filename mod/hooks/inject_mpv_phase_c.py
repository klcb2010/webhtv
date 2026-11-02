#!/usr/bin/env python3
"""MPV phase C V13 — source-aware injection, brace-depth strip, owner-clear in release only."""
# Upstream lifecycle reference: https://github.com/webhtv/webhtv
# Font pipeline reference: https://github.com/100pangci/mpvKt
# Always inject against the fork's real androidx.media3.mpvplayer.MpvPlayer source.
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER_BRIDGE_BEGIN = "MPV_MOD_PUBLIC_BRIDGE_V13_BEGIN"
MARKER_BRIDGE_END = "MPV_MOD_PUBLIC_BRIDGE_V13_END"
MARKER_OPT = "MPV_PHASE_C_INIT_OPTIONS_V12"
MARKER_POST = "MPV_PHASE_C_POST_INIT_STYLE_V12"
MARKER_RELEASE = "MPV_MOD_HANDLE_RELEASE_V12"
MARKER_OWNER = "MPV_MOD_OWNER_CLEAR_V12"
OK: list[str] = []
ERR: list[str] = []

BRIDGE = r"""
    // MPV_MOD_PUBLIC_BRIDGE_V13_BEGIN
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
            ok &= modSetRuntimeProperty("sub-ass-style-overrides", forceStyle);
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
    // MPV_MOD_PUBLIC_BRIDGE_V13_END
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

    private static String fontExtension(File file) {
        if (file == null) return ".ttf";
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return ".ttf";
        String ext = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return (".ttf".equals(ext) || ".otf".equals(ext) || ".ttc".equals(ext)) ? ext : ".ttf";
    }

    private static void removeOtherFontVariants(File dir, String base, File keep) {
        for (String ext : new String[]{".ttf", ".otf", ".ttc"}) {
            File candidate = new File(dir, base + ext);
            if (!candidate.equals(keep) && candidate.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                candidate.delete();
            }
        }
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
            String ext = fontExtension(src);
            File subfont = new File(dir, "subfont" + ext);
            File named = (id.isEmpty() || "system".equals(id)) ? null : new File(dir, id + ext);
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
                removeOtherFontVariants(dir, "subfont", subfont);
                if (named != null) removeOtherFontVariants(dir, id, named);
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
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.BIG_ENDIAN);
            int sfntOffset = 0;
            if (all[0] == 't' && all[1] == 't' && all[2] == 'c' && all[3] == 'f') {
                // TTC header: use the first face offset. Table offsets inside TTC
                // records are absolute offsets from the beginning of the collection.
                if (all.length < 16) {
                    FAMILY_CACHE.put(key, "");
                    return null;
                }
                long faceOffset = bb.getInt(12) & 0xFFFFFFFFL;
                if (faceOffset > Integer.MAX_VALUE || faceOffset + 12 > all.length) {
                    FAMILY_CACHE.put(key, "");
                    return null;
                }
                sfntOffset = (int) faceOffset;
            }
            int numTables = bb.getShort(sfntOffset + 4) & 0xFFFF;
            int nameOffset = -1;
            for (int i = 0; i < numTables; i++) {
                int off = sfntOffset + 12 + i * 16;
                if (off + 16 > all.length) break;
                if (bb.getInt(off) == 0x6E616D65) {
                    long tableOffset = bb.getInt(off + 8) & 0xFFFFFFFFL;
                    if (tableOffset <= Integer.MAX_VALUE) nameOffset = (int) tableOffset;
                    break;
                }
            }
            if (nameOffset < 0 || nameOffset + 6 > all.length) {
                FAMILY_CACHE.put(key, "");
                return null;
            }
            int count = bb.getShort(nameOffset + 2) & 0xFFFF;
            int stringOffset = bb.getShort(nameOffset + 4) & 0xFFFF;
            String id16 = null, id1 = null;
            int id16Rank = Integer.MAX_VALUE;
            int id1Rank = Integer.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                int rec = nameOffset + 6 + i * 12;
                if (rec < 0 || rec + 12L > all.length) break;
                int platformID = bb.getShort(rec) & 0xFFFF;
                int encodingID = bb.getShort(rec + 2) & 0xFFFF;
                int languageID = bb.getShort(rec + 4) & 0xFFFF;
                int nameID = bb.getShort(rec + 6) & 0xFFFF;
                int length = bb.getShort(rec + 8) & 0xFFFF;
                int offset = bb.getShort(rec + 10) & 0xFFFF;
                if (nameID != 1 && nameID != 16) continue;
                long absLong = (long) nameOffset + stringOffset + offset;
                if (absLong < 0 || absLong + length > all.length || length <= 0) continue;
                int abs = (int) absLong;
                String value;
                if (platformID == 0 || platformID == 3) {
                    int validLength = length & ~1;
                    if (validLength <= 0) continue;
                    value = new String(all, abs, validLength, StandardCharsets.UTF_16BE).trim();
                } else if (platformID == 1) {
                    try {
                        value = new String(all, abs, length, Charset.forName("MacRoman")).trim();
                    } catch (Throwable ignored) {
                        continue;
                    }
                } else {
                    continue;
                }
                if (value.isEmpty() || value.indexOf(0) >= 0) continue; continue;
                int rank;
                if (platformID == 3 && languageID == 0x0409) rank = 0;
                else if (platformID == 0 && languageID == 0) rank = 1;
                else if (platformID == 3 && languageID == 0x0804) rank = 2;
                else if (platformID == 3) rank = 3;
                else if (platformID == 0) rank = 4;
                else rank = 5;
                if (nameID == 16 && rank < id16Rank) {
                    id16 = value;
                    id16Rank = rank;
                } else if (nameID == 1 && rank < id1Rank) {
                    id1 = value;
                    id1Rank = rank;
                }
            }
            if (id16 != null) family = id16;
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



def remove_marked_try_blocks(text: str, marker_substr: str) -> str:
    """Remove // marker ... try { ... } catch (...) {} blocks using brace depth."""
    while True:
        idx = text.find(marker_substr)
        if idx < 0:
            break
        # find start of line for marker
        line_start = text.rfind("\n", 0, idx) + 1
        # find try after marker
        try_idx = text.find("try", idx)
        if try_idx < 0 or try_idx - idx > 200:
            # remove just the marker line
            line_end = text.find("\n", idx)
            if line_end < 0:
                text = text[:line_start]
                break
            text = text[:line_start] + text[line_end + 1 :]
            continue
        brace = text.find("{", try_idx)
        if brace < 0:
            break
        end_try = find_matching_brace(text, brace)
        if end_try < 0:
            break
        # optional catch
        rest = text[end_try + 1 : end_try + 80]
        m_catch = re.match(r"\s*catch\s*\([^)]*\)\s*\{", rest)
        end = end_try
        if m_catch:
            cbrace = text.find("{", end_try + 1)
            end_catch = find_matching_brace(text, cbrace)
            if end_catch >= 0:
                end = end_catch
        text = text[:line_start] + text[end + 1 :]
    return text


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
    # V8–V13 full bridge blocks by markers
    text = re.sub(
        r"\n\s*// MPV_MOD_PUBLIC_BRIDGE_V(?:8|9|10|11|12|13)_BEGIN[\s\S]*?// MPV_MOD_PUBLIC_BRIDGE_V(?:8|9|10|11|12|13)_END\n",
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
    text = remove_marked_try_blocks(text, "MPV_PHASE_C_AFTER_INIT_V5")
    text = remove_marked_try_blocks(text, "MPV_PHASE_C_AFTER_INIT_V6")
    # also post-init style blocks by marker + try
    for mk in (
        "MPV_PHASE_C_POST_INIT_STYLE_V5",
        "MPV_PHASE_C_POST_INIT_STYLE_V6",
        "MPV_PHASE_C_POST_INIT_STYLE_V7",
        "MPV_PHASE_C_POST_INIT_STYLE_V8",
        "MPV_PHASE_C_POST_INIT_STYLE_V9",
        "MPV_PHASE_C_POST_INIT_STYLE_V10",
        "MPV_PHASE_C_POST_INIT_STYLE_V12",
    ):
        text = remove_marked_try_blocks(text, mk)

    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_INIT_OPTIONS_V(?:5|6|7|8|9|10|11|12|13)\n[^\n]*modSetInitOption[^\n]*\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_PHASE_C_POST_INIT_STYLE_V(?:5|6|7|8|9|10|11|12|13)[\s\S]{0,1000}?catch \(Throwable ignored\) \{\}\n",
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
        r"[^\n]*// MPV_MOD_HANDLE_RELEASE_V(?:5|6|7|8|9|10|11|12|13)[^\n]*\n(?:\s*try \{ modUnregisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n)?",
        "\n",
        text,
        count=5,
    )
    text = re.sub(
        r"[^\n]*// MPV_MOD_OWNER_CLEAR_V(?:5|6|7|8|9|10|11)[\s\S]{0,250}?catch \(Throwable ignored\) \{\}\n",
        "\n",
        text,
        count=5,
    )
    text = re.sub(r"\n\s*try \{ modRegisterActive\(this\); \} catch \(Throwable ignored\) \{\}\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text


def inject_bridge(text: str) -> str:
    if MARKER_BRIDGE_BEGIN in text and MARKER_BRIDGE_END in text:
        OK.append("bridge V11 already")
        return text
    idx = text.rfind("\n}")
    if idx < 0:
        ERR.append("class end not found")
        return text
    text = text[:idx] + "\n" + BRIDGE + text[idx:]
    OK.append("bridge V12 inserted")
    return text


def inject_init_slang(text: str) -> str:
    """Inject slang in the real ensureInitialized() body before mpvInit()."""
    slang = "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"
    body = extract_method_body(text, "ensureInitialized")
    if body and MARKER_OPT in body and "MPV_PHASE_C_FONT_OPTIONS_V14" in body and 'com.fongmi.android.tv.player.track.LangUtil.getPreferredTextLanguages()' in body:
        if body.find(MARKER_OPT) < body.find("mpvInit") and body.find("MPV_PHASE_C_FONT_OPTIONS_V14") < body.find("mpvInit"):
            OK.append("init slang and selected font options already in ensureInitialized")
            return text

    # Remove the previous generated font-options block using balanced braces.
    font_marker = text.find("// MPV_PHASE_C_FONT_OPTIONS_V14")
    if font_marker >= 0:
        line_start = text.rfind("\n", 0, font_marker) + 1
        try_start = text.find("try {", font_marker)
        open_brace = text.find("{", try_start)
        close_brace = find_matching_brace(text, open_brace) if try_start >= 0 and open_brace >= 0 else -1
        if close_brace < 0:
            ERR.append("unable to safely locate previous V14 font-options block")
            return text
        catch_end = re.match(r"\s*catch\s*\(Throwable ignored\)\s*\{\s*\}", text[close_brace + 1:])
        if not catch_end:
            ERR.append("unable to safely remove previous V14 font-options catch")
            return text
        end = close_brace + 1 + catch_end.end()
        if end < len(text) and text[end] == "\n":
            end += 1
        text = text[:line_start] + text[end:]

    # Remove previous phase-C slang lines only; do not guess from unrelated options.
    text = re.sub(
        r'^[ \t]*// MPV_PHASE_C_INIT_OPTIONS_V(?:5|6|7|8|9|10|11|12|13)\r?\n'
        r'^[ \t]*try \{ modSetInitOption\("slang"[^ \r\n]*.*\r?\n',
        "",
        text,
        flags=re.M,
    )
    method = find_method(text, "ensureInitialized")
    if method is None:
        ERR.append("ensureInitialized declaration not found (throws clauses supported)")
        return text
    _, brace, end = method
    body = text[brace + 1:end]
    init = re.search(r'\bmpvInit\s*\(\s*\)\s*;', body)
    if not init:
        ERR.append("mpvInit(); not found inside ensureInitialized")
        return text
    line_start = body.rfind("\n", 0, init.start()) + 1
    indent = re.match(r"[ \t]*", body[line_start:]).group(0)
    block = (
        f"{indent}// {MARKER_OPT}\n"
        f'{indent}try {{ modSetInitOption("slang", android.text.TextUtils.join(",", com.fongmi.android.tv.player.track.LangUtil.getPreferredTextLanguages())); }} catch (Throwable ignored) {{}}\n'
        f"{indent}// MPV_PHASE_C_FONT_OPTIONS_V14: configure custom fonts before MPV initializes\n"
        f"{indent}try {{\n"
        f'{indent}    java.lang.Class<?> policy = java.lang.Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");\n'
        f'{indent}    String fontsDir = (String) policy.getMethod("getSubFontsDirProperty").invoke(null);\n'
        f'{indent}    String fontFamily = (String) policy.getMethod("getSubFontProperty").invoke(null);\n'
        f'{indent}    if (fontsDir != null && !fontsDir.isEmpty()) modSetInitOption("sub-fonts-dir", fontsDir);\n'
        f'{indent}    if (fontFamily != null && !fontFamily.isEmpty()) modSetInitOption("sub-font", fontFamily);\n'
        f'{indent}}} catch (Throwable ignored) {{}}\n'
    )
    text = text[:brace + 1] + body[:init.start()] + block + body[init.start():] + text[end:]
    OK.append("init slang before mpvInit in ensureInitialized")
    return text


def inject_post_init(text: str) -> str:
    """Place style application immediately after applyPostInitOptions in ensureInitialized."""
    body = extract_method_body(text, "ensureInitialized")
    if body and MARKER_POST in body:
        OK.append("post-init already inside ensureInitialized")
        return text
    method = find_method(text, "ensureInitialized")
    if method is None:
        ERR.append("ensureInitialized declaration not found for post-init")
        return text
    _, brace, end = method
    body = text[brace + 1:end]
    call = re.search(r'\bapplyPostInitOptions\s*\(\s*\)\s*;', body)
    if not call:
        ERR.append("applyPostInitOptions() not found inside ensureInitialized")
        return text
    line_start = body.rfind("\n", 0, call.start()) + 1
    indent = re.match(r"[ \t]*", body[line_start:]).group(0)
    post = (
        f"\n{indent}// {MARKER_POST}\n"
        f"{indent}try {{\n"
        f"{indent}    modRegisterActive(this);\n"
        f"{indent}    modApplyUserSubtitleStyleFromPolicy();\n"
        f"{indent}}} catch (Throwable ignored) {{}}\n"
    )
    text = text[:brace + 1] + body[:call.end()] + post + body[call.end():] + text[end:]
    OK.append("post-init after applyPostInitOptions inside ensureInitialized")
    return text


def inject_apply_subtitle_style_policy(text: str) -> str:
    """Re-apply the selected app font after MpvPlayer's normal caption style."""
    marker = "MPV_PHASE_C_STYLE_LAST_V13"
    body = extract_method_body(text, "applySubtitleStyle")
    if body and marker in body:
        OK.append("selected subtitle style already applied last")
        return text
    method = find_method(text, "applySubtitleStyle")
    if method is None:
        ERR.append("applySubtitleStyle declaration not found")
        return text
    _, brace, end = method
    body = text[brace + 1:end]
    # Insert immediately before the method's closing brace, after every normal
    # sub-font/sub-color assignment, so system CaptioningManager cannot overwrite
    # the user's selected font.
    insert = (
        "\n        // " + marker + "\n"
        "        try { modApplyUserSubtitleStyleFromPolicy(); } catch (Throwable ignored) {}\n"
    )
    text = text[:end] + insert + text[end:]
    OK.append("selected app subtitle style applied after native caption style")
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
    """Unregister the active player where the native owner is actually cleared."""
    body = extract_method_body(text, "releaseNativeContextLocked")
    if body and MARKER_OWNER in body:
        OK.append("owner clear already in releaseNativeContextLocked")
        return text

    # Remove only a legacy marked owner-clear try block, wherever V5-V11 placed it.
    text = re.sub(
        r'^[ \t]*try \{ // MPV_MOD_OWNER_CLEAR_V(?:5|6|7|8|9|10|11|12|13)[\s\S]{0,200}?catch \(Throwable ignored\) \{\}\r?\n',
        "",
        text,
        flags=re.M,
    )
    method = find_method(text, "releaseNativeContextLocked")
    if method is None:
        ERR.append("releaseNativeContextLocked declaration not found")
        return text
    _, brace, end = method
    body = text[brace + 1:end]
    owner = re.search(r'if \(ownsNativeContext\)\s+nativeContextOwner\s*=\s*null\s*;', body)
    if not owner:
        ERR.append("ownsNativeContext nativeContextOwner clear not found in releaseNativeContextLocked")
        return text
    line_start = body.rfind("\n", 0, owner.start()) + 1
    indent = re.match(r"[ \t]*", body[line_start:]).group(0)
    insert = (
        owner.group(0) + "\n"
        + indent + f"// {MARKER_OWNER}: unregister with native owner release\n"
        + indent + "modUnregisterActive(this);"
    )
    body = body[:owner.start()] + insert + body[owner.end():]
    text = text[:brace + 1] + body + text[end:]
    OK.append("owner unregister in releaseNativeContextLocked")
    return text


def find_method(text: str, method_name: str):
    """Return (declaration_start, opening_brace, closing_brace), including throws clauses."""
    pattern = re.compile(
        rf"(?m)^[ \t]*(?:(?:public|protected|private)\s+)?"
        rf"(?:static\s+)?[\w$.<>?, \[\]]+\s+{re.escape(method_name)}\s*"
        rf"\([^;{{}}]*\)\s*(?:throws\s+[^{{}}]+)?\{{"
    )
    matches = list(pattern.finditer(text))
    if len(matches) != 1:
        return None
    match = matches[0]
    brace = text.find("{", match.start(), match.end())
    end = find_matching_brace(text, brace)
    if brace < 0 or end < 0:
        return None
    return match.start(), brace, end


def extract_method_body(text: str, method_name: str) -> str | None:
    method = find_method(text, method_name)
    if method is None:
        return None
    _, brace, end = method
    return text[brace:end + 1]


def extract_method(text: str, method_name: str) -> str | None:
    method = find_method(text, method_name)
    if method is None:
        return None
    start, _, end = method
    return text[start:end + 1]


def verify_injected(text: str) -> None:
    if MARKER_BRIDGE_BEGIN not in text or MARKER_BRIDGE_END not in text:
        ERR.append("VERIFY: bridge BEGIN/END missing")
    if 'modSetRuntimeProperty("sub-ass-style-overrides", forceStyle)' not in text:
        ERR.append("VERIFY: valid ASS style override property missing")
    if "sub-ass-force-style" in text:
        ERR.append("VERIFY: invalid sub-ass-force-style property remains")
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

    p_slang = pos(re.escape(MARKER_OPT))
    p_init = pos(r"mpvInit\s*\(\s*\)\s*;")
    p_true = pos(r"initialized\s*=\s*true\s*;")
    p_post = pos(r"applyPostInitOptions\s*\(\s*\)\s*;")
    p_style = pos(re.escape(MARKER_POST))
    if p_slang < 0:
        ERR.append("VERIFY: slang marker not inside ensureInitialized")
    elif 'modSetInitOption("slang", android.text.TextUtils.join(",", com.fongmi.android.tv.player.track.LangUtil.getPreferredTextLanguages()))' not in body:
        ERR.append("VERIFY: slang priority string incorrect or missing")
    if "MPV_PHASE_C_FONT_OPTIONS_V14" not in body:
        ERR.append("VERIFY: pre-init custom font options missing")
    else:
        font_pos = body.find("MPV_PHASE_C_FONT_OPTIONS_V14")
        if not (0 <= font_pos < body.find("mpvInit")):
            ERR.append("VERIFY: custom font options are not before mpvInit")
        for option in ('modSetInitOption("sub-fonts-dir", fontsDir)', 'modSetInitOption("sub-font", fontFamily)'):
            if option not in body:
                ERR.append("VERIFY: missing pre-init " + option)
    if min(p_init, p_true, p_post, p_style) < 0:
        ERR.append("VERIFY: missing step in ensureInitialized")
    elif not (p_slang < p_init < p_true < p_post < p_style):
        ERR.append(
            f"VERIFY: bad order slang={p_slang} init={p_init} true={p_true} post={p_post} style={p_style}"
        )

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
    style_body = extract_method_body(text, "applySubtitleStyle")
    if not style_body or "MPV_PHASE_C_STYLE_LAST_V13" not in style_body or "modApplyUserSubtitleStyleFromPolicy()" not in style_body:
        ERR.append("VERIFY: selected app font is not applied after applySubtitleStyle")

    owner_body = extract_method_body(text, "releaseNativeContextLocked")
    if not owner_body or MARKER_OWNER not in owner_body or "modUnregisterActive(this)" not in owner_body:
        ERR.append("VERIFY: owner unregister not inside releaseNativeContextLocked")
    OK.append("VERIFY all checks executed")


def write_policy() -> None:
    if "platformID == 0 || platformID == 3" not in POLICY_JAVA:
        ERR.append("VERIFY: Unicode platform font names are not decoded as UTF-16BE")
    if "id16Rank" not in POLICY_JAVA or "0x0804" not in POLICY_JAVA:
        ERR.append("VERIFY: font family language priority is incomplete")
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(POLICY_JAVA, encoding="utf-8")
    OK.append("policy V12 written")


def patch_setting() -> None:
    marker = "MPV_FONT_APPLY_AFTER_PUT_V12"
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        ERR.append("Setting.java not found — cannot SUCCESS without real Setting")
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
    print(f"[mod] inject_mpv_phase_c V12 ROOT={ROOT}")
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
            if ERR:
                print("---- results ----")
                for x in OK:
                    print(" OK", x)
                for x in ERR:
                    print(" FAIL", x)
                print("[mod] inject_mpv_phase_c FAILED")
                return 1
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
    text = inject_apply_subtitle_style_policy(text)
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
