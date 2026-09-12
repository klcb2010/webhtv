package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;

public class SettingPersonalActivity extends BaseActivity {

    private ActivitySettingPersonalBinding mBinding;
    private String[] globalHistoryMode;
    private String[] subtitleLabels;
    private String[] subtitleValues;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPersonalActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPersonalBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        globalHistoryMode = getResources().getStringArray(R.array.select_global_history_mode);
        try {
            subtitleLabels = getResources().getStringArray(R.array.select_subtitle_language);
            subtitleValues = getResources().getStringArray(R.array.select_subtitle_language_value);
        } catch (Throwable e) {
            subtitleLabels = new String[]{"Auto", "Chinese", "English"};
            subtitleValues = new String[]{"auto", "zh", "en"};
        }
        refreshTexts();
        setListeners();
    }

    private void setListeners() {
        // autoChange hidden — use upstream 播放设置
        mBinding.autoBackup.setOnClickListener(this::setAutoBackup);
        mBinding.episodeHistory.setOnClickListener(this::setEpisodeHistory);
        mBinding.globalHistory.setOnClickListener(this::setGlobalHistory);
        mBinding.playBackToDetail.setOnClickListener(this::setPlayBackToDetail);
        mBinding.searchThread.setOnClickListener(this::setSearchThread);
        mBinding.subtitleAutoMatch.setOnClickListener(this::setSubtitleAutoMatch);
        mBinding.subtitleLanguage.setOnClickListener(this::setSubtitleLanguage);
        mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);
        mBinding.homeVodAutoLoad.setOnClickListener(this::setHomeVodAutoLoad);
        mBinding.homeHistory.setOnClickListener(this::setHomeHistory);
        try { mBinding.playCache.setOnClickListener(this::setPlayCache); } catch (Throwable ignored) {}
        try { mBinding.playDirect.setOnClickListener(this::setPlayDirect); } catch (Throwable ignored) {}
        try { mBinding.recommendSource.setOnClickListener(this::setRecommendSource); } catch (Throwable ignored) {}
    }

    private void refreshTexts() {
        try {
        // autoChange hidden
        } catch (Throwable e) {
        // autoChange hidden
        }
        mBinding.autoBackupText.setText(getSwitch(Setting.isAutoBackup()));
        mBinding.episodeHistoryText.setText(getSwitch(Setting.isEpisodeHistory()));
        int gh = Setting.getGlobalHistoryMode();
        if (globalHistoryMode != null && gh >= 0 && gh < globalHistoryMode.length) {
            mBinding.globalHistoryText.setText(globalHistoryMode[gh]);
        } else {
            mBinding.globalHistoryText.setText(String.valueOf(gh));
        }
        mBinding.playBackToDetailText.setText(getSwitch(Setting.isPlayBackToDetail()));
        mBinding.searchThreadText.setText(String.valueOf(Setting.getSearchThread()));
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
        String lang = Setting.getSubtitlePreferredLanguage();
        String label = lang;
        if (subtitleValues != null && subtitleLabels != null) {
            for (int i = 0; i < subtitleValues.length; i++) {
                if (subtitleValues[i].equals(lang)) {
                    label = subtitleLabels[i];
                    break;
                }
            }
        }
        mBinding.subtitleLanguageText.setText(label);
        mBinding.homeSiteLockText.setText(getSwitch(Setting.isHomeSiteLock()));
        mBinding.homeVodAutoLoadText.setText(getSwitch(Setting.isHomeVodAutoLoad()));
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        try { mBinding.playCacheText.setText(playCacheLabel()); } catch (Throwable ignored) {}
        try { mBinding.playDirectText.setText(getSwitch(Setting.isPlayDirect())); } catch (Throwable ignored) {}
        try { mBinding.recommendSourceText.setText(recommendSourceLabel()); } catch (Throwable ignored) {}
        // TV-only rows may be GONE on mobile via layout; still safe if present
        try {
            boolean tv = false;
            try {
                Class.forName("androidx.leanback.widget.VerticalGridView");
                // detect by presence of leanback resources package flavor is compile-time
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void setAutoChange(View view) {
        try {
            PlayerSetting.putAutoChange(!PlayerSetting.isAutoChange());
        } catch (Throwable ignored) {
        }
        refreshTexts();
    }

    private void setAutoBackup(View view) {
        Setting.putAutoBackup(!Setting.isAutoBackup());
        refreshTexts();
    }

    private void setEpisodeHistory(View view) {
        Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
        refreshTexts();
    }

    private void setGlobalHistory(View view) {
        int size = globalHistoryMode == null ? 2 : globalHistoryMode.length;
        if (size <= 0) size = 2;
        Setting.putGlobalHistoryMode((Setting.getGlobalHistoryMode() + 1) % size);
        refreshTexts();
    }

    private void setPlayBackToDetail(View view) {
        Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
        refreshTexts();
    }

    private void setSearchThread(View view) {
        int[] options = new int[]{8, 16, 20, 32, 48, 64};
        int cur = Setting.getSearchThread();
        int idx = 0;
        for (int i = 0; i < options.length; i++) if (options[i] == cur) { idx = i; break; }
        int next = options[(idx + 1) % options.length];
        Setting.putSearchThread(next);
        try { com.fongmi.android.tv.utils.Task.newSearchExecutor(next); } catch (Throwable ignored) {}
        refreshTexts();
    }

    private void setSubtitleAutoMatch(View view) {
        Setting.putSubtitleAutoMatchEnabled(!Setting.isSubtitleAutoMatchEnabled());
        refreshTexts();
    }

    private void setSubtitleLanguage(View view) {
        if (subtitleValues == null || subtitleValues.length == 0) return;
        String cur = Setting.getSubtitlePreferredLanguage();
        int idx = 0;
        for (int i = 0; i < subtitleValues.length; i++) if (subtitleValues[i].equals(cur)) { idx = i; break; }
        Setting.putSubtitlePreferredLanguage(subtitleValues[(idx + 1) % subtitleValues.length]);
        refreshTexts();
    }

    private void setHomeSiteLock(View view) {
        Setting.putHomeSiteLock(!Setting.isHomeSiteLock());
        refreshTexts();
    }

    private void setHomeVodAutoLoad(View view) {
        Setting.putHomeVodAutoLoad(!Setting.isHomeVodAutoLoad());
        refreshTexts();
    }

    private void setHomeHistory(View view) {
        Setting.putHomeHistory(!Setting.isHomeHistory());
        refreshTexts();
    }





    private String playCacheLabel() {
        try {
            String[] labels = getResources().getStringArray(R.array.select_play_cache);
            int opt = PlayerSetting.getPlayCacheOption();
            if (labels != null && opt >= 0 && opt < labels.length) return labels[opt];
        } catch (Throwable ignored) {}
        int opt = 0;
        try { opt = PlayerSetting.getPlayCacheOption(); } catch (Throwable ignored) {}
        switch (opt) {
            case 1: return "256MB";
            case 2: return "512MB";
            case 3: return "1GB";
            case 4: return "2GB";
            default: return "128MB";
        }
    }

    private void setPlayCache(View view) {
        int opt = 0;
        try { opt = PlayerSetting.getPlayCacheOption(); } catch (Throwable ignored) {}
        int size = 5;
        try {
            String[] labels = getResources().getStringArray(R.array.select_play_cache);
            if (labels != null && labels.length > 0) size = labels.length;
        } catch (Throwable ignored) {}
        opt = (opt + 1) % Math.max(1, size);
        try { PlayerSetting.putPlayCacheOption(opt); } catch (Throwable ignored) {}
        try { mBinding.playCacheText.setText(playCacheLabel()); } catch (Throwable ignored) {}
        try { refreshTexts(); } catch (Throwable ignored) {}
    }

    private void setPlayDirect(View view) {
        Setting.putPlayDirect(!Setting.isPlayDirect());
        try { mBinding.playDirectText.setText(getSwitch(Setting.isPlayDirect())); } catch (Throwable ignored) {}
        try { refreshTexts(); } catch (Throwable ignored) {}
    }

    private String recommendSourceLabel() {
        int src = Setting.getRecommendSource();
        if (src == Setting.RECOMMEND_AI) return getString(R.string.setting_recommend_ai);
        if (src == Setting.RECOMMEND_DOUBAN) return getString(R.string.setting_recommend_douban);
        if (src == Setting.RECOMMEND_AUTO) return getString(R.string.setting_recommend_auto);
        return getString(R.string.setting_recommend_off);
    }

    private void setRecommendSource(View view) {
        int src = Setting.getRecommendSource();
        src = (src + 1) % 4;
        Setting.putRecommendSource(src);
        try { mBinding.recommendSourceText.setText(recommendSourceLabel()); } catch (Throwable ignored) {}
    }

}
