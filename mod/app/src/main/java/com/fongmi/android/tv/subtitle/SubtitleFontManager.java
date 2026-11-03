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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 内置字幕字体：yiqicukai / huipianheyuan / fangzhengzhunyuan / system / custom。
 * Mobile / Leanback / Exo 共用。
 * 默认 index 0 = 义启粗楷；index 3 = 系统默认；index 4 = 自定义外部字体。
 * MPV-BRANCH-ONLY: index 0~3 的语义与旧版本完全一致，index 4 为本次新增（向后兼容）。
 */
public final class SubtitleFontManager {

    private static final String TAG = "SubtitleFont";
    public static final String ID_YIQICUKAI = "yiqicukai";
    public static final String ID_HUIPIANHEYUAN = "huipianheyuan";
    public static final String ID_FANGZHENGZHUNYUAN = "fangzhengzhunyuan";
    public static final String ID_SYSTEM = "system";
    // MPV-BRANCH-ONLY: 自定义外部字体（不弹文件选择器，只识别既有字体文件）
    public static final String ID_CUSTOM = "custom";

    // 0 义启粗楷(默认), 1 毁片和圆, 2 方正细圆, 3 系统默认, 4 自定义外部字体
    private static final String[] IDS = {ID_YIQICUKAI, ID_HUIPIANHEYUAN, ID_FANGZHENGZHUNYUAN, ID_SYSTEM, ID_CUSTOM};
    private static final String[] LABELS_ZH = {"义启粗楷", "毁片和圆", "方正细圆", "系统默认", "自定义外部字体"};
    private static final String[] LABELS_EN = {"YiQiCuKai", "HuiPianHeYuan", "FangZhengZhunYuan", "System", "Custom font"};
    // MPV-BRANCH-ONLY: 内置字体在 assets/fonts/ 下的文件名（与 IDS 顺序一致，system 无文件）
    private static final String[] ASSET_FILES = {"yiqicukai.ttf", "huipianheyuan.ttf", "fangzhengzhunyuan.ttf", "", "custom.ttf"};
    public static final int CUSTOM_INDEX = 4;
    private static final String TAG_BUILTIN = "MpvSubtitleBuiltin";
    private static final String CUSTOM_FILE_BASE = "custom";

