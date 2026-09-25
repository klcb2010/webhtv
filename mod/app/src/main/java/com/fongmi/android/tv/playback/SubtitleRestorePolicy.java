package com.fongmi.android.tv.playback;

import java.io.File;
import java.util.function.Predicate;

/**
 * 与 Silent1566/webhtv 文档 6.3/6.4 对齐的恢复判定。
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

        public boolean restore() { return restore; }
        public boolean clear() { return clear; }
        public String reason() { return reason; }

        static Decision inject() { return new Decision(true, false, "restore"); }
        static Decision drop(String reason) { return new Decision(false, true, reason); }
        static Decision skip(String reason) { return new Decision(false, false, reason); }
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
        if (source.getEpisodeUrl().isEmpty()) return Decision.drop("episode-unknown");
        String current = episodeUrl == null ? "" : episodeUrl;
        if (!source.getEpisodeUrl().equals(current)) return Decision.skip("episode-changed");
        if (source.isRemote()) return Decision.inject();
        return exists.test(source.getUrl()) ? Decision.inject() : Decision.drop("file-missing");
    }
}
