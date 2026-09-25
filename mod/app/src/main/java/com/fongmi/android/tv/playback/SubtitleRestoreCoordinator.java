package com.fongmi.android.tv.playback;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Sub;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

/**
 * 对齐 Silent docs/SUB-EXT-HISTORY-external-subtitle-restore.md：
 * <ul>
 *   <li>写入：所有外挂最终都走 PlayerManager.setSub → onUserSetSub</li>
 *   <li>恢复：setMediaItem 之前把 Sub 注入 PlaySpec.subs（不走 setSub 重启分支）</li>
 *   <li>选中：同时 priming Assrt 的 preferExternal / pendingName，由 onTracksReady 强制 Override</li>
 * </ul>
 * 存储用 Prefers（避免 Room 迁移风险），文件复制到 filesDir/sub_remember。
 */
public final class SubtitleRestoreCoordinator {

    private static final String TAG = "SubRestore";
    private static final String PREFIX = "ext_sub_src_";

    private static volatile History sBoundHistory;
    private static volatile Sub sPendingRestore;
    private static volatile String sPendingName;
    private static volatile String sPendingFormat;

    private SubtitleRestoreCoordinator() {
    }

    public static void bindHistory(History history) {
        sBoundHistory = history;
    }

    public static void clearBind() {
        sBoundHistory = null;
    }

    public static Sub peekPending() {
        return sPendingRestore;
    }

    public static String peekPendingName() {
        return sPendingName;
    }

    public static String peekPendingFormat() {
        return sPendingFormat;
    }

    /**
     * PlayerManager.setSub 唯一收口：记住当前外挂。
     */
    public static void onUserSetSub(Sub sub) {
        try {
            if (sub == null || TextUtils.isEmpty(sub.getUrl())) return;
            History h = sBoundHistory;
            if (h == null) return;
            try {
                Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
                Object incognito = setting.getMethod("isIncognito").invoke(null);
                if (incognito instanceof Boolean && (Boolean) incognito) return;
            } catch (Throwable ignored) {
            }
            Sub durable = ensureDurable(sub);
            String episodeUrl = safe(h.getEpisodeUrl());
            SubtitleSource source = SubtitleSource.of(durable, episodeUrl);
            if (source == null) return;
            String json = SubtitleSource.encode(source);
            putCommit(cacheKey(h.getKey(), episodeUrl), json);
            putCommit(cacheKey(h.getKey(), ""), json);
            try {
                String vod = safe(h.getVodName());
                String remarks = safe(h.getVodRemarks());
                if (!TextUtils.isEmpty(vod)) putCommit(cacheKey("vod:" + vod, remarks), json);
            } catch (Throwable ignored) {
            }
            // 同步 Assrt 侧文件缓存，保证 attachRememberedSub / tryRestore 也能命中
            try {
                Class<?> assrt = Class.forName("com.fongmi.android.tv.subtitle.AssrtSubtitleMatch");
                Method m = assrt.getMethod("rememberSubFromCoordinator",
                        History.class, String.class, String.class, String.class, String.class);
                m.invoke(null, h, durable.getUrl(), durable.getName(), durable.getLang(), durable.getFormat());
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "remember setSub name=" + durable.getName() + " ep=" + episodeUrl);
        } catch (Throwable e) {
            Log.w(TAG, "onUserSetSub: " + e.getMessage());
        }
    }

    /**
     * 起播前由 VideoActivity 调用：从 Prefers 装载并登记 pending。
     * 同时 priming Assrt 选轨状态，避免只进列表不选中。
     */
    public static void prepareRestore(History history) {
        sPendingRestore = null;
        sPendingName = null;
        sPendingFormat = null;
        if (history == null) return;
        try {
            Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
            Object incognito = setting.getMethod("isIncognito").invoke(null);
            if (incognito instanceof Boolean && (Boolean) incognito) return;
        } catch (Throwable ignored) {
        }
        String episodeUrl = safe(history.getEpisodeUrl());
        SubtitleSource source = load(history.getKey(), episodeUrl);
        if (source == null) source = load(history.getKey(), "");
        if (source == null) {
            try {
                source = load("vod:" + safe(history.getVodName()), safe(history.getVodRemarks()));
            } catch (Throwable ignored) {
            }
        }
        SubtitleRestorePolicy.Decision d = SubtitleRestorePolicy.decide(source, episodeUrl, false);
        if (d.clear()) {
            clear(history.getKey(), episodeUrl);
            clear(history.getKey(), "");
            Log.i(TAG, "prepareRestore clear reason=" + d.reason());
            return;
        }
        if (!d.restore() || source == null) {
            Log.i(TAG, "prepareRestore skip reason=" + (d.reason() != null ? d.reason() : "null"));
            return;
        }
        Sub sub = source.toSub();
        if (sub == null) return;
        // 本地文件再确认一次存在；不存在则 drop
        if (!source.isRemote()) {
            File f = new File(source.getUrl());
            if (!f.isFile()) {
                clear(history.getKey(), episodeUrl);
                clear(history.getKey(), "");
                Log.i(TAG, "prepareRestore file missing, cleared");
                return;
            }
        }
        sPendingRestore = sub;
        sPendingName = sub.getName();
        sPendingFormat = sub.getFormat();
        // 关键 Assrt 状态：历史重进后 onTracksReady 才能强制选外挂
        try {
            Class<?> assrt = Class.forName("com.fongmi.android.tv.subtitle.AssrtSubtitleMatch");
            Method prime = assrt.getMethod("primeExternalPreference", String.class, String.class);
            prime.invoke(null, sub.getName(), sub.getFormat());
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "prepareRestore pending name=" + sub.getName() + " url=" + sub.getUrl());
    }

