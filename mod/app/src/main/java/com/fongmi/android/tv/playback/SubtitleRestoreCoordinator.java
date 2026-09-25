package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * History <-> PlayerManager 的外挂字幕记忆搬运。
 */
public final class SubtitleRestoreCoordinator {

    private SubtitleRestoreCoordinator() {
    }

    public static boolean remember(History history, Sub sub) {
        if (history == null || Setting.isIncognito()) return false;
        SubtitleSource source = SubtitleSource.of(sub, history.getEpisodeUrl());
        if (source == null) return false;
        history.setSubtitleSourceObject(source);
        return true;
    }

    public static boolean restore(History history, PlayerManager player, Result result) {
        if (player == null) return false;
        player.setPendingRestoreSub(null);
        if (history == null || Setting.isIncognito()) return false;

        SubtitleSource source = history.getSubtitleSourceObject();
        SubtitleRestorePolicy.Decision decision = SubtitleRestorePolicy.decide(
                source, history.getEpisodeUrl(), false);

        if (decision.restore()) {
            Sub sub = source.toSub();
            player.setPendingRestoreSub(sub);
            if (result != null) {
                List<Sub> subs = new ArrayList<>();
                subs.add(sub);
                result.setSubs(subs);
            }
        }

        if (!decision.clear()) return false;
        history.setSubtitleSource("");
        return true;
    }
}
