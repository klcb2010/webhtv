package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

/**
 * TV / 公共入口：转发到 PhoneScreenshot（手机实现）或仅提示不可用。
 * 阶段 B：与控制栏共用同一套 EXO Surface / MPV 原画分流逻辑。
 */
public final class ScreenshotHelper {
    public interface Callback {
        void onSuccess(Uri uri);
        void onError(String message);
    }

    private ScreenshotHelper() {}

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        try {
            // 优先走手机完整实现（main + mobile 合并编译时可用）
            Class<?> cl = Class.forName("com.fongmi.android.tv.utils.PhoneScreenshot");
            java.lang.reflect.Method m = cl.getMethod("capture", Activity.class, PlayerView.class,
                    Class.forName("com.fongmi.android.tv.utils.PhoneScreenshot$Callback"));
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    cl.getClassLoader(),
                    new Class[]{Class.forName("com.fongmi.android.tv.utils.PhoneScreenshot$Callback")},
                    (proxy, method, args) -> {
                        if (callback == null) return null;
                        String name = method.getName();
                        if ("onSuccess".equals(name) && args != null && args.length > 0) {
                            callback.onSuccess((Uri) args[0]);
                        } else if ("onError".equals(name)) {
                            String msg = (args != null && args.length > 0 && args[0] != null) ? String.valueOf(args[0]) : null;
                            callback.onError(msg);
                        }
                        return null;
                    });
            m.invoke(null, activity, playerView, cb);
        } catch (Throwable e) {
            if (callback != null) {
                try {
                    callback.onError(activity != null
                            ? activity.getString(com.fongmi.android.tv.R.string.screenshot_unavailable)
                            : "unavailable");
                } catch (Throwable e2) {
                    callback.onError("unavailable");
                }
            }
        }
    }
}