    private static final ConcurrentHashMap<String, Typeface> TYPEFACE_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, File> FILE_CACHE = new ConcurrentHashMap<>();
    // MPV-BRANCH-ONLY: 字体文件读写共用锁（与 MpvSubtitleStylePolicy 共用），避免并发复制读到 0B 半成品
    public static final Object FONT_IO_LOCK = new Object();

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
        // MPV-BRANCH-ONLY: 自定义外部字体回显文件名（便于确认当前生效的字体）
        if (i == CUSTOM_INDEX) {
            String name = customFontName();
            if (name == null || name.isEmpty()) return zh ? LABELS_ZH[i] : LABELS_EN[i];
            return (zh ? "自定义：" : "Custom: ") + name;
        }
        return zh ? LABELS_ZH[i] : LABELS_EN[i];
    }

    /** ASS FontName / 显示用；系统默认与自定义外部字体返回 sans-serif。 */
    public static String displayNameOf(int index) {
        int i = normalizeIndex(index);
        if (i == 3 || i == CUSTOM_INDEX) return "sans-serif";
        return labelOf(i, true);
    }

    /** MPV-BRANCH-ONLY: 内置字体对应的 assets 文件名（system/custom 返回空或 custom.ttf）。 */
    public static String assetFileOf(int index) {
        int i = normalizeIndex(index);
        return ASSET_FILES[i];
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
        // MPV-BRANCH-ONLY: 自定义外部字体缺失时回退系统字体，避免误显示成内置字体
        if (ID_CUSTOM.equals(id)) {
            Typeface tf = loadTypeface(id);
            if (tf == null) tf = Typeface.SANS_SERIF;
            TYPEFACE_CACHE.put(id, tf);
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
        // MPV-BRANCH-ONLY: 自定义外部字体走独立探测（兼容旧外部字体文件）
        if (ID_CUSTOM.equals(id)) {
            File custom = customFontFile();
            if (custom != null && custom.isFile()) FILE_CACHE.put(id, custom);
            return custom;
        }
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
            // MPV-BRANCH-ONLY: 自定义外部字体未就绪时不要预热，避免把 SANS_SERIF 缓存住
            if (ID_CUSTOM.equals(id) && !hasCustomFont()) continue;
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
        // MPV-BRANCH-ONLY: 自定义外部字体只识别既有文件，绝不弹文件选择器
        if (ID_CUSTOM.equals(id)) return customFontFile();
        // MPV-BRANCH-ONLY: 与 MpvSubtitleStylePolicy 共用锁，避免并发复制互相读到 0B 半成品
        synchronized (FONT_IO_LOCK) {
            return ensureFontFileLocked(id);
        }
    }

    private static File ensureFontFileLocked(String id) {
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

    // MPV-BRANCH-ONLY BEGIN —— 内置字体后台加载 + 自定义外部字体探测（不弹文件选择器）
    private static final ExecutorService BUILTIN_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "subtitle-builtin-font");
            t.setDaemon(true);
            return t;
        }
    });

    private static boolean isFontFileName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    private static boolean isBuiltinFontFileName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String asset : ASSET_FILES) {
            if (asset == null || asset.isEmpty()) continue;
            if (n.equals(asset.toLowerCase(Locale.ROOT))) return true;
        }
        for (String id : IDS) {
            if (ID_SYSTEM.equals(id) || ID_CUSTOM.equals(id)) continue;
            if (n.equals(id + ".ttf") || n.equals(id + ".otf") || n.equals(id + ".ttc")) return true;
        }
        return false;
    }

    /**
     * 自定义外部字体文件：优先 custom.ttf/otf/ttc；
     * 其次回退读取旧版本遗留在 subtitle_fonts/ 下的其他字体文件（向后兼容，无需重新选择）。
     */
    public static File customFontFile() {
        try {
            File dir = getFontsDir();
            if (dir == null) return null;
            for (String ext : new String[]{".ttf", ".otf", ".ttc"}) {
                File f = new File(dir, CUSTOM_FILE_BASE + ext);
                if (f.isFile() && f.length() > 1000) return f;
            }
            File[] list = dir.listFiles();
            if (list != null) {
                java.util.Arrays.sort(list, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File f : list) {
                    if (f == null || !f.isFile() || f.length() <= 1000) continue;
                    if (!isFontFileName(f.getName())) continue;
                    if (isBuiltinFontFileName(f.getName())) continue;
                    if ("subfont".equalsIgnoreCase(stripExt(f.getName()))) continue;
                    return f;
                }
            }
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN, "customFontFile: " + e.getMessage());
        }
        return null;
    }

    private static String stripExt(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : (name == null ? "" : name);
    }

    /** 自定义外部字体是否可用（用于个性设置页回显）。 */
    public static boolean hasCustomFont() {
        File f = customFontFile();
        return f != null && f.isFile();
    }

    /** 自定义外部字体的显示名：文件名；缺失时返回空串。 */
    public static String customFontName() {
        File f = customFontFile();
        return f == null ? "" : f.getName();
    }

    /**
     * 后台静默加载内置字体（MPV-BRANCH-ONLY）。
     * - 全程子线程单线程池执行，避免多次选择并发拷贝同一个 ttf；
     * - 不弹文件选择器、不 toast、不新增 Setting 字段、不改数据库；
     * - 完成后回主线程通知 MPV 运行时刷新（播放中切换立即生效）。
     */
    public static void loadBuiltinFont(final int index) {
        final int idx = normalizeIndex(index);
        final String id = idOf(idx);
        final long start = System.currentTimeMillis();
        com.fongmi.android.tv.utils.ModDebugLog.i(TAG_BUILTIN,
                "request index=" + idx + " id=" + id + " source=" + (ID_CUSTOM.equals(id) ? "external" : ID_SYSTEM.equals(id) ? "system" : "assets/fonts") + " silent=true");
        try {
            BUILTIN_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    File file = null;
                    String where = "none";
                    try {
                        if (ID_SYSTEM.equals(id)) {
                            TYPEFACE_CACHE.put(id, Typeface.SANS_SERIF);
                            FILE_CACHE.remove(id);
                            com.fongmi.android.tv.utils.ModDebugLog.i(TAG_BUILTIN,
                                    "ok index=" + idx + " id=" + id + " typeface=sans-serif cost=" + (System.currentTimeMillis() - start) + "ms");
                        } else {
                            file = ensureFontFile(id);
                            if (file != null && file.isFile()) where = file.getAbsolutePath();
                            Typeface tf = null;
                            if (file != null && file.isFile()) {
                                try {
                                    tf = Typeface.createFromFile(file);
                                } catch (Throwable e) {
                                    com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN, "createFromFile failed id=" + id + " err=" + e.getMessage());
                                }
                            }
                            if (tf == null && !ID_CUSTOM.equals(id)) tf = loadTypeface(id);
                            if (tf == null) tf = Typeface.SANS_SERIF;
                            TYPEFACE_CACHE.put(id, tf);
                            if (file != null && file.isFile()) FILE_CACHE.put(id, file);
                            com.fongmi.android.tv.utils.ModDebugLog.i(TAG_BUILTIN,
                                    "ok index=" + idx + " id=" + id + " file=" + where
                                            + " bytes=" + (file == null ? 0L : file.length())
                                            + " cacheDir=" + (file == null ? "none" : String.valueOf(file.getParent()))
                                            + " cost=" + (System.currentTimeMillis() - start) + "ms");
                        }
                    } catch (Throwable e) {
                        com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN,
                                "fail index=" + idx + " id=" + id + " err=" + e);
                    }
                    notifyMpvRuntime(index);
                }
            });
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN, "submit fail index=" + idx + " err=" + e);
            notifyMpvRuntime(index);
        }
    }

    /** 回主线程通知 MPV 运行时刷新（native 属性调用必须留在主线程）。 */
    private static void notifyMpvRuntime(final int index) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    boolean applied = false;
                    try {
                        applied = com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
                    } catch (Throwable e) {
                        com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN, "runtime apply err=" + e);
                    }
                    com.fongmi.android.tv.utils.ModDebugLog.i(TAG_BUILTIN,
                            "runtime applied=" + applied + " index=" + index);
                }
            });
        } catch (Throwable e) {
            com.fongmi.android.tv.utils.ModDebugLog.w(TAG_BUILTIN, "runtime post err=" + e);
        }
    }
    // MPV-BRANCH-ONLY END
}
