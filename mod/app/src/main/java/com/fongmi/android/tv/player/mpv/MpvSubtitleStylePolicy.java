package com.fongmi.android.tv.player.mpv;

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
