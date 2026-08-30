package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;
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
    protected void initView() {
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
        mBinding.autoBackup.post(() -> mBinding.autoBackup.requestFocus());
    }

    private void setListeners() {
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
    }

    private void refreshTexts() {
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
        String langLabel = lang;
        if (subtitleValues != null && subtitleLabels != null) {
            for (int i = 0; i < subtitleValues.length && i < subtitleLabels.length; i++) {
                if (subtitleValues[i].equals(lang)) {
                    langLabel = subtitleLabels[i];
                    break;
                }
            }
        }
        mBinding.subtitleLanguageText.setText(langLabel);
        mBinding.homeSiteLockText.setText(getSwitch(Setting.isHomeSiteLock()));
        mBinding.homeVodAutoLoadText.setText(getSwitch(Setting.isHomeVodAutoLoad()));
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
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
        int size = globalHistoryMode == null || globalHistoryMode.length == 0 ? 3 : globalHistoryMode.length;
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
        for (int i = 0; i < options.length; i++) if (options[i] == cur) idx = i;
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
        for (int i = 0; i < subtitleValues.length; i++) if (subtitleValues[i].equals(cur)) idx = i;
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
}
