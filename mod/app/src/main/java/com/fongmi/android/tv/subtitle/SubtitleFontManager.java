package com.fongmi.android.tv.subtitle;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import com.fongmi.android.tv.App;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置字幕字体：yiqicukai / huipianheyuan / fangzhengzhunyuan / system。
 * Mobile / Leanback / Exo 共用。
 * 默认 index 0 = 义启粗楷；index 3 = 系统默认。
 */
public final class SubtitleFontManager {

    private static final String TAG = "SubtitleFont";
    public static final String ID_YIQICUKAI = "yiqicukai";
    public static final String ID_HUIPIANHEYUAN = "huipianheyuan";
    public static final String ID_FANGZHENGZHUNYUAN = "fangzhengzhunyuan";
    public static final String ID_SYSTEM = "system";

    // 0 义启粗楷(默认), 1 毁片和圆, 2 方正细圆, 3 系统默认
    private static final String[] IDS = {ID_YIQICUKAI, ID_HUIPIANHEYUAN, ID_FANGZHENGZHUNYUAN, ID_SYSTEM};
    private static final String[] LABELS_ZH = {"义启粗楷", "毁片和圆", "方正细圆", "系统默认"};
    private static final String[] LABELS_EN = {"YiQiCuKai", "HuiPianHeYuan", "FangZhengZhunYuan", "System"};

    private static final ConcurrentHashMap<String, Typeface> TYPEFACE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> FILE_CACHE = new ConcurrentHashMap<>();

    private SubtitleFontManager() {
    }

    public static int count() {
        return IDS.length;
    }

    public static String idOf(int index) {
        return IDS[normalizeIndex(index)];
    }