    /**
     * 在 setMediaItem / prepareMpvOutputForNewItem / start 开头调用。
     * 把 pending 注入当前 PlaySpec；注入成功才消费 pending（失败保留，便于重试）。
     */
    public static void injectPendingIntoPlayerManager(Object playerManager) {
        Sub sub = sPendingRestore;
        if (sub == null || playerManager == null) return;
        try {
            Field specField = findField(playerManager.getClass(), "spec");
            if (specField == null) {
                Log.w(TAG, "no spec field");
                return;
            }
            specField.setAccessible(true);
            Object spec = specField.get(playerManager);
            if (spec == null) {
                Log.w(TAG, "spec is null, cannot inject yet");
                return;
            }
            Method setSub = null;
            for (Method m : spec.getClass().getMethods()) {
                if ("setSub".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    setSub = m;
                    break;
                }
            }
            if (setSub == null) {
                Log.w(TAG, "PlaySpec.setSub missing");
                return;
            }
            setSub.invoke(spec, sub);
            // 成功才消费，避免 prepareMpv 早退导致丢失
            sPendingRestore = null;
            Log.i(TAG, "injected into PlaySpec name=" + sub.getName());
        } catch (Throwable e) {
            Log.w(TAG, "inject failed: " + e.getMessage());
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Sub ensureDurable(Sub sub) {
        try {
            String url = sub.getUrl();
            if (TextUtils.isEmpty(url) || url.contains("://")) return sub;
            File src = new File(url);
            if (!src.isFile()) return sub;
            File dir = new File(Init.context().getFilesDir(), "sub_remember");
            if (!dir.exists() && !dir.mkdirs()) return sub;
            File dst = new File(dir, md5(url) + "_" + src.getName());
            if (!dst.isFile() || dst.length() != src.length()) {
                try (FileChannel in = new FileInputStream(src).getChannel();
                     FileChannel out = new FileOutputStream(dst).getChannel()) {
                    out.transferFrom(in, 0, in.size());
                }
            }
            if (!dst.isFile()) return sub;
            Sub out = Sub.create(sub.getName(), dst.getAbsolutePath(), sub.getLang(), sub.getFormat());
            try {
                out.setFlag(androidx.media3.common.C.SELECTION_FLAG_DEFAULT
                        | androidx.media3.common.C.SELECTION_FLAG_FORCED);
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable e) {
            return sub;
        }
    }

    private static void putCommit(String key, String value) {
        try {
            Prefers.getPrefers().edit().putString(key, value == null ? "" : value).commit();
        } catch (Throwable e) {
            Prefers.put(key, value);
        }
    }

    private static SubtitleSource load(String historyKey, String episodeUrl) {
        return SubtitleSource.decode(Prefers.getString(cacheKey(historyKey, episodeUrl)));
    }

    private static void clear(String historyKey, String episodeUrl) {
        putCommit(cacheKey(historyKey, episodeUrl), "");
    }

    private static String cacheKey(String historyKey, String episodeUrl) {
        return PREFIX + md5((historyKey == null ? "" : historyKey) + "\u0001" + (episodeUrl == null ? "" : episodeUrl));
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest((input == null ? "" : input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            return Integer.toHexString((input == null ? "" : input).hashCode());
        }
    }

    public static boolean remember(History history, Sub sub) {
        History prev = sBoundHistory;
        if (history != null) sBoundHistory = history;
        try {
            onUserSetSub(sub);
            return true;
        } finally {
            if (history != null) sBoundHistory = prev != null ? prev : history;
        }
    }

    public static boolean remember(String historyKey, String episodeUrl, Sub sub) {
        if (sub == null) return false;
        onUserSetSub(sub);
        return true;
    }

    public static Sub restore(History history, Object player, Object result) {
        prepareRestore(history);
        if (player != null) injectPendingIntoPlayerManager(player);
        return sPendingRestore;
    }
}
