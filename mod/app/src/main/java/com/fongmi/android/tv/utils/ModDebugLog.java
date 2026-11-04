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

    // ============================================================
    // 日志总开关：排查问题时把 false 改成 true（改这一处即可，其余不用动）
    // ============================================================
    private static final boolean LOG_ACTIVE = true;

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
        if (!LOG_ACTIVE) return false;
        if (enabled != null) return enabled;
        enabled = Boolean.TRUE;
        return true;
    }

    public static void init(Context context) {
        if (!LOG_ACTIVE || context == null) return;
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
        if (!isEnabled()) return;
        write(tag, msg, "W");
    }

    private static void write(String tag, String msg, String level) {
        if (!LOG_ACTIVE) return;
        String text = msg == null ? "" : msg;
        String safeTag = tag == null ? "Mod" : tag;
        // 同步投一份到 logcat —— 文件有页缓存/查看器不刷新的问题，logcat 是实时的
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
                    if (ctx != null) {
                        File dir = new File(ctx.getCacheDir(), "mod_debug");
                        dir.mkdirs();
                        logFile = new File(dir, "mod.log");
                    }
                    f = logFile;
                } catch (Throwable ignored) {}
            }
            if (f == null) return;
            if (f.length() > MAX_BYTES) f.delete();
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { out.getFD().sync(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}
