package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.setting.Setting;

/**
 * MPV 字幕样式策略（对齐常见 lua 脚本思路，但读 App 设置，不单独塞脚本）：
 * <ul>
 *   <li>{@code sub-ass-override=force} — ASS/内嵌样式也吃用户色</li>
 *   <li>{@code sub-ass-force-style} — PrimaryColour 等 ASS 强制样式</li>
 *   <li>{@code sub-color} — SRT/纯文本字幕颜色（r/g/b 0~1）</li>
 * </ul>
 * 上游 MpvPlayer 应在建链/改设置时读取本类常量与方法。
 */
public final class MpvSubtitleStylePolicy {

    /** 必须 force，scale 只缩放不改色（与社区脚本一致） */
    public static final String ASS_OVERRIDE = "force";

    private MpvSubtitleStylePolicy() {
    }

    /**
     * MPV {@code sub-ass-force-style} 字符串（BGR &HBBGGRR&）。
     * 描边/阴影固定黑，保证深色画面可读。
     */
    public static String getAssForceStyle() {
        int argb = Setting.getSubtitleColorArgb();
        String primary = toAssColour(argb);
        return "PrimaryColour=" + primary
                + ",SecondaryColour=" + primary
                + ",OutlineColour=&H000000&,BackColour=&H000000&"
                + ",Outline=2,Shadow=1,BorderStyle=1";
    }

    /**
     * MPV {@code sub-color}：{@code r/g/b} 浮点 0~1，供 SRT 等非 ASS 轨使用。
     */
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
        return "sans-serif";
    }

    /** ASS 颜色：&HAABBGGRR&（MPV/libass 惯例，A=00 不透明） */
    private static String toAssColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return String.format(java.util.Locale.US, "&H00%02X%02X%02X&", b, g, r);
    }
}
