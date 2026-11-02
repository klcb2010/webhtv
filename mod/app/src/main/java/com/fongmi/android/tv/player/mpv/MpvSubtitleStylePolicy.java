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
