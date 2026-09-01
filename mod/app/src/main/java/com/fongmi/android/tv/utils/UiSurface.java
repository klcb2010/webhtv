package com.fongmi.android.tv.utils;

import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * - show(): swallow Result.msg only (app Notify 仍正常)
 * - install(): 尽量拦截系统 Toast.enqueue*，返回类型必须合法（boolean→false），禁止返回 null
 */
public final class UiSurface {

    private static volatile boolean installed;

    private UiSurface() {
    }

    public static void show(String msg) {
        // spider Result.msg：不弹
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
        Object base = field.get(null);
        if (base == null) {
            try {
                Method getService = Toast.class.getDeclaredMethod("getService");
                getService.setAccessible(true);
                getService.invoke(null);
                base = field.get(null);
            } catch (Throwable ignored) {
            }
        }
        if (base == null) return;

        final Object target = base;
        Object proxy = Proxy.newProxyInstance(
                target.getClass().getClassLoader(),
                target.getClass().getInterfaces(),
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String n = method.getName();
                        if (n != null) {
                            String ln = n.toLowerCase();
                            // 拦截排队显示 toast；必须按返回类型给默认值
                            if (ln.contains("toast") && (ln.contains("enqueue") || ln.contains("show"))) {
                                return defaultValue(method.getReturnType());
                            }
                        }
                        return method.invoke(target, args);
                    }
                });
        field.set(null, proxy);
    }

    private static Object defaultValue(Class<?> rt) {
        if (rt == null || rt == void.class || rt == Void.class) return null;
        if (rt == boolean.class) return false;
        if (rt == Boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == short.class) return (short) 0;
        if (rt == byte.class) return (byte) 0;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        if (rt == char.class) return '\0';
        return null;
    }
}
