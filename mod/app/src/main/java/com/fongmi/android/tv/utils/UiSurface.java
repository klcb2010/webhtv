package com.fongmi.android.tv.utils;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
 * intoast 名单过滤。enqueue 常不带文案，需从 Toast 视图/字段取文本再决定 cancel。
 */
public final class UiSurface {

    private static final String ASSET = "intoast";
    private static final AtomicReference<List<String>> RULES = new AtomicReference<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean installed;

    private UiSurface() {
    }

    public static void show(String msg) {
        // Result.msg 不展示
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
        if (rules().isEmpty()) return; // 无名单不代理，避免误伤
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
                                Toast toast = findToast(args);
                                String text = collectText(args, toast);
                                if (blocked(text)) {
                                    return defaultValue(method.getReturnType());
                                }
                                // 文案此时可能还没进参数：放行后主线程再取视图/字段，命中则 cancel
                                if (toast != null && !rules().isEmpty()) {
                                    scheduleCancelIfMatched(toast);
                                }
                            }
                        }
                        return method.invoke(target, args);
                    }
                });
        field.set(null, proxy);
    }

    private static void scheduleCancelIfMatched(final Toast toast) {
        Runnable check = new Runnable() {
            int round = 0;

            @Override
            public void run() {
                try {
                    String text = collectText(null, toast);
                    if (blocked(text)) {
                        toast.cancel();
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (round++ < 5) MAIN.postDelayed(this, 50L * round);
            }
        };
        MAIN.post(check);
        MAIN.postDelayed(check, 80);
        MAIN.postDelayed(check, 200);
    }

    private static Toast findToast(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof Toast) return (Toast) a;
        }
        return null;
    }

    private static String collectText(Object[] args, Toast toast) {
        StringBuilder sb = new StringBuilder();
        if (args != null) {
            for (Object a : args) {
                if (a instanceof CharSequence) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(a);
                }
            }
        }
        if (toast != null) {
            String fromView = textFromView(toast.getView());
            if (!TextUtils.isEmpty(fromView)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(fromView);
            }
            String fromField = textFromToastFields(toast);
            if (!TextUtils.isEmpty(fromField)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(fromField);
            }
        }
        return sb.toString();
    }

    private static String textFromView(View view) {
        if (view == null) return "";
        if (view instanceof TextView) {
            CharSequence cs = ((TextView) view).getText();
            return cs == null ? "" : cs.toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.getChildCount(); i++) {
                String t = textFromView(g.getChildAt(i));
                if (TextUtils.isEmpty(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 读取 Toast 文本字段。仅用于名单匹配；lint 在模块 lint.xml 中对私有 API 放行本类。
     */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi"})
    private static String textFromToastFields(Toast toast) {
        if (toast == null) return "";
        String[] names = new String[]{"mText", "mNextView", "text"};
        for (String name : names) {
            try {
                Field f = Toast.class.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(toast);
                if (v instanceof CharSequence) return v.toString();
                if (v instanceof View) {
                    String t = textFromView((View) v);
                    if (!TextUtils.isEmpty(t)) return t;
                }
            } catch (Throwable ignored) {
            }
        }
        // TN.mNextView
        try {
            Field tnF = Toast.class.getDeclaredField("mTN");
            tnF.setAccessible(true);
            Object tn = tnF.get(toast);
            if (tn != null) {
                for (String name : new String[]{"mNextView", "mView"}) {
                    try {
                        Field f = tn.getClass().getDeclaredField(name);
                        f.setAccessible(true);
                        Object v = f.get(tn);
                        if (v instanceof View) {
                            String t = textFromView((View) v);
                            if (!TextUtils.isEmpty(t)) return t;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
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
