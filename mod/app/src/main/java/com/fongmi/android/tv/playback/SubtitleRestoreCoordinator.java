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
 * 对齐 Silent SUB-EXT-HISTORY：
 * <ul>
 *   <li>写入：PlayerManager.setSub → onUserSetSub（记文件路径，复制到 filesDir/sub_remember）</li>
 *   <li>恢复：VideoActivity.setPlayer → prepareRestore；setMediaItem 前 injectPending 写入 PlaySpec.subs</li>
 * </ul>
 * 存储用 Prefers JSON，避免 History Room 迁移。
 */
public final class SubtitleRestoreCoordinator {

    private static final String TAG = "SubRestore";
    private static final String PREFIX = "ext_sub_src_";

    private static volatile History sBoundHistory;
    private static volatile Sub sPendingRestore;

    private SubtitleRestoreCoordinator() {
    }

    static {
        try {
            android.util.Log.e("SubRestore", "SubtitleRestoreCoordinator class loaded");
            android.util.Log.e("AssrtSub", "SubtitleRestoreCoordinator class loaded");
        } catch (Throwable ignored) {}
    }

    public static void bindHistory(History history) {
        sBoundHistory = history;
    }

    public static void clearBind() {
        sBoundHistory = null;
        sPendingRestore = null;
    }

    /** PlayerManager.setSub 唯一写入收口 */
    public static void onUserSetSub(Sub sub) {
        try {
            if (sub == null || TextUtils.isEmpty(sub.getUrl())) {
                Log.i(TAG, "onUserSetSub skip null/empty sub");
                return;
            }
            History h = sBoundHistory;
            if (h == null) {
                // 仍记一份全局键，避免 bindHistory 未跑时完全无日志/无记忆
                Log.w(TAG, "onUserSetSub no bound history, use fallback key name=" + sub.getName());
                Sub durable = ensureDurable(sub);
                SubtitleSource source = SubtitleSource.of(durable, "");
                if (source != null) {
                    putCommit(cacheKey("last", ""), SubtitleSource.encode(source));
                }
                try {
                    Class<?> assrt = Class.forName("com.fongmi.android.tv.subtitle.AssrtSubtitleMatch");
                    assrt.getMethod("rememberSubFromCoordinator", History.class, String.class, String.class, String.class, String.class)
                            .invoke(null, null, durable.getUrl(), durable.getName(), durable.getLang(), durable.getFormat());
                } catch (Throwable ignored) {}
                return;
            }
            try {
                Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
                Object incognito = setting.getMethod("isIncognito").invoke(null);
                if (incognito instanceof Boolean && (Boolean) incognito) return;
            } catch (Throwable ignored) {
            }
            Sub durable = ensureDurable(sub);
            // 稳定集标识：优先备注/集名，避免把可变播放 URL 写进 episodeUrl
            String episodeUrl = stableEpisodeId(h);
            SubtitleSource source = SubtitleSource.of(durable, episodeUrl);
            if (source == null) return;
            String json = SubtitleSource.encode(source);
            putCommit(cacheKey(h.getKey(), episodeUrl), json);
            putCommit(cacheKey(h.getKey(), ""), json);
            // 兼容旧逻辑：若 History 仍带 play URL，也写一份（恢复时会被 url-rotated 放过）
            try {
                String play = safe(h.getEpisodeUrl());
                if (!play.isEmpty() && !play.equals(episodeUrl)) {
                    putCommit(cacheKey(h.getKey(), play), json);
                }
            } catch (Throwable ignored) {}
            try {
                String vod = safe(h.getVodName());
                String remarks = safe(h.getVodRemarks());
                if (!TextUtils.isEmpty(vod)) putCommit(cacheKey("vod:" + vod, remarks), json);
            } catch (Throwable ignored) {
            }
            // 同步 Assrt 文件缓存 + 选中偏好
            try {
                Class<?> assrt = Class.forName("com.fongmi.android.tv.subtitle.AssrtSubtitleMatch");
                assrt.getMethod("rememberSubFromCoordinator", History.class, String.class, String.class, String.class, String.class)
                        .invoke(null, h, durable.getUrl(), durable.getName(), durable.getLang(), durable.getFormat());
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "remember setSub name=" + durable.getName() + " ep=" + episodeUrl);
            markAction("remember", durable.getName() + " ep=" + episodeUrl);
        } catch (Throwable e) {
            Log.w(TAG, "onUserSetSub: " + e.getMessage());
        }
    }

