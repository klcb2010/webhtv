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
import java.util.concurrent.ConcurrentHashMap;

public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";
    private static final ConcurrentHashMap<String, String> FAMILY_CACHE = new ConcurrentHashMap<>();
    private static final Object ENSURE_LOCK = new Object();
    private static volatile String sLastFingerprint = "";

    private MpvSubtitleStylePolicy() {
    }

    private static String fingerprint(String id, File src) {
        if (src == null || !src.isFile()) return id + "|missing";
        return id + "|" + src.length() + "|" + src.lastModified();
    }

    private static boolean targetOk(File dst, File src) {
        if (dst == null || !dst.isFile() || src == null || !src.isFile()) return false;
        return dst.length() == src.length() && dst.length() > 0;
    }

    public static File ensureFontsReady() {
        synchronized (ENSURE_LOCK) {
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
                if (id == null) id = "";
                File src = SubtitleFontManager.getFontFile(idx);
                String fp = fingerprint(id, src);
                File subfont = new File(dir, "subfont.ttf");
                File named = (id.isEmpty() || "system".equals(id)) ? null : new File(dir, id + ".ttf");
                boolean need = !fp.equals(sLastFingerprint)
                        || !targetOk(subfont, src)
                        || (named != null && src != null && src.isFile() && !targetOk(named, src));
                if (need && src != null && src.isFile()) {
                    forceCopy(src, subfont);
                    if (named != null) forceCopy(src, named);
                    sLastFingerprint = fp;
                }
            } catch (Throwable ignored) {
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
