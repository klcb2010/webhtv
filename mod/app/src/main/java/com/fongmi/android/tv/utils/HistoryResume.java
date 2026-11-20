package com.fongmi.android.tv.utils;

import android.app.Activity;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;

/**
 * Open a history item under local / global-history modes.
 * AUTO: switch config if needed, then open by siteKey+vodId when site exists; otherwise search by name.
 * SEARCH: always open global search with the title.
 */
public final class HistoryResume {

    private HistoryResume() {
    }

    public static void open(Activity activity, History item) {
        if (activity == null || item == null) return;
        if (Setting.isGlobalHistorySearch()) {
            SearchActivity.start(activity, item.getVodName());
            return;
        }
        if (!Setting.isGlobalHistoryEnabled() || item.getCid() == VodConfig.getCid()) {
            openLocal(activity, item);
            return;
        }
        Config config = Config.find(item.getCid());
        if (config == null || config.isEmpty()) {
            SearchActivity.start(activity, item.getVodName());
            return;
        }
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                openLocal(activity, item);
            }

            @Override
            public void error(String msg) {
                SearchActivity.start(activity, item.getVodName());
            }
        });
    }

    private static void openLocal(Activity activity, History item) {
        Site site = VodConfig.get().getSite(item.getSiteKey());
        if (site == null || site.getKey().isEmpty()) {
            SearchActivity.start(activity, item.getVodName());
            return;
        }
        VideoActivity.start(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, item.getWallPic());
    }
}
