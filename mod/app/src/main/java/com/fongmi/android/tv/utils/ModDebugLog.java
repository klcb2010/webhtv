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
    // 日志总开关（编译期）：V30 起默认 true；想彻底不打日志就改成 false
    // 运行期可在「个性设置 → 播放器临时调试日志」里随时开关，点一下立即生效
    // ============================================================
    private static final boolean LOG_ACTIVE = true;

    /** 运行期开关（内存态，点一下立即生效） */
    private static volatile Boolean runtimeOverride;

    private static final String PREF = "mod_debug_log";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static volatile Boolean enabled;
    private static volatile File logFile;

    private ModDebugLog() {}

    public static void setEnabled(boolean on) {
        enabled = on;
        try { Prefers.put(PREF, on); } catch (Throwable ignored) {}
    }

    public static void setRuntimeOverride(boolean on) {
        runtimeOverride = on;
    }

    /** 当前是否在打日志（含临时开关） */
    public static boolean isRuntimeOverrideOn() {
        return runtimeOverride != null ? runtimeOverride : LOG_ACTIVE;
    }

    public static boolean isEnabled() {
        if (!LOG_ACTIVE) return false;
        if (runtimeOverride != null) return runtimeOverride;
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
