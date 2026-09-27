package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.subtitle.SubtitleFontManager;

import java.io.File;

public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";

    private MpvSubtitleStylePolicy() {
    }

    public static String getAssForceStyle() {
        int argb = Setting.getSubtitleColorArgb();
        String primary = toAssColour(argb);
        String zh = Setting.getSubtitleFontFamily(); // 雅黑/幼圆/楷体
        if (zh == null || zh.isEmpty()) zh = "雅黑";
        // FontName 用中文名（与别名文件 楷体.ttf 对应）
        return "FontName=" + zh
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
     * sub-font：中文族名优先（配合 楷体.ttf 别名），再 id。
     */
    public static String getSubFontProperty() {
        try {
            String zh = Setting.getSubtitleFontFamily();
            if (zh != null && !zh.isEmpty()) return zh;
        } catch (Throwable ignored) {
        }
        try {
            String id = Setting.getSubtitleFontId();
            if (id != null && !id.isEmpty()) return id;
        } catch (Throwable ignored) {
        }
        return "雅黑";
    }

    public static String getSubFontsDirProperty() {
        try {
            SubtitleFontManager.prepareAllFonts();
            File dir = SubtitleFontManager.getFontsDir();
            if (dir != null && dir.isDirectory()) return dir.getAbsolutePath();
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String toAssColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return String.format(java.util.Locale.US, "&H00%02X%02X%02X&", b, g, r);
    }
}
