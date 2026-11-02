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
 * MPV 字幕字体/颜色：对齐 mpvKt 做法 —— 将当前选中字体落到 config 目录的 subfont.ttf，
 * 并设置 sub-fonts-dir + sub-font（中文显示名 / id）。
 */
public final class MpvSubtitleStylePolicy {

    public static final String ASS_OVERRIDE = "force";

    private MpvSubtitleStylePolicy() {
    }

    /** 确保字体已从 assets 解压，并把当前选择复制为 subfont.ttf（libass 默认回退字体）。 */
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
            } catch (Throwable ignored) {
            }
        }
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        try {
            int idx = Setting.getSubtitleFontIndex();
            File src = SubtitleFontManager.getFontFile(idx);
            if (src != null && src.isFile() && dir != null) {
                File subfont = new File(dir, "subfont.ttf");
                copyFile(src, subfont);
                // 同时保留原 id 文件名，方便 sub-font=id 命中
                String id = SubtitleFontManager.idOf(idx);
                if (id != null && !id.isEmpty() && !"system".equals(id)) {
                    File named = new File(dir, id + ".ttf");
                    if (!named.exists()) copyFile(src, named);
                }
            }
        } catch (Throwable ignored) {
        }
        return dir;
    }

    private static void copyFile(File src, File dst) {
        if (src == null || dst == null) return;
        try {
            if (dst.exists() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) {
                return;
            }
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String getAssForceStyle() {
        ensureFontsReady();
        int argb = Setting.getSubtitleColorArgb();
        String primary = toAssColour(argb);
        String zh = Setting.getSubtitleFontFamily();
        if (zh == null || zh.isEmpty()) {
            try {
                zh = SubtitleFontManager.displayNameOf(Setting.getSubtitleFontIndex());
            } catch (Throwable ignored) {
                zh = "义启粗楷";
            }
        }
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
     * sub-font：优先中文显示名（libass FontName），再 id。
     * 配合 subfont.ttf 与 sub-fonts-dir 使用。
     */
    public static String getSubFontProperty() {
        ensureFontsReady();
        try {
            String zh = Setting.getSubtitleFontFamily();
            if (zh != null && !zh.isEmpty() && !"sans-serif".equalsIgnoreCase(zh)) return zh;
        } catch (Throwable ignored) {
        }
        try {
            int idx = Setting.getSubtitleFontIndex();
            String dn = SubtitleFontManager.displayNameOf(idx);
            if (dn != null && !dn.isEmpty() && !"sans-serif".equalsIgnoreCase(dn)) return dn;
            String id = SubtitleFontManager.idOf(idx);
            if (id != null && !id.isEmpty() && !"system".equals(id)) return id;
        } catch (Throwable ignored) {
        }
        try {
            String id = Setting.getSubtitleFontId();
            if (id != null && !id.isEmpty() && !"system".equals(id)) return id;
        } catch (Throwable ignored) {
        }
        return "义启粗楷";
    }

    public static String getSubFontsDirProperty() {
        File dir = ensureFontsReady();
        if (dir != null && dir.isDirectory()) return dir.getAbsolutePath();
        return "";
    }

    private static String toAssColour(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = (argb & 0xFF);
        return String.format(java.util.Locale.US, "&H00%02X%02X%02X&", b, g, r);
    }
}
