package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.widget.Toast;

import com.fongmi.android.tv.App;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Result.msg + optional toast filter.
 * Rules file is an opaque asset written only at CI time (not in public source).
 */
public final class UiSurface {

    private static final String ASSET = "intoast";
    private static final AtomicReference<List<String>> RULES = new AtomicReference<>();
    private static volatile boolean installed;

    private UiSurface() {
    }

    /** Spider Result.msg：命中规则才丢弃；无规则时仍丢弃 msg（避免广告文案） */
    public static void show(String msg) {
        // Result.msg 一律不走 Notify；系统 Toast 由 install 黑名单处理
    }

    public static boolean blocked(String text) {
        if (TextUtils.isEmpty(text)) return false;
        List<String> rules = rules();
        if (rules.isEmpty()) return false;
        for (String r : rules) {
            if (!r.isEmpty() && text.contains(r)) return true;
        }
        return false;
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

    private static List<String> rules() {
        List<String> cached = RULES.get();
        if (cached != null) return cached;
        synchronized (UiSurface.class) {
            cached = RULES.get();
            if (cached != null) return cached;
            List<String> loaded = load();
            RULES.set(loaded);
            return loaded;
        }
    }

    private static List<String> load() {
        try {
            InputStream in = App.get().getAssets().open(ASSET);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> list = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                list.add(line);
            }
            br.close();
            return list.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(list);
        } catch (Throwable ignored) {
            return Collections.emptyList();
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
                            if (ln.contains("toast") && (ln.contains("enqueue") || ln.equals("show"))) {
                                if (shouldBlockArgs(args)) {
                                    return defaultValue(method.getReturnType());
                                }
                            }
                        }
                        return method.invoke(target, args);
                    }
                });
        field.set(null, proxy);
    }

    private static boolean shouldBlockArgs(Object[] args) {
        if (args == null || rules().isEmpty()) return false;
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof CharSequence) {
                if (blocked(a.toString())) return true;
            }
            if (a instanceof Toast) {
                String t = toastText((Toast) a);
                if (blocked(t)) return true;
            }
            // 部分实现把文本放在 Object[] / List
            if (a instanceof Object[]) {
                if (shouldBlockArgs((Object[]) a)) return true;
            }
        }
        return false;
    }

    private static String toastText(Toast toast) {
        try {
            Field f = Toast.class.getDeclaredField("mText");
            f.setAccessible(true);
            Object v = f.get(toast);
            return v == null ? "" : v.toString();
        } catch (Throwable e) {
            try {
                Method m = Toast.class.getMethod("getText");
                Object v = m.invoke(toast);
                return v == null ? "" : v.toString();
            } catch (Throwable ignored) {
                return "";
            }
        }
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
