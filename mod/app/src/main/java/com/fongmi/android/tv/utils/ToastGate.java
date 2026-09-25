package com.fongmi.android.tv.utils;

import android.os.Build;
import android.widget.Toast;

import com.fongmi.android.tv.setting.Setting;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 拦截系统 Toast 入队（含外挂 JAR 直接 Toast.makeText/show），
 * 受个性设置「全局吐司」控制。返回值按方法类型给出，避免 unbox NPE。
 */
public final class ToastGate {

    private static volatile boolean installed;

    private ToastGate() {
    }

    public static void install() {
        if (installed) return;
        synchronized (ToastGate.class) {
            if (installed) return;
            try {
                hookToastService();
                installed = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookToastService() throws Exception {
        // Toast.getService() / sService 在不同 API 字段名不同
        Object service = null;
        Field serviceField = null;
        for (String name : new String[]{"sService", "INotificationManager", "sServiceInstance"}) {
            try {
                Field f = Toast.class.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    service = v;
                    serviceField = f;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (service == null) {
            // 触发一次内部初始化
            try {
                Method getService = Toast.class.getDeclaredMethod("getService");
                getService.setAccessible(true);
                service = getService.invoke(null);
            } catch (Throwable ignored) {
            }
            try {
                Field f = Toast.class.getDeclaredField("sService");
                f.setAccessible(true);
                if (service == null) service = f.get(null);
                serviceField = f;
            } catch (Throwable ignored) {
            }
        }
        if (service == null || serviceField == null) return;
        if (Proxy.isProxyClass(service.getClass())) return;

        final Object original = service;
        ClassLoader cl = original.getClass().getClassLoader();
        Class<?>[] ifaces = original.getClass().getInterfaces();
        if (ifaces == null || ifaces.length == 0) {
            // 可能是 stub 类，尝试 INotificationManager
            try {
                Class<?> nm = Class.forName("android.app.INotificationManager");
                ifaces = new Class<?>[]{nm};
            } catch (Throwable e) {
                return;
            }
        }
        Object proxy = Proxy.newProxyInstance(cl, ifaces, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name != null && name.startsWith("enqueueToast")) {
                    boolean allow = true;
                    try {
                        allow = Setting.isGlobalToast();
                    } catch (Throwable ignored) {
                    }
                    if (!allow) {
                        return defaultValue(method.getReturnType());
                    }
                }
                try {
                    return method.invoke(original, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable c = e.getCause();
                    if (c != null) throw c;
                    throw e;
                }
            }
        });
        serviceField.set(null, proxy);
    }

    private static Object defaultValue(Class<?> rt) {
        if (rt == null || rt == void.class || rt == Void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == Boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == byte.class) return (byte) 0;
        if (rt == short.class) return (short) 0;
        if (rt == char.class) return (char) 0;
        return null;
    }
}
