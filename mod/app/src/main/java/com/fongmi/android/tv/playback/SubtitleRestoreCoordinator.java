package com.fongmi.android.tv.playback;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 外挂字幕记忆：Prefers 存 JSON，文件复制到 filesDir/sub_remember 防清缓存丢失。
 */
public final class SubtitleRestoreCoordinator {

    private static final String TAG = "SubRestore";
    private static final String PREFIX = "ext_sub_src_";

    private SubtitleRestoreCoordinator() {
    }

    public static boolean remember(History history, Sub sub) {
        if (history == null || sub == null) return false;
        try {
            Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
            Object incognito = setting.getMethod("isIncognito").invoke(null);
            if (incognito instanceof Boolean && (Boolean) incognito) return false;
        } catch (Throwable ignored) {
        }
        Sub durable = ensureDurable(sub);
        String episodeUrl = history.getEpisodeUrl();
        SubtitleSource source = SubtitleSource.of(durable, episodeUrl);
        if (source == null) return false;
        String json = SubtitleSource.encode(source);
        putCommit(cacheKey(history.getKey(), episodeUrl), json);
        if (!TextUtils.isEmpty(history.getKey())) {
            putCommit(cacheKey(history.getKey(), ""), json);
        }
        // 再按片名+集名兜底
        try {
            String vod = history.getVodName() == null ? "" : history.getVodName();
            String remarks = history.getVodRemarks() == null ? "" : history.getVodRemarks();
            if (!TextUtils.isEmpty(vod)) putCommit(cacheKey("vod:" + vod, remarks), json);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "remember key=" + history.getKey() + " ep=" + episodeUrl + " url=" + durable.getUrl());
        return true;
    }

    public static boolean remember(String historyKey, String episodeUrl, Sub sub) {
        if (sub == null || TextUtils.isEmpty(sub.getUrl())) return false;
        Sub durable = ensureDurable(sub);
        SubtitleSource source = SubtitleSource.of(durable, episodeUrl);
        if (source == null) return false;
        String json = SubtitleSource.encode(source);
        putCommit(cacheKey(historyKey, episodeUrl), json);
        if (!TextUtils.isEmpty(historyKey)) putCommit(cacheKey(historyKey, ""), json);
        Log.i(TAG, "remember key=" + historyKey + " url=" + durable.getUrl());
        return true;
    }

    public static Sub restore(History history, Object player, Result result) {
        if (history == null) return null;
        try {
            Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
            Object incognito = setting.getMethod("isIncognito").invoke(null);
            if (incognito instanceof Boolean && (Boolean) incognito) return null;
        } catch (Throwable ignored) {
        }
        String episodeUrl = history.getEpisodeUrl();
        SubtitleSource source = load(history.getKey(), episodeUrl);
        if (source == null) source = load(history.getKey(), "");
        if (source == null) {
            try {
                String vod = history.getVodName() == null ? "" : history.getVodName();
                String remarks = history.getVodRemarks() == null ? "" : history.getVodRemarks();
                if (!TextUtils.isEmpty(vod)) source = load("vod:" + vod, remarks);
            } catch (Throwable ignored) {
            }
        }
        SubtitleRestorePolicy.Decision decision = SubtitleRestorePolicy.decide(source, episodeUrl, false);
        Log.i(TAG, "restore decision=" + decision.reason() + " has=" + (source != null));
        if (decision.clear()) {
            clear(history.getKey(), episodeUrl);
            clear(history.getKey(), "");
            return null;
        }
        if (!decision.restore() || source == null) return null;
        Sub sub = source.toSub();
        if (sub == null) return null;
        injectResult(result, sub);
        // setPlayer() 发生在新播放器创建前，player() 此时可能仍是上一集。
        // 这里只注入 Result，绝不能 setSub() 到旧播放器，否则会触发错误的重建。
        return sub;
    }

    public static void applyToPlayer(Object player, Sub sub) {
        if (player == null || sub == null) return;
        try {
            player.getClass().getMethod("setSub", Sub.class).invoke(player, sub);
        } catch (Throwable e) {
            Log.w(TAG, "setSub failed: " + e.getMessage());
        }
    }

    private static void injectResult(Result result, Sub sub) {
        if (result == null || sub == null) return;
        try {
            List<Sub> list = new ArrayList<>();
            list.add(sub);
            try {
                java.lang.reflect.Field f = result.getClass().getDeclaredField("subs");
                f.setAccessible(true);
                f.set(result, list);
            } catch (Throwable e) {
                result.setSubs(list);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 复制到 filesDir，避免清缓存后路径失效 */
    private static Sub ensureDurable(Sub sub) {
        try {
            String url = sub.getUrl();
            if (TextUtils.isEmpty(url) || url.contains("://")) return sub;
            File src = new File(url);
            if (!src.isFile()) return sub;
            File dir = new File(Init.context().getFilesDir(), "sub_remember");
            if (!dir.exists() && !dir.mkdirs()) return sub;
            String name = src.getName();
            File dst = new File(dir, md5(url) + "_" + name);
            if (!dst.isFile() || dst.length() != src.length()) {
                copyFile(src, dst);
            }
            if (!dst.isFile()) return sub;
            Sub out = Sub.create(sub.getName(), dst.getAbsolutePath(), sub.getLang(), sub.getFormat());
            try {
                out.setFlag(sub.getFlag());
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable e) {
            return sub;
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dst).getChannel()) {
            out.transferFrom(in, 0, in.size());
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
        String json = Prefers.getString(cacheKey(historyKey, episodeUrl));
        return SubtitleSource.decode(json);
    }

    private static void clear(String historyKey, String episodeUrl) {
        putCommit(cacheKey(historyKey, episodeUrl), "");
    }

    private static String cacheKey(String historyKey, String episodeUrl) {
        String raw = (historyKey == null ? "" : historyKey) + "\u0001" + (episodeUrl == null ? "" : episodeUrl);
        return PREFIX + md5(raw);
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
