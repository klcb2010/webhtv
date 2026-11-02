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
 * MPV 字幕：字体目录 + subfont.ttf（mpvKt 思路）+ 运行时属性由调用方 setProperty 写入。
 * sub-font 使用字体文件主名（yiqicukai 等），与目录中 yiqicukai.ttf 对应，避免未验证的中文族名。
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
                // 始终覆盖 subfont.ttf（内容以源文件为准，不靠 size/mtime 短路）
                File subfont = new File(dir, "subfont.ttf");
                forceCopy(src, subfont);
                if (id != null && !id.isEmpty() && !"system".equals(id)) {
                    File named = new File(dir, id + ".ttf");
                    forceCopy(src, named);
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
        // FontName 用文件主名（与 sub-fonts-dir 中 *.ttf 对应），不用未验证中文显示名
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

    /** 文件主名：yiqicukai / huipianheyuan / fangzhengzhunyuan；系统默认用 sans-serif */
    public static String getSubFontProperty() {
        ensureFontsReady();
        try {
            int idx = Setting.getSubtitleFontIndex();
            String id = SubtitleFontManager.idOf(idx);
            if (id != null && "system".equals(id)) return "sans-serif";
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {
        }
        try {
            String id = Setting.getSubtitleFontId();
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {
        }
        return "yiqicukai";
    }

    public static String getSubFontsDirProperty() {
        File dir = ensureFontsReady();
        if (dir != null && dir.isDirectory()) return dir.getAbsolutePath();
        return "";
    }

    /**
     * 对已创建的 MPV 引擎写入运行时属性（setProperty），不是 setOption。
     * invokeOn 可为 MpvPlayer 或内部 native 包装。
     */
    public static void applyRuntime(Object player) {
        if (player == null) return;
        ensureFontsReady();
        String dir = getSubFontsDirProperty();
        String font = getSubFontProperty();
        String style = getAssForceStyle();
        String color = getSubColorProperty();
        trySet(player, "sub-ass-override", ASS_OVERRIDE);
        trySet(player, "embeddedfonts", "yes");
        if (dir != null && !dir.isEmpty()) trySet(player, "sub-fonts-dir", dir);
        if (font != null && !font.isEmpty()) trySet(player, "sub-font", font);
        if (style != null && !style.isEmpty()) trySet(player, "sub-ass-force-style", style);
        if (color != null && !color.isEmpty()) trySet(player, "sub-color", color);
        trySet(player, "sub-border-color", "0.0/0.0/0.0");
        trySet(player, "sub-border-size", "2");
        // 简体优先：MPV 原生 slang（mpvKt defaultPreferredSubtitleLanguages 思路）
        trySet(player, "slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht");
        trySet(player, "subs-with-matching-audio", "no");
    }

    private static void trySet(Object player, String key, String value) {
        if (player == null || key == null || value == null) return;
        // 优先 setProperty（运行时），再 setOption
        for (String method : new String[]{"setProperty", "setOption"}) {
            try {
                java.lang.reflect.Method m = player.getClass().getMethod(method, String.class, String.class);
                m.invoke(player, key, value);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            java.lang.reflect.Method cmd = player.getClass().getMethod("command", String[].class);
            cmd.invoke(player, (Object) new String[]{"set", key, value});
        } catch (Throwable ignored) {
        }
    }

    private static String toAssColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = (argb & 0xFF);
        return String.format(java.util.Locale.US, "&H00%02X%02X%02X&", b, g, r);
    }
}
