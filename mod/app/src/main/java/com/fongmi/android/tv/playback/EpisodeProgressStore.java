package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Prefers;

/**
 * 分集进度：同一部剧按「集」分别记进度，换集不丢其它集进度。
 * 存 Prefers，不改 History 表结构。
 */
public final class EpisodeProgressStore {

    private static final String PREFIX = "ep_prog_";

    private EpisodeProgressStore() {
    }

    private static String cacheKey(String historyKey, String episodeUrl, String episodeName) {
        String id = !TextUtils.isEmpty(episodeUrl) ? episodeUrl : (episodeName == null ? "" : episodeName);
        return PREFIX + Util.md5((historyKey == null ? "" : historyKey) + "\u0001" + id);
    }

    public static void save(History history, Episode episode, long position, long duration) {
        if (history == null || episode == null) return;
        if (position <= 0 || position == C.TIME_UNSET) return;
        try {
            String key = cacheKey(history.getKey(), episode.getUrl(), episode.getName());
            Prefers.put(key, position + "\u0001" + (duration > 0 ? duration : 0));
        } catch (Throwable ignored) {
        }
    }

    /** 用 History 当前记录的集信息保存（换集前调用） */
    public static void saveCurrent(History history) {
        if (history == null) return;
        long pos = history.getPosition();
        if (pos <= 0 || pos == C.TIME_UNSET) return;
        try {
            String key = cacheKey(history.getKey(), history.getEpisodeUrl(), history.getVodRemarks());
            Prefers.put(key, pos + "\u0001" + (history.getDuration() > 0 ? history.getDuration() : 0));
        } catch (Throwable ignored) {
        }
    }

    public static long[] load(History history, Episode episode) {
        if (history == null || episode == null) return null;
        try {
            String key = cacheKey(history.getKey(), episode.getUrl(), episode.getName());
            String raw = Prefers.getString(key);
            if (TextUtils.isEmpty(raw)) return null;
            String[] p = raw.split("\u0001", -1);
            long pos = Long.parseLong(p[0]);
            long dur = p.length > 1 ? Long.parseLong(p[1]) : 0;
            if (pos <= 0) return null;
            return new long[]{pos, dur};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void applyToHistory(History history, Episode episode) {
        long[] pd = load(history, episode);
        if (pd == null || history == null) return;
        history.setPosition(pd[0]);
        if (pd[1] > 0) history.setDuration(pd[1]);
    }
}