    /** 起播前由 VideoActivity 调用：装载并登记 pending */
    public static void prepareRestore(History history) {
        if (history != null) sBoundHistory = history;
        sPendingRestore = null;
        if (history == null) return;
        try {
            Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
            Object incognito = setting.getMethod("isIncognito").invoke(null);
            if (incognito instanceof Boolean && (Boolean) incognito) return;
        } catch (Throwable ignored) {
        }
        String episodeUrl = stableEpisodeId(history);
        String playUrl = safe(history.getEpisodeUrl());
        SubtitleSource source = load(history.getKey(), episodeUrl);
        if (source == null) source = load(history.getKey(), "");
        if (source == null && !playUrl.isEmpty()) source = load(history.getKey(), playUrl);
        if (source == null) {
            try {
                source = load("vod:" + safe(history.getVodName()), safe(history.getVodRemarks()));
            } catch (Throwable ignored) {
            }
        }
        if (source == null) source = load("last", "");
        SubtitleRestorePolicy.Decision d = SubtitleRestorePolicy.decide(source, episodeUrl, false);
        // 播放 URL 变化时 policy 已放行；若仍 skip episode-changed，同 key 强制恢复
        if (!d.restore() && "episode-changed".equals(d.reason()) && source != null && source.isUsable()) {
            d = SubtitleRestorePolicy.Decision.inject("episode-changed-override");
        }
        if (d.clear()) {
            clear(history.getKey(), episodeUrl);
            clear(history.getKey(), "");
            Log.i(TAG, "prepareRestore clear reason=" + d.reason());
            return;
        }
        if (!d.restore() || source == null) {
            Log.i(TAG, "prepareRestore skip reason=" + (d.reason() != null ? d.reason() : "null"));
            markAction("prepareSkip", d.reason());
            return;
        }
        Sub sub = source.toSub();
        if (sub == null) return;
        sPendingRestore = sub;
        // 通知 Assrt：有外挂待选，历史重进强制选中
        try {
            Class<?> assrt = Class.forName("com.fongmi.android.tv.subtitle.AssrtSubtitleMatch");
            assrt.getMethod("primeExternalPreference", String.class, String.class)
                    .invoke(null, sub.getName(), sub.getFormat());
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "prepareRestore pending name=" + sub.getName() + " url=" + sub.getUrl());
        markAction("prepareRestore", sub.getName());

    }

    /**
     * setMediaItem / prepareMpv / start 前调用。
     * 成功注入才消费 pending；失败保留以便重试。
     */
    /**
     * 起播期不再改 PlaySpec.subs（易触发 reprepare / 有声无画）。
     * 外挂恢复改由 Result.subs（attachRememberedSub）+ 轨道就绪后轻量选轨完成。
     * 此方法仅消费 pending 标记，便于 last_action 观测。
     */
    public static void injectPendingIntoPlayerManager(Object playerManager) {
        Sub sub = sPendingRestore;
        if (sub == null) return;
        // 不置空 pending：留给 Assrt onTracksReady / selectPending 使用 peekPending
        markAction("injectSkipped", sub.getName() + " (soft-restore)");
        Log.i(TAG, "inject skipped soft-restore name=" + sub.getName());
    }

    /** 选轨成功后由 Assrt 调用，清理 pending */
    public static void clearPending() {
        sPendingRestore = null;
    }

    public static Sub peekPending() {
        return sPendingRestore;
    }

    public static String peekPendingName() {
        return sPendingRestore != null ? sPendingRestore.getName() : "";
    }

    public static String peekPendingFormat() {
        return sPendingRestore != null ? sPendingRestore.getFormat() : "";
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

    
    /** 不依赖 logcat：写文件 + 可选 Toast，方便确认补丁是否进包运行 */
    private static void markAction(String action, String detail) {
        try {
            File dir = new File(Init.context().getFilesDir(), "sub_remember");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "last_action.txt");
            String line = System.currentTimeMillis() + "\t" + action + "\t" + (detail == null ? "" : detail) + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // 控制台（release 也可能保留）
            System.out.println("SubRestore|" + action + "|" + detail);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.e(TAG, action + " " + detail);
            android.util.Log.e("AssrtSub", action + " " + detail);
        } catch (Throwable ignored) {
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


    /** 稳定集标识：备注优先；避免把可变播放 URL 当 episode 键 */
    private static String stableEpisodeId(History h) {
        if (h == null) return "";
        try {
            String remarks = safe(h.getVodRemarks());
            if (!remarks.isEmpty() && !SubtitleRestorePolicy.looksLikePlayUrl(remarks)) return remarks;
        } catch (Throwable ignored) {
        }
        try {
            String ep = safe(h.getEpisodeUrl());
            if (!ep.isEmpty() && !SubtitleRestorePolicy.looksLikePlayUrl(ep) && ep.length() < 64) return ep;
        } catch (Throwable ignored) {
        }
        return "";
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
}
