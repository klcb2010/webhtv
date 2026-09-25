package com.fongmi.android.tv.playback;

import java.io.File;
import java.util.function.Predicate;

/**
 * fish2018 的 History.episodeUrl 经常是「本次解析出的播放地址」，每次进播放页都会变。
 * 不能把它当成「换集」依据；换集时 History.key 通常会变。
 */
public final class SubtitleRestorePolicy {

    public static final class Decision {
        private final boolean restore;
        private final boolean clear;
        private final String reason;

        private Decision(boolean restore, boolean clear, String reason) {
            this.restore = restore;
            this.clear = clear;
            this.reason = reason;
        }

        public boolean restore() {
            return restore;
        }

        public boolean clear() {
            return clear;
        }

        public String reason() {
            return reason;
        }

        static Decision inject() {
            return new Decision(true, false, "restore");
        }

        static Decision inject(String reason) {
            return new Decision(true, false, reason);
        }

        static Decision drop(String reason) {
            return new Decision(false, true, reason);
        }

        static Decision skip(String reason) {
            return new Decision(false, false, reason);
        }
    }

    private SubtitleRestorePolicy() {
    }

    public static Decision decide(SubtitleSource source, String episodeUrl, boolean crossSource) {
        return decide(source, episodeUrl, crossSource, path -> new File(path).isFile());
    }

    static Decision decide(SubtitleSource source, String episodeUrl, boolean crossSource,
                           Predicate<String> exists) {
        if (source == null || !source.isUsable()) return Decision.skip("absent");
        if (crossSource) return Decision.skip("cross-source");

        String saved = source.getEpisodeUrl() == null ? "" : source.getEpisodeUrl().trim();
        String ep = episodeUrl == null ? "" : episodeUrl.trim();

        // 仅当两边都是「短标识」（不像 URL）且不一致时，才视为换集
        if (!saved.isEmpty() && !ep.isEmpty() && !saved.equals(ep)
                && !looksLikePlayUrl(saved) && !looksLikePlayUrl(ep)) {
            return Decision.skip("episode-changed");
        }

        if (source.isRemote()) {
            return (!saved.isEmpty() && !ep.isEmpty() && !saved.equals(ep) && looksLikePlayUrl(saved))
                    ? Decision.inject("url-rotated")
                    : Decision.inject();
        }
        if (!exists.test(source.getUrl())) return Decision.drop("file-missing");
        if (!saved.isEmpty() && !ep.isEmpty() && !saved.equals(ep) && looksLikePlayUrl(saved)) {
            return Decision.inject("url-rotated");
        }
        return Decision.inject();
    }

    /** 播放直链 / 带 query 的解析地址，每次可能不同 */
    static boolean looksLikePlayUrl(String s) {
        if (s == null || s.isEmpty()) return false;
        String t = s.toLowerCase();
        if (t.contains("://")) return true;
        if (t.contains(".m3u8") || t.contains(".mp4") || t.contains(".mkv")) return true;
        if (t.contains("?") && t.length() > 40) return true;
        if (t.startsWith("/") && t.length() > 20) return true;
        return t.length() > 80;
    }
}