    public static int indexOfId(String id) {
        if (id == null) return 0;
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(s)) return i;
        }
        if (s.contains("yiqi") || s.contains("cukai") || s.contains("义启") || s.contains("粗楷")) return 0;
        if (s.contains("huipian") || s.contains("毁片") || s.contains("heyuan")) return 1;
        if (s.contains("fangzheng") || s.contains("zhunyuan") || s.contains("细圆") || s.contains("方正")
                || s.contains("xiyuan")) return 2;
        if (s.contains("system") || s.contains("default") || s.contains("sans")
                || "系统".equals(s) || s.contains("系统默认")) return 3;
        return 0;
    }

    public static int normalizeIndex(int index) {
        if (index < 0 || index >= IDS.length) return 0;
        return index;
    }

    public static String labelOf(int index, boolean zh) {
        int i = normalizeIndex(index);
        return zh ? LABELS_ZH[i] : LABELS_EN[i];
    }

    /** ASS FontName / 显示用；系统默认返回 sans-serif。 */
    public static String displayNameOf(int index) {
        if (normalizeIndex(index) == 3) return "sans-serif";
        return labelOf(index, true);
    }

    public static Typeface getTypeface(int index) {
        String id = idOf(index);
        Typeface cached = TYPEFACE_CACHE.get(id);
        if (cached != null) return cached;
        if (ID_SYSTEM.equals(id)) {
            Typeface tf = Typeface.SANS_SERIF;
            TYPEFACE_CACHE.put(id, tf);
            /* log silenced */
            return tf;
        }
        Typeface tf = loadTypeface(id);
        if (tf == null && !ID_YIQICUKAI.equals(id)) {
            tf = loadTypeface(ID_YIQICUKAI);
        }
        if (tf == null) tf = Typeface.SANS_SERIF;
        TYPEFACE_CACHE.put(id, tf);
        return tf;
    }

    public static File getFontFile(int index) {
        String id = idOf(index);
        if (ID_SYSTEM.equals(id)) return null;
        File cached = FILE_CACHE.get(id);
        if (cached != null && cached.isFile()) return cached;
        File f = ensureFontFile(id);
        if (f == null && !ID_YIQICUKAI.equals(id)) f = ensureFontFile(ID_YIQICUKAI);
        if (f != null) FILE_CACHE.put(id, f);
        return f;
    }

    public static void prepareAllFonts() {
        for (String id : IDS) {
            if (ID_SYSTEM.equals(id)) continue;
            try {
                ensureFontFile(id);
                getTypeface(indexOfId(id));
            } catch (Throwable e) {
                com.fongmi.android.tv.utils.ModDebugLog.w(TAG, "prepare " + id + ": " + e.getMessage());
            }
        }
    }

    public static void registerAssFontAliases(Object registry) {
        if (registry == null) return;
        String[][] pairs = new String[][]{
                {ID_YIQICUKAI, "义启粗楷"},
                {ID_YIQICUKAI, "YiQiCuKai"},
                {ID_HUIPIANHEYUAN, "毁片和圆"},
                {ID_HUIPIANHEYUAN, "HuiPianHeYuan"},
                {ID_FANGZHENGZHUNYUAN, "方正细圆"},
                {ID_FANGZHENGZHUNYUAN, "FangZhengZhunYuan"},
        };
        try {
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method method : registry.getClass().getMethods()) {
                if ("addFontPath".equals(method.getName()) || "registerFont".equals(method.getName())) {
                    m = method;
                    break;
                }
            }
            if (m == null) return;
            for (String[] pair : pairs) {
                File f = ensureFontFile(pair[0]);
                if (f == null || !f.isFile()) continue;
                try {
                    if (m.getParameterTypes().length == 2) m.invoke(registry, pair[1], f.getAbsolutePath());
                    else if (m.getParameterTypes().length == 1) m.invoke(registry, f.getAbsolutePath());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG, "registerAssFontAliases: " + e.getMessage());
        }
    }

    private static Typeface loadTypeface(String id) {
        if (ID_SYSTEM.equals(id)) return Typeface.SANS_SERIF;
        try {
            Context ctx = App.get();
            if (ctx == null) return null;
            for (String assetPath : assetCandidates(id)) {
                try {
                    Typeface tf = Typeface.createFromAsset(ctx.getAssets(), assetPath);
                    if (tf != null) {
                        /* log silenced */
                        return tf;
                    }
                } catch (Throwable ignored) {
                }
            }
            File f = ensureFontFile(id);
            if (f != null && f.isFile()) {
                Typeface tf = Typeface.createFromFile(f);
                if (tf != null) {
                    /* log silenced */
                    return tf;
                }
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG, "loadTypeface " + id + ": " + e.getMessage());
        }
        return null;
    }

    private static String[] assetCandidates(String id) {
        return new String[]{
                "fonts/" + id + ".ttf",
                "fonts/" + id + ".otf",
                "fonts/" + id + ".TTC",
                "fonts/" + id + ".ttc",
                "fonts/" + id,
                id + ".ttf",
                id + ".otf",
                id
        };
    }

    private static File ensureFontFile(String id) {
        if (ID_SYSTEM.equals(id)) return null;
        try {
            File dir = getFontsDir();
            if (dir == null) return null;
            for (String ext : new String[]{".ttf", ".otf", ".ttc", ""}) {
                File existing = new File(dir, id + ext);
                if (existing.isFile() && existing.length() > 1000) return existing;
            }
            Context ctx = App.get();
            if (ctx == null) return null;
            for (String assetPath : assetCandidates(id)) {
                try (InputStream in = ctx.getAssets().open(assetPath)) {
                    String name = assetPath.contains(".") ? new File(assetPath).getName() : id + ".ttf";
                    if (!name.contains(".")) name = id + ".ttf";
                    File out = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    if (out.isFile() && out.length() > 1000) {
                        /* log silenced */
                        return out;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG, "ensureFontFile " + id + ": " + e.getMessage());
        }
        return null;
    }

    public static File getFontsDir() {
        try {
            Context ctx = App.get();
            if (ctx == null) return null;
            File dir = new File(ctx.getFilesDir(), "subtitle_fonts");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable e) {
            return null;
        }
    }
}
