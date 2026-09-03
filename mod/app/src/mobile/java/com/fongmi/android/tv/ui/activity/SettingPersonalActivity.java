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
        try { mBinding.homePush.setOnClickListener(this::setHomePush); } catch (Throwable ignored) {}
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
        try { mBinding.homePushText.setText(getSwitch(Setting.isHomePush())); } catch (Throwable ignored) {}
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
        int[] options = new int[]{1, 2, 4, 8, 16};
        int cur = Setting.getSearchThread();
        int idx = 0;
        for (int i = 0; i < options.length; i++) if (options[i] == cur) { idx = i; break; }
        Setting.putSearchThread(options[(idx + 1) % options.length]);
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

    private void setHomePush(View view) {
        Setting.putHomePush(!Setting.isHomePush());
        refreshTexts();
    }
}
