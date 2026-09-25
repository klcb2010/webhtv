package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Sub;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * 持久化一次外挂字幕选择的来源，而不是运行时 sid。
 * 依据 Silent1566/webhtv/docs/SUB-EXT-HISTORY-external-subtitle-restore.md。
 */
public final class SubtitleSource {

    public static final String MODE_EXTERNAL = "external";
    public static final String MODE_DISABLED = "disabled";

    private static final Gson GSON = new Gson();

    @SerializedName("mode")
    private String mode;
    @SerializedName("url")
    private String url;
    @SerializedName("name")
    private String name;
    @SerializedName("lang")
    private String lang;
    @SerializedName("format")
    private String format;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("time")
    private long time;

    public SubtitleSource() {
    }

    public static SubtitleSource of(Sub sub, String episodeUrl) {
        if (sub == null || TextUtils.isEmpty(sub.getUrl())) return null;
        SubtitleSource source = new SubtitleSource();
        source.mode = MODE_EXTERNAL;
        source.url = sub.getUrl();
        source.name = sub.getName();
        source.lang = sub.getLang();
        source.format = sub.getFormat();
        source.episodeUrl = episodeUrl == null ? "" : episodeUrl;
        source.time = System.currentTimeMillis();
        return source;
    }

    public static SubtitleSource decode(String json) {
        if (TextUtils.isEmpty(json)) return null;
        try {
            SubtitleSource source = GSON.fromJson(json, SubtitleSource.class);
            return source != null && source.isUsable() ? source : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return null;
        }
    }

    public static String encode(SubtitleSource source) {
        return source == null || !source.isUsable() ? "" : GSON.toJson(source);
    }

    public Sub toSub() {
        return isUsable() ? Sub.create(getName(), getUrl(), getLang(), getFormat()) : null;
    }

    public boolean isUsable() {
        return isExternal() && !TextUtils.isEmpty(url);
    }

    public boolean isExternal() {
        return !MODE_DISABLED.equals(mode);
    }

    public boolean isRemote() {
        return getUrl().contains("://");
    }

    public String getMode() {
        return mode == null ? MODE_EXTERNAL : mode;
    }

    public String getUrl() {
        return url == null ? "" : url;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public String getLang() {
        return lang == null ? "" : lang;
    }

    public String getFormat() {
        return format == null ? "" : format;
    }

    public String getEpisodeUrl() {
        return episodeUrl == null ? "" : episodeUrl;
    }

    public long getTime() {
        return time;
    }
}
