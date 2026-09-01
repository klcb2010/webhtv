package com.fongmi.android.tv.utils;

import android.os.Build;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * UI surface helpers. Toast enqueue is gated process-wide when install() succeeds.
 */
public final class UiSurface {

    private static volatile boolean installed;

    private UiSurface() {
    }

    /** Result.msg path — never surfaces as toast. */
    public static void show(String msg) {
        // no-op
    }

    public static void install() {
        if (installed) return;
        synchronized (UiSurface.class) {
            if (installed) return;
            installed = true;
            try {
                gateToastService();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void gateToastService() throws Exception {
        // Legacy Toast path: INotificationManager.enqueueToast*
        Field field = null;
        for (String name : new String[]{"sService", "service"}) {
            try {
                field = Toast.class.getDeclaredField(name);
                break;
            } catch (NoSuchFieldException ignored) {
            }
        }
        if (field == null) return;
        field.setAccessible(true);
        final Object original = field.get(null);
        if (original == null) {
            // force getService()
            try {
                Method getService = Toast.class.getDeclaredMethod("getService");
                getService.setAccessible(true);
                getService.invoke(null);
            } catch (Throwable ignored) {
            }
        }
        final Object base = field.get(null);
        if (base == null) return;
        Object proxy = Proxy.newProxyInstance(
                base.getClass().getClassLoader(),
                base.getClass().getInterfaces(),
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String n = method.getName();
                        if (n != null) {
                            String ln = n.toLowerCase();
                            if (ln.contains("enqueue") && ln.contains("toast")) {
                                return null;
                            }
                            if (ln.equals("enqueuetoast") || ln.equals("enqueuetoastex")) {
                                return null;
                            }
                        }
                        return method.invoke(base, args);
                    }
                });
        field.set(null, proxy);
        if (Build.VERSION.SDK_INT >= 28) {
            // best-effort; may no-op on some OEM builds
        }
    }
}
