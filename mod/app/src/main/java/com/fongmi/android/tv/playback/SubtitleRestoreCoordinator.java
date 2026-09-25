package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.github.catvod.utils.Prefers;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 外挂字幕在 History 与播放器之间的搬运（Prefers 落盘，避免改 Room 迁移）。
 *
 * 写入：用户选中外挂后 remember()
 * 读取：起播前 restore() → 写入 Result.subs / 调用 player.setSub
 */
public final class SubtitleRestoreCoordinator {

    private static final String PREFIX = "ext_sub_src_";

    private SubtitleRestoreCoordinator() {
    }

    public static boolean remember(History history, Sub sub) {
        if (history == null || sub == null) return false;
        try {
            // 无痕模式
            Class<?> setting = Class.forName("com.fongmi.android.tv.setting.Setting");
            Object incognito = setting.getMethod("isIncognito").invoke(null);
            if (incognito instanceof Boolean && (Boolean) incognito) return false;
        } catch (Throwable ignored) {
        }
        String episodeUrl = history.getEpisodeUrl();
        SubtitleSource source = SubtitleSource.of(sub, episodeUrl);
        if (source == null) return false;
        Prefers.put(cacheKey(history.getKey(), episodeUrl), SubtitleSource.encode(source));
        // 同时按 historyKey 存一份，方便 episodeUrl 短暂为空时回退
        if (!TextUtils.isEmpty(history.getKey())) {
            Prefers.put(cacheKey(history.getKey(), ""), SubtitleSource.encode(source));
        }
        return true;
    }

    public static boolean remember(String historyKey, String episodeUrl, Sub sub) {
        if (sub == null || TextUtils.isEmpty(sub.getUrl())) return false;
        SubtitleSource source = SubtitleSource.of(sub, episodeUrl);
        if (source == null) return false;
        Prefers.put(cacheKey(historyKey, episodeUrl), SubtitleSource.encode(source));
        if (!TextUtils.isEmpty(historyKey)) {
            Prefers.put(cacheKey(historyKey, ""), SubtitleSource.encode(source));
        }
        return true;
    }

    /**
     * 起播前恢复。把字幕写进 Result（若当前无自带 subs），并尝试 player.setSub。
     *
     * @return 恢复用的 Sub；失败返回 null
     */
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
        SubtitleRestorePolicy.Decision decision = SubtitleRestorePolicy.decide(source, episodeUrl, false);
        if (decision.clear()) {
            clear(history.getKey(), episodeUrl);
            clear(history.getKey(), "");
            return null;
        }
        if (!decision.restore() || source == null) return null;
        Sub sub = source.toSub();
        if (sub == null) return null;
        if (result != null) {
            try {
                List<Sub> existing = result.getSubs();
                if (existing == null || existing.isEmpty()) {
                    List<Sub> list = new ArrayList<>();
                    list.add(sub);
                    result.setSubs(list);
                }
            } catch (Throwable ignored) {
            }
        }
        applyToPlayer(player, sub);
        return sub;
    }

    public static void applyToPlayer(Object player, Sub sub) {
        if (player == null || sub == null) return;
        try {
            player.getClass().getMethod("setSub", Sub.class).invoke(player, sub);
        } catch (Throwable ignored) {
        }
    }

    private static SubtitleSource load(String historyKey, String episodeUrl) {
        String json = Prefers.getString(cacheKey(historyKey, episodeUrl));
        return SubtitleSource.decode(json);
    }

    private static void clear(String historyKey, String episodeUrl) {
        Prefers.put(cacheKey(historyKey, episodeUrl), "");
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
