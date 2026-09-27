package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.subtitle.SubtitleFontManager;

import java.io.File;

/**
 * MPV 字幕样式：颜色 + 内置字体（yahei/youyuan/kaiti）。
 */
public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";

    private MpvSubtitleStylePolicy() {
    }

    public static String getAssForceStyle() {
        int argb = Setting.getSubtitleColorArgb();
        String primary = toAssColour(argb);
        String fontName = Setting.getSubtitleFontFamily();
        if (fontName == null || fontName.isEmpty()) fontName = "雅黑";
        return "FontName=" + fontName
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
            String fam = Setting.getSubtitleFontFamily();
            if (fam != null && !fam.isEmpty()) return fam;
        } catch (Throwable ignored) {
        }
        return "雅黑";
    }

    /** MPV sub-fonts-dir：解压后的内置字体目录 */
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
