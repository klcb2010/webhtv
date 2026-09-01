package com.fongmi.android.tv.utils;

/**
 * Result.msg 不展示。不代理系统 Toast（避免 enqueueToast 返回 null 导致 NPE）。
 */
public final class UiSurface {

    private UiSurface() {
    }

    public static void show(String msg) {
        // no-op
    }

    public static void install() {
        // no-op
    }
}
