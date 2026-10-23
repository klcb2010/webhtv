package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * SHELL_JAR_SETTINGS_REDO v20-nologin
 * 淡蓝背景 + 完整线路列表可滚动 + 下载线程需确认 + 账号登录不劫持
 */
public final class JarPanSettingsShell {

    public static final String ID_LINE_ORDER = "playLineOrderSettings";
    public static final String ID_THREAD = "panThreadHubSettings";
    public static final String ID_LOGIN = "panLoginHubSettings";

    private static final String PREF_ORDER = "playLineOrder";
    private static final String PREF_THREAD = "shell_jar_download_threads";
    private static final int[] THREAD_OPTS = {1, 2, 3, 4, 6, 8, 12, 16};

    /** 用户偏好的淡蓝背景 */
    private static final int BG_PANEL = 0xFFE3F2FD;
    private static final int BG_ROW = 0xFFFFFFFF;
    private static final int BG_ROW_FOCUS = 0xFFBBDEFB;
    private static final int STROKE_FOCUS = 0xFF1976D2;
    private static final int TEXT_MAIN = 0xFF212121;
    private static final int TEXT_SUB = 0xFF616161;

    private static volatile boolean sOpening = false;

    private JarPanSettingsShell() {}

    /**
     * 与壳子其它魔改一致：优先 Util.isLeanback()（按编译 flavor）。
     * 再回退 FEATURE_LEANBACK / UI_MODE，避免部分盒子漏判。
     * 手机端 mobile flavor 下 Util.isLeanback()=false，绝不进壳子 UI。
     */
    public static boolean isTelevision() {
        try {
            if (com.fongmi.android.tv.utils.Util.isLeanback()) return true;
        } catch (Throwable ignored) {
        }
        try {
            Context c = null;
            try { c = com.fongmi.android.tv.App.activity(); } catch (Throwable ignored) {}
            if (c == null) {
                try { c = com.fongmi.android.tv.App.get(); } catch (Throwable ignored) {}
            }
            if (c == null) return false;
            if (c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true;
            int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
            return ui == Configuration.UI_MODE_TYPE_TELEVISION;
        } catch (Throwable e) {
            return false;
        }
    }



    public static boolean isShellPanel(String id) {
        return isShellPanel(id, null);
    }

    public static boolean isShellPanel(String id, String name) {
        if (!isTelevision()) return false;
        String s = id == null ? "" : id.toLowerCase().trim();
        String n = name == null ? "" : name.trim();
        // 账号登录：不劫持，完全走 jar
        if (s.contains("playlineorder") || s.contains("lineorder") || n.contains("线路排序"))
            return true;
        if (s.contains("panthread") || s.contains("threadhub") || n.contains("下载线程"))
            return true;
        return false;
    }

    public static void open(Activity activity, String siteKey, String id) {
        open(activity, siteKey, id, null);
    }

    public static void open(Activity activity, String siteKey, String id, String name) {
        if (activity == null || activity.isFinishing()) return;
        if (!isTelevision()) return;
        sOpening = false; // 永不因脏标记跳过
        sOpening = true;
        try {
            openInner(activity, siteKey, id != null ? id : "");
        } catch (Throwable e) {
            sOpening = false;
        }
    }

    private static void openInner(Activity activity, String siteKey, String id) {
        String s = id == null ? "" : id.toLowerCase();
        if (s.contains("thread") || (id != null && id.contains(ID_THREAD))) {
            openThread(activity);
        } else {
            openLineOrder(activity);
        }
    }



    public static void openPost(String siteKey, String id) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity a = null;
            try {
                a = com.fongmi.android.tv.App.activity();
            } catch (Throwable ignored) {
            }
            if (a == null || a.isFinishing()) {
                try {
                    a = com.github.catvod.Init.activityForDialog();
                } catch (Throwable ignored) {
                }
            }
            if (a != null && !a.isFinishing() && isShellPanel(id, null)) open(a, siteKey, id, null);
        });
    }

    // -------------------- 线路排序 --------------------


    private static void openLineOrder(Activity activity) {
        final List<String> items = mergeOrderList(readOrderPref());
        final int[] sel = {0};

        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, "线路排序"));
        root.addView(sub(activity, "上下键移动 · 左右键上/下移 · 保存"));

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setFillViewport(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        int listH = Math.min(dp(activity, 420), (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f));
        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        TextView btnReset = barBtn(activity, "重置");
        TextView btnSave = barBtn(activity, "保存");
        bar.addView(btnReset);
        bar.addView(btnSave);
        root.addView(bar);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                LinearLayout row = makeRow(activity, (i + 1) + ".  " + items.get(i), null);
                row.setId(View.generateViewId());
                row.setOnFocusChangeListener((v, has) -> {
                    if (has) {
                        sel[0] = idx;
                        scroll.post(() -> {
                            int top = v.getTop();
                            int bottom = v.getBottom();
                            int sl = scroll.getScrollY();
                            int sh = scroll.getHeight();
                            if (top < sl) scroll.smoothScrollTo(0, top);
                            else if (bottom > sl + sh) scroll.smoothScrollTo(0, bottom - sh);
                        });
                    }
                    applyRowFocus(activity, v, has);
                });
                row.setOnKeyListener((v, keyCode, e) -> {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                    boolean moved = false;
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                        if (sel[0] > 0) {
                            String tmp = items.remove(sel[0]);
                            items.add(sel[0] - 1, tmp);
                            sel[0]--;
                            moved = true;
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (sel[0] < items.size() - 1) {
                            String tmp = items.remove(sel[0]);
                            items.add(sel[0] + 1, tmp);
                            sel[0]++;
                            moved = true;
                        }
                    }
                    if (moved) {
                        int keep = sel[0];
                        refresh[0].run();
                        list.post(() -> {
                            if (keep >= 0 && keep < list.getChildCount())
                                list.getChildAt(keep).requestFocus();
                        });
                        return true;
                    }
                    return false;
                });
                list.addView(row);
            }
            int n = list.getChildCount();
            for (int i = 0; i < n; i++) {
                View c = list.getChildAt(i);
                if (i + 1 < n) c.setNextFocusDownId(list.getChildAt(i + 1).getId());
                else c.setNextFocusDownId(btnSave.getId());
                if (i > 0) c.setNextFocusUpId(list.getChildAt(i - 1).getId());
            }
            if (n > 0) {
                int last = list.getChildAt(n - 1).getId();
                btnSave.setNextFocusUpId(last);
                btnReset.setNextFocusUpId(last);
            }
        };

        AlertDialog dialog = buildDialog(activity, root);
        btnReset.setOnClickListener(v -> {
            items.clear();
            items.addAll(fullDefaultOrder());
            sel[0] = 0;
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
        });
        btnSave.setOnClickListener(v -> {
            saveOrder(items);
            dialog.dismiss();
        });
        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            refresh[0].run();
            list.post(() -> {
                if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
            });
        });
        attachDismissFinish(activity, dialog);
        dialog.show();
        sizeWindow(dialog, activity, 0.72f);
    }


    private static void move(List<String> items, int[] sel, int delta, Runnable[] refresh, LinearLayout list) {
        int to = sel[0] + delta;
        if (to < 0 || to >= items.size()) return;
        String tmp = items.remove(sel[0]);
        items.add(to, tmp);
        sel[0] = to;
        int keep = sel[0];
        refresh[0].run();
        list.post(() -> {
            if (keep < list.getChildCount()) list.getChildAt(keep).requestFocus();
        });
    }

    private static List<String> mergeOrderList(List<String> saved) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (saved != null) set.addAll(saved);
        for (String d : fullDefaultOrder()) set.add(d);
        return new ArrayList<>(set);
    }

    private static List<String> readOrderPref() {
        List<String> out = new ArrayList<>();
        try {
            String raw = Prefers.getString(PREF_ORDER, "");
            if (TextUtils.isEmpty(raw)) return out;
            for (String part : raw.split(",")) {
                String s = part.trim();
                if ("迅雷会员".equals(s)) s = "迅雷智能";
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void saveOrder(List<String> items) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(items.get(i));
            }
            Prefers.put(PREF_ORDER, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static List<String> fullDefaultOrder() {
        List<String> d = new ArrayList<>();
        d.add("夸克无限");
        d.add("夸克原画");
        d.add("夸克普画");
        d.add("UC无限");
        d.add("UC原画");
        d.add("百度无限");
        d.add("百度原画");
        d.add("迅雷原画");
        d.add("迅雷智能");
        d.add("阿里原画");
        d.add("天翼原画");
        d.add("115原画");
        d.add("移动原画");
        d.add("光鸭原画");
        return d;
    }

    /** 与 jar 原界面一致：各网盘分项线程，不是全局一份 */
    private static final class ThreadItem {
        final String title;
        final String key;
        final String defVal;
        final int color;
        final boolean allowAuto;

        ThreadItem(String title, String key, String defVal, int color, boolean allowAuto) {
            this.title = title;
            this.key = key;
            this.defVal = defVal;
            this.color = color;
            this.allowAuto = allowAuto;
        }
    }

    private static List<ThreadItem> threadHubItems() {
        List<ThreadItem> list = new ArrayList<>();
        list.add(new ThreadItem("夸克(普通)", "quarkThread", "32", 0xFF4CAF50, false));
        list.add(new ThreadItem("夸克(会员)", "quarkThreadVip", "16", 0xFF4CAF50, false));
        list.add(new ThreadItem("UC网盘", "ucThread", "自动", 0xFFFF9800, true));
        list.add(new ThreadItem("百度网盘", "baiduThread", "12", 0xFFF44336, false));
        list.add(new ThreadItem("迅雷网盘", "xunleiThread", "10", 0xFF2196F3, false));
        list.add(new ThreadItem("阿里网盘", "aliThread", "64", 0xFF03A9F4, false));
        return list;
    }

    private static void openThread(Activity activity) {
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, "下载线程"));
        root.addView(sub(activity, "点击配置各网盘的下载线程数"));

        final ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        List<ThreadItem> items = threadHubItems();
        List<LinearLayout> rows = new ArrayList<>();
        AlertDialog[] hubRef = new AlertDialog[1];

        for (ThreadItem item : items) {
            String cur = readThreadVal(item.key, item.defVal);
            LinearLayout row = makeThreadHubRow(activity, item, formatThreadLabel(cur, item.allowAuto));
            row.setId(View.generateViewId());
            row.setTag(item);
            row.setOnFocusChangeListener((v, has) -> {
                applyRowFocus(activity, v, has);
                if (has) {
                    scroll.post(() -> {
                        int top = v.getTop();
                        int bottom = v.getBottom();
                        int sl = scroll.getScrollY();
                        int sh = scroll.getHeight();
                        if (top < sl) scroll.smoothScrollTo(0, top);
                        else if (bottom > sl + sh) scroll.smoothScrollTo(0, bottom - sh);
                    });
                }
            });
            row.setOnClickListener(v -> openThreadPicker(activity, item, hubRef[0], () -> {
                String nv = readThreadVal(item.key, item.defVal);
                try {
                    // box: [0]=dot View, [1]=mid LinearLayout -> [0]=name [1]=val
                    if (row.getChildCount() >= 2 && row.getChildAt(1) instanceof LinearLayout) {
                        LinearLayout mid = (LinearLayout) row.getChildAt(1);
                        if (mid.getChildCount() >= 2 && mid.getChildAt(1) instanceof TextView) {
                            ((TextView) mid.getChildAt(1)).setText(formatThreadLabel(nv, item.allowAuto) + "  ›");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }));
            rows.add(row);
            list.addView(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) rows.get(i).setNextFocusUpId(rows.get(i - 1).getId());
            if (i + 1 < rows.size()) rows.get(i).setNextFocusDownId(rows.get(i + 1).getId());
        }
        scroll.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int listH = Math.min(dp(activity, 420),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        AlertDialog dialog = buildDialog(activity, root);
        hubRef[0] = dialog;
        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            if (!rows.isEmpty()) rows.get(0).post(rows.get(0)::requestFocus);
        });
        attachDismissFinish(activity, dialog);
        dialog.show();
        sizeWindow(dialog, activity, 0.62f);
    }

    private static LinearLayout makeThreadHubRow(Activity a, ThreadItem item, String valueLabel) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        applyRowFocus(a, box, false);
        int p = dp(a, 14);
        box.setPadding(p, dp(a, 14), p, dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        box.setLayoutParams(lp);

        // color dot
        View dot = new View(a);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(item.color);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(a, 12), dp(a, 12));
        dlp.gravity = Gravity.CENTER_VERTICAL;
        dlp.rightMargin = dp(a, 12);
        box.addView(dot, dlp);

        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(a);
        name.setText(item.title);
        name.setTextColor(TEXT_MAIN);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.gravity = Gravity.CENTER_VERTICAL;
        mid.addView(name, nlp);

        TextView val = new TextView(a);
        val.setText(valueLabel + "  ›");
        val.setTextColor(TEXT_SUB);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        mid.addView(val, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(mid);
        return box;
    }

    private static void openThreadPicker(Activity activity, ThreadItem item, AlertDialog parent, Runnable onSaved) {
        LinearLayout root = column(activity);
        root.setBackgroundColor(BG_PANEL);
        root.addView(title(activity, item.title));
        root.addView(sub(activity, "上下键选择 · OK 确认"));

        String cur = readThreadVal(item.key, item.defVal);
        final String[] selected = {normalizeThread(cur, item.allowAuto)};

        // 与手机端 jar 常见档位对齐（含截图中的 10/12/16/32/64）
        List<String> opts = new ArrayList<>();
        if (item.allowAuto) opts.add("自动");
        // 与手机端常见档位对齐：16 / 32 / 64 为主，兼顾截图中的 10/12
        for (int n : new int[]{8, 10, 12, 16, 32, 64, 128}) {
            opts.add(String.valueOf(n));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        List<LinearLayout> rows = new ArrayList<>();
        AlertDialog[] dlgRef = new AlertDialog[1];

        for (String opt : opts) {
            boolean on = opt.equals(selected[0]) || (opt.equals("自动") && isAuto(selected[0]));
            LinearLayout row = makeRow(activity, (on ? "✓  " : "    ") + (opt.equals("自动") ? "自动" : opt + " 线程"), null);
            row.setTag(opt);
            row.setId(View.generateViewId());
            row.setOnFocusChangeListener((v, has) -> {
                if (has) selected[0] = (String) v.getTag();
                applyRowFocus(activity, v, has);
                // update checkmarks
                for (LinearLayout r : rows) {
                    String o = (String) r.getTag();
                    boolean sel = o.equals(selected[0]);
                    if (r.getChildCount() > 0 && r.getChildAt(0) instanceof TextView) {
                        ((TextView) r.getChildAt(0)).setText((sel ? "✓  " : "    ")
                                + (o.equals("自动") ? "自动" : o + " 线程"));
                    }
                }
            });
            row.setOnClickListener(v -> {
                selected[0] = (String) v.getTag();
                writeThreadVal(item.key, selected[0]);
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                if (onSaved != null) onSaved.run();
            });
            rows.add(row);
            list.addView(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) rows.get(i).setNextFocusUpId(rows.get(i - 1).getId());
            if (i + 1 < rows.size()) rows.get(i).setNextFocusDownId(rows.get(i + 1).getId());
        }
        scroll.addView(list);
        int listH = Math.min(dp(activity, 380),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5f));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        AlertDialog dialog = buildDialog(activity, root);
        dlgRef[0] = dialog;
        // 子窗不 finish Activity
        dialog.setOnShowListener(d -> {
            styleWindow(dialog);
            for (LinearLayout row : rows) {
                String o = (String) row.getTag();
                if (o.equals(selected[0]) || (o.equals("自动") && isAuto(selected[0]))) {
                    row.post(row::requestFocus);
                    break;
                }
            }
        });
        dialog.show();
        sizeWindow(dialog, activity, 0.5f);
    }

    private static String readThreadVal(String key, String def) {
        try {
            String s = Prefers.getString(key, "");
            if (!TextUtils.isEmpty(s)) return s.trim();
            int i = Prefers.getInt(key, -1);
            if (i > 0) return String.valueOf(i);
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static void writeThreadVal(String key, String val) {
        try {
            if ("自动".equals(val) || "auto".equalsIgnoreCase(val) || "0".equals(val)) {
                Prefers.put(key, "0");
            } else {
                Prefers.put(key, val);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String formatThreadLabel(String raw, boolean allowAuto) {
        if (isAuto(raw)) return "自动";
        try {
            return Integer.parseInt(raw.trim()) + "线程";
        } catch (Throwable e) {
            return TextUtils.isEmpty(raw) ? (allowAuto ? "自动" : raw) : raw;
        }
    }

    private static boolean isAuto(String raw) {
        if (TextUtils.isEmpty(raw)) return true;
        String s = raw.trim();
        return "自动".equals(s) || "auto".equalsIgnoreCase(s) || "0".equals(s);
    }

    private static String normalizeThread(String raw, boolean allowAuto) {
        if (allowAuto && isAuto(raw)) return "自动";
        try {
            return String.valueOf(Integer.parseInt(raw.trim()));
        } catch (Throwable e) {
            return allowAuto ? "自动" : "16";
        }
    }

    // -------------------- UI helpers --------------------

    private static AlertDialog buildDialog(Activity activity, View root) {
        return new MaterialAlertDialogBuilder(activity)
                .setView(root)
                .setCancelable(true)
                .create();
    }

    private static void styleWindow(AlertDialog dialog) {
        try {
            if (dialog.getWindow() != null) {
                dialog.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(BG_PANEL));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sizeWindow(AlertDialog dialog, Activity activity, float widthFraction) {
        try {
            int w = (int) (activity.getResources().getDisplayMetrics().widthPixels * widthFraction);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable ignored) {
        }
    }

    private static void attachDismissFinish(Activity activity, AlertDialog dialog) {
        dialog.setOnDismissListener(d -> {
            sOpening = false;
            try {
                if (!activity.isFinishing()
                        && activity.getClass().getName().contains("VideoActivity")) {
                    activity.finish();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void applyRowFocus(Activity a, View v, boolean has) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(a, 10));
        if (has) {
            g.setColor(0xFF64B5F6);
            g.setStroke(dp(a, 4), 0xFF0D47A1);
        } else {
            g.setColor(BG_ROW);
            g.setStroke(dp(a, 1), 0xFF90CAF9);
        }
        v.setBackground(g);
        try { v.setElevation(has ? dp(a, 8) : 0); } catch (Throwable ignored) {}
    }

    private static LinearLayout makeRow(Activity a, String title, String sub) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        applyRowFocus(a, box, false);
        int p = dp(a, 14);
        box.setPadding(p, dp(a, 12), p, dp(a, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(a, 8);
        box.setLayoutParams(lp);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(null, Typeface.BOLD);
        box.addView(t);
        TextView s = new TextView(a);
        s.setText(sub == null ? "" : sub);
        s.setTextColor(TEXT_SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setPadding(0, dp(a, 4), 0, 0);
        box.addView(s);
        return box;
    }

    private static TextView barBtn(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_MAIN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setGravity(Gravity.CENTER);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setClickable(true);
        int h = dp(a, 44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, 1f);
        lp.setMargins(dp(a, 4), dp(a, 8), dp(a, 4), 0);
        tv.setLayoutParams(lp);
        applyRowFocus(a, tv, false);
        tv.setOnFocusChangeListener((v, has) -> applyRowFocus(a, v, has));
        return tv;
    }

    private static LinearLayout column(Activity a) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        root.setPadding(p, p, p, p);
        root.setBackgroundColor(BG_PANEL);
        return root;
    }

    private static TextView title(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_MAIN);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private static TextView sub(Activity a, String text) {
        TextView tv = new TextView(a);
        tv.setText(text);
        tv.setTextColor(TEXT_SUB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setPadding(0, dp(a, 4), 0, dp(a, 12));
        return tv;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
