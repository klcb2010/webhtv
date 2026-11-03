package com.fongmi.android.tv.utils;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ModDebugLog {
    private static final String PREF = "mod_debug_log";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static volatile Boolean enabled;
    private static volatile File logFile;

    private ModDebugLog() {}

    public static void setEnabled(boolean on) {
        enabled = on;
        try { Prefers.put(PREF, on); } catch (Throwable ignored) {}
    }

    public static boolean isEnabled() {
        // MPV-BRANCH-ONLY: 字幕字体/颜色排障默认开启（只写关键点，2MB 滚动）
        // 需要关闭时调用 setEnabled(false)；重启后会再次默认开启。
        if (enabled != null) return enabled;
        enabled = Boolean.TRUE;
        return true;
    }

    public static void init(Context context) {
        if (context == null) return;
        try {
            File dir = new File(context.getCacheDir(), "mod_debug");
            dir.mkdirs();
            logFile = new File(dir, "mod.log");
        } catch (Throwable ignored) {}
    }

    public static void i(String tag, String msg) {
        if (!isEnabled()) return;
        write(tag, msg, "I");
    }

    public static void w(String tag, String msg) {
        write(tag, msg, "W");
    }

    private static void write(String tag, String msg, String level) {
        String text = msg == null ? "" : msg;
        String safeTag = tag == null ? "Mod" : tag;
        // MPV-BRANCH-ONLY: 同步投一份到 logcat —— 文件有页缓存/查看器不刷新的问题，
        // logcat 是实时的，`adb logcat -s MpvSubtitleStyle Updater` 或算法助手可直接抓。
        try {
            if ("W".equals(level)) Log.w(safeTag, text);
            else Log.i(safeTag, text);
        } catch (Throwable ignored) {}

        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
            + " " + level + "/" + safeTag + ": " + text + "\n";
        try {
            File f = logFile;
            if (f == null) {
                try {
                    Context ctx = App.get();
                    if (ctx != null) init(ctx);
                    f = logFile;
                } catch (Throwable ignored) {}
            }
            if (f == null) return;
            if (f.length() > MAX_BYTES) f.delete();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                // MPV-BRANCH-ONLY: 立即落盘，否则用文件管理器查看时可能看不到刚写的行
                try { out.getFD().sync(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
