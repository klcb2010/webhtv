package com.fongmi.android.tv.player.mpv;

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
