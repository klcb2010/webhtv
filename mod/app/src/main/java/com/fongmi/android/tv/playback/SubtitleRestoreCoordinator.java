package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Sub;

/**
 * 外挂字幕「自动记忆/恢复」已关闭：保留空壳避免注入点编译失败，不执行任何逻辑。
 * 用户手动在字幕列表中选择即可。
 */
public final class SubtitleRestoreCoordinator {

    private SubtitleRestoreCoordinator() {
    }

    public static void bindHistory(History history) {
    }

    public static void clearBind() {
    }

    public static void onUserSetSub(Sub sub) {
    }

    public static void prepareRestore(History history) {
    }

    public static void injectPendingIntoPlayerManager(Object playerManager) {
    }

    public static void clearPending() {
    }

    public static Sub peekPending() {
        return null;
    }

    public static String peekPendingName() {
        return "";
    }

    public static String peekPendingFormat() {
        return "";
    }

    public static boolean remember(History history, Sub sub) {
        return false;
    }

    public static boolean remember(String historyKey, String episodeUrl, Sub sub) {
        return false;
    }

    public static Sub restore(History history, Object player, Object result) {
        return null;
    }
}
