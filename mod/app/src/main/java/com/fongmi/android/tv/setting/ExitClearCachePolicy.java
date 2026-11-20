package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.utils.Path;

/**
 * 退出 App 时按档位清理缓存：0 不清理；6/8/10 表示缓存 ≥ 该 GB 才清理。
 */
public final class ExitClearCachePolicy {

    private ExitClearCachePolicy() {
    }

    public static boolean shouldRun(boolean finishing, boolean changingConfigurations) {
        return finishing && !changingConfigurations && Setting.getExitClearCacheGb() > 0;
    }

    public static void runAsync() {
        final int gb = Setting.getExitClearCacheGb();
        if (gb <= 0) return;
        final long limit = gb * 1024L * 1024L * 1024L;
        // 后台扫描体积并清理，避免阻塞退出
        new Thread(() -> {
            try {
                long size = FileUtil.getDirectorySize(Path.cache());
                if (size >= limit) {
                    Path.clear(Path.cache());
                }
            } catch (Throwable ignored) {
            }
        }, "exit-clear-cache").start();
    }
}
