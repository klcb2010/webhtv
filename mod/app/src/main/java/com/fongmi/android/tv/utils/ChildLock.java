package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.setting.Setting;

/**
 * 儿童管理：进程内解锁状态。开启后下次冷启动需密码。
 */
public final class ChildLock {

    private static volatile boolean unlockedThisProcess = false;

    private ChildLock() {
    }

    public static boolean isEnabled() {
        return Setting.isChildLockEnabled()
                && Setting.getChildLockPasswordHash() != null
                && !Setting.getChildLockPasswordHash().isEmpty();
    }

    public static boolean isUnlocked() {
        return unlockedThisProcess;
    }

    public static void unlock() {
        unlockedThisProcess = true;
    }

    /** 设置保存后本次不锁；仅影响「是否需要弹窗」判断前可重置，一般不调用 */
    public static void lockAgain() {
        unlockedThisProcess = false;
    }

    public static boolean needsGate() {
        return isEnabled() && !unlockedThisProcess;
    }
}
