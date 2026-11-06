package com.fongmi.android.tv.ui.activity;

import com.fongmi.android.tv.ui.dialog.ChildLockSetupDialog;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.subtitle.SubtitleFontManager;
import com.fongmi.android.tv.event.RefreshEvent;
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
        try { mBinding.globalToast.setOnClickListener(this::setGlobalToast); } catch (Throwable ignored) {}
        try { mBinding.autoCheckUpdate.setOnClickListener(this::setAutoCheckUpdate); } catch (Throwable ignored) {}
        try { mBinding.exitClearCache.setOnClickListener(this::setExitClearCache); } catch (Throwable ignored) {}
        try { mBinding.subtitleColor.setOnClickListener(this::setSubtitleColor); } catch (Throwable ignored) {}
        try { mBinding.subtitleFont.setOnClickListener(this::setSubtitleFont); } catch (Throwable ignored) {}
        try { mBinding.kaitiScale.setOnClickListener(this::setKaiTiScale); } catch (Throwable ignored) {}
        mBinding.episodeHistory.setOnClickListener(this::setEpisodeHistory);
        mBinding.globalHistory.setOnClickListener(this::setGlobalHistory);
        mBinding.playBackToDetail.setOnClickListener(this::setPlayBackToDetail);
        mBinding.searchThread.setOnClickListener(this::setSearchThread);
        mBinding.subtitleAutoMatch.setOnClickListener(this::setSubtitleAutoMatch);
        mBinding.subtitleLanguage.setOnClickListener(this::setSubtitleLanguage);
        try { mBinding.childLock.setOnClickListener(this::setChildLock); } catch (Throwable ignored) {}
        try { mBinding.homePush.setOnClickListener(this::setHomePush); } catch (Throwable ignored) {}
        try { mBinding.playDirect.setOnClickListener(this::setPlayDirect); } catch (Throwable ignored) {}
        try { mBinding.homeEnterLive.setOnClickListener(this::setHomeEnterLive); } catch (Throwable ignored) {}
        try { mBinding.liveDefaultFullscreen.setOnClickListener(this::setLiveDefaultFullscreen); } catch (Throwable ignored) {}
        try { mBinding.eac3SoftDecode.setOnClickListener(this::setEac3SoftDecode); } catch (Throwable ignored) {}
        try { mBinding.searchPanFirst.setOnClickListener(this::setSearchPanFirst); } catch (Throwable ignored) {}
        try { mBinding.playDirectPanPrefer.setOnClickListener(this::setPlayDirectPanPrefer); } catch (Throwable ignored) {}
        try { mBinding.hideHomeTypeMore.setOnClickListener(this::setHideHomeTypeMore); } catch (Throwable ignored) {}
        try { mBinding.playbackScreenshotBtn.setOnClickListener(this::setPlaybackScreenshotBtn); } catch (Throwable ignored) {}
        try { mBinding.screenshotBrightnessBoost.setOnClickListener(this::setScreenshotBrightnessBoost); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotBtn.setOnClickListener(this::setMpvScreenshotBtn); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotBrightnessBoost.setOnClickListener(this::setMpvScreenshotBrightnessBoost); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotContrast.setOnClickListener(this::setMpvScreenshotContrast); } catch (Throwable ignored) {}
        try { mBinding.hdrToSdr.setOnClickListener(this::setHdrToSdr); } catch (Throwable ignored) {}
        try { mBinding.mpvFontScale.setOnClickListener(this::setMpvFontScale); } catch (Throwable ignored) {}
        try { mBinding.debugLog.setOnClickListener(this::setDebugLog); } catch (Throwable ignored) {}
        try { mBinding.webviewLive.setOnClickListener(this::setWebViewLive); } catch (Throwable ignored) {}
        try { mBinding.recommendSource.setOnClickListener(this::setRecommendSource); } catch (Throwable ignored) {}
        try { mBinding.smbEntry.setOnClickListener(this::setSmbEntry); } catch (Throwable ignored) {}
    }

    private void refreshTexts() {
        try {
        // autoChange hidden
        } catch (Throwable e) {
        // autoChange hidden
        }
        mBinding.autoBackupText.setText(getSwitch(Setting.isAutoBackup()));
        try { mBinding.globalToastText.setText(getSwitch(Setting.isGlobalToast())); } catch (Throwable ignored) {}
        try { mBinding.autoCheckUpdateText.setText(getSwitch(Setting.isAutoCheckUpdate())); } catch (Throwable ignored) {}
        try { mBinding.exitClearCacheText.setText(exitClearCacheLabel()); } catch (Throwable ignored) {}
        try { mBinding.subtitleColorText.setText(subtitleColorLabel()); } catch (Throwable ignored) {}
        try { mBinding.subtitleFontText.setText(subtitleFontLabel()); } catch (Throwable ignored) {}
        try { mBinding.kaitiScaleText.setText(Setting.getKaiTiScaleLabel(true)); } catch (Throwable ignored) {}
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
        try { mBinding.childLockText.setText(getSwitch(Setting.isChildLockEnabled())); } catch (Throwable ignored) {}
        try { mBinding.homePushText.setText(getSwitch(Setting.isHomePush())); } catch (Throwable ignored) {}
        try { mBinding.playDirectText.setText(getSwitch(Setting.isPlayDirect())); } catch (Throwable ignored) {}
        try { mBinding.homeEnterLiveText.setText(getSwitch(Setting.isHomeEnterLive())); } catch (Throwable ignored) {}
        try { mBinding.liveDefaultFullscreenText.setText(getSwitch(Setting.isLiveDefaultFullscreen())); } catch (Throwable ignored) {}
        try { mBinding.eac3SoftDecodeText.setText(getSwitch(Setting.isEac3SoftDecode())); } catch (Throwable ignored) {}
        try { mBinding.searchPanFirstText.setText(Setting.getSearchResultPreferText()); } catch (Throwable ignored) {}
        try { mBinding.playDirectPanPreferText.setText(getSwitch(Setting.isPlayDirectPanPrefer())); } catch (Throwable ignored) {}
        try { mBinding.hideHomeTypeMoreText.setText(getSwitch(Setting.isHideHomeTypeMore())); } catch (Throwable ignored) {}
        try { mBinding.playbackScreenshotBtnText.setText(Setting.getExoScreenshotModeLabel()); } catch (Throwable ignored) {}
        try { mBinding.screenshotBrightnessBoostText.setText(Setting.getScreenshotBrightnessBoostLabel()); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotBtnText.setText(Setting.getMpvScreenshotModeLabel()); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotBrightnessBoostText.setText(Setting.getMpvScreenshotBrightnessBoostLabel()); } catch (Throwable ignored) {}
        try { mBinding.mpvScreenshotContrastText.setText(Setting.getMpvScreenshotContrastLabel()); } catch (Throwable ignored) {}
        try { mBinding.hdrToSdrText.setText(getSwitch(Setting.isHdrToSdrEnabled())); } catch (Throwable ignored) {}
        try { mBinding.mpvFontScaleText.setText(Setting.getMpvFontScaleLabel()); } catch (Throwable ignored) {}
        try { mBinding.debugLogText.setText(getSwitch(com.fongmi.android.tv.utils.ModDebugLog.isRuntimeOverrideOn())); } catch (Throwable ignored) {}
        try { mBinding.webviewLiveText.setText(getSwitch(Setting.isWebViewLiveEnabled())); } catch (Throwable ignored) {}
        try { mBinding.recommendSourceText.setText(recommendSourceLabel()); } catch (Throwable ignored) {}
        try { mBinding.smbEntryText.setText(getSwitch(Setting.isSmbEntryEnabled())); } catch (Throwable ignored) {}
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

    private void setGlobalToast(View view) {
        Setting.putGlobalToast(!Setting.isGlobalToast());
        refreshTexts();
    }


    private void setAutoCheckUpdate(View view) {
        Setting.putAutoCheckUpdate(!Setting.isAutoCheckUpdate());
        refreshTexts();
    }

    private String exitClearCacheLabel() {
        int gb = Setting.getExitClearCacheGb();
        if (gb <= 0) {
            try { return getString(R.string.setting_exit_clear_cache_off); } catch (Throwable e) { return getSwitch(false); }
        }
        return gb + "G";
    }

    private void setExitClearCache(View view) {
        int cur = Setting.getExitClearCacheGb();
        int next;
        if (cur <= 0) next = 6;
        else if (cur == 6) next = 8;
        else if (cur == 8) next = 10;
        else next = 0;
        Setting.putExitClearCacheGb(next);
        refreshTexts();
    }

    private String subtitleColorLabel() {
        try {
            String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
            return Setting.getSubtitleColorLabel(lang != null && lang.startsWith("zh"));
        } catch (Throwable e) {
            return Setting.getSubtitleColorLabel(true);
        }
    }

    private String subtitleFontLabel() {
        try {
            String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
            return Setting.getSubtitleFontLabel(lang != null && lang.startsWith("zh"));
        } catch (Throwable e) {
            return Setting.getSubtitleFontLabel(true);
        }
    }

    private void setSubtitleColor(View view) {
        Setting.cycleSubtitleColor();
        try { com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.notifySubtitleStyleChanged(); } catch (Throwable ignored) {}
        refreshTexts();
    }

    private void setSubtitleFont(View view) {
        // MPV-BRANCH-ONLY: 点一下切下一个字体（义启粗楷 → 毁片和圆 → 方正细圆 → 系统默认 → 筑紫A圆D 循环）
        applySubtitleFont(SubtitleFontManager.nextSelectableIndex(Setting.getSubtitleFontIndex()));
    }

    /** MPV-BRANCH-ONLY: 应用字体（写配置 → 后台静默加载 → 通知运行时刷新）。 */
    private void applySubtitleFont(int index) {
        Setting.putSubtitleFontIndex(index);
        try {
            SubtitleFontManager.loadBuiltinFont(index);
        } catch (Throwable ignored) {
        }
        try {
            mBinding.subtitleFontText.setText(subtitleFontLabel());
        } catch (Throwable ignored) {
        }
        try {
            com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.notifySubtitleStyleChanged();
        } catch (Throwable ignored) {
        }
        try {
            com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle();
        } catch (Throwable ignored) {
        }
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




    private void setHomePush(View view) {
        Setting.putHomePush(!Setting.isHomePush());
        try { refreshTexts(); } catch (Throwable ignored) {}
        try { RefreshEvent.home(); } catch (Throwable ignored) {}
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
        try { refreshTexts(); } catch (Throwable ignored) {}
    }





    private void setWebViewLive(View view) {
        try {
            Setting.putWebViewLiveEnabled(!Setting.isWebViewLiveEnabled());
            mBinding.webviewLiveText.setText(getSwitch(Setting.isWebViewLiveEnabled()));
        } catch (Throwable ignored) {
        }
    }

    private void setLiveDefaultFullscreen(View view) {
        try {
            Setting.putLiveDefaultFullscreen(!Setting.isLiveDefaultFullscreen());
            mBinding.liveDefaultFullscreenText.setText(getSwitch(Setting.isLiveDefaultFullscreen()));
        } catch (Throwable ignored) {
        }
    }

    private void setEac3SoftDecode(View view) {
        try {
            Setting.putEac3SoftDecode(!Setting.isEac3SoftDecode());
            mBinding.eac3SoftDecodeText.setText(getSwitch(Setting.isEac3SoftDecode()));
        } catch (Throwable ignored) {
        }
    }

    private void setSearchPanFirst(View view) {
        try {
            int next = (Setting.getSearchResultPrefer() + 1) % 5;
            Setting.putSearchResultPrefer(next);
            mBinding.searchPanFirstText.setText(Setting.getSearchResultPreferText());
        } catch (Throwable ignored) {
        }
    }

    private void setPlayDirectPanPrefer(View view) {
        try {
            Setting.putPlayDirectPanPrefer(!Setting.isPlayDirectPanPrefer());
            mBinding.playDirectPanPreferText.setText(getSwitch(Setting.isPlayDirectPanPrefer()));
        } catch (Throwable ignored) {
        }
    }

    private void setHideHomeTypeMore(View view) {
        try {
            Setting.putHideHomeTypeMore(!Setting.isHideHomeTypeMore());
            mBinding.hideHomeTypeMoreText.setText(getSwitch(Setting.isHideHomeTypeMore()));
            try {
                com.fongmi.android.tv.event.RefreshEvent.typeMore();
            } catch (Throwable ignored2) {
            }
        } catch (Throwable ignored) {
        }
    }




    private void setHomeEnterLive(View view) {
        try {
            Setting.putHomeEnterLive(!Setting.isHomeEnterLive());
            mBinding.homeEnterLiveText.setText(getSwitch(Setting.isHomeEnterLive()));
        } catch (Throwable ignored) {
        }
    }

    private void setChildLock(View view) {
        try {
            // post 避免 focusable 行首次点击只抢焦点不触发业务
            view.post(() -> {
                try {
                    if (isFinishing()) return;
                    ChildLockSetupDialog.show(SettingPersonalActivity.this, () -> {
                        try {
                            mBinding.childLockText.setText(getSwitch(Setting.isChildLockEnabled()));
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }


    /** MPV-BRANCH-ONLY: 播放器临时调试日志开关，点一下立即生效 */
    private void setDebugLog(View view) {
        boolean next = !com.fongmi.android.tv.utils.ModDebugLog.isRuntimeOverrideOn();
        com.fongmi.android.tv.utils.ModDebugLog.setRuntimeOverride(next);
        try {
            mBinding.debugLogText.setText(getSwitch(next));
        } catch (Throwable ignored) {
        }
    }

    /** MPV-BRANCH-ONLY: 点一下切下一个倍率（1.00 / 1.15 / 1.20 / 1.30），立即作用于 mpv 字幕 */
    private void setMpvFontScale(View view) {
        try {
            Setting.cycleMpvFontScale();
        } catch (Throwable ignored) {
        }
        try {
            mBinding.mpvFontScaleText.setText(Setting.getMpvFontScaleLabel());
        } catch (Throwable ignored) {
        }
        // EXO 侧：走 media3 SubtitleView 的 fractional text size（与 MPV 共用同一倍率）
        try {
            com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle();
        } catch (Throwable ignored) {
        }
        boolean applied = false;
        try {
            applied = com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
        } catch (Throwable ignored) {
        }
        // MPV-BRANCH-ONLY: mpv 还没 file-loaded 时上面这次会直接返回 false，
        // 延迟再补两次，保证「起播中改倍率」也能立刻生效（就绪后 modForcePreferredSubtitleTrack 还会再兜一次）
        if (!applied) {
            try {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(() -> {
                    try {
                        com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
                    } catch (Throwable ignored) {
                    }
                }, 1500L);
                h.postDelayed(() -> {
                    try {
                        com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
                    } catch (Throwable ignored) {
                    }
                }, 4000L);
            } catch (Throwable ignored) {
            }
        }
        refreshTexts();
    }

    private void setKaiTiScale(View view) {
        Setting.cycleKaiTiScale();
        try { mBinding.kaitiScaleText.setText(Setting.getKaiTiScaleLabel(true)); } catch (Throwable ignored) {}
        try { com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.notifySubtitleStyleChanged(); } catch (Throwable ignored) {}
        try { com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle(); } catch (Throwable ignored) {}
    }

    private void setPlaybackScreenshotBtn(View view) {
        try {
            Setting.cycleExoScreenshotMode();
            mBinding.playbackScreenshotBtnText.setText(Setting.getExoScreenshotModeLabel());
            try { com.fongmi.android.tv.utils.Notify.show(Setting.getExoScreenshotModeLabel()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private void setScreenshotBrightnessBoost(View view) {
        try {
            Setting.cycleScreenshotBrightnessBoost();
            mBinding.screenshotBrightnessBoostText.setText(Setting.getScreenshotBrightnessBoostLabel());
            try { com.fongmi.android.tv.utils.Notify.show(Setting.getScreenshotBrightnessBoostLabel()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private void setHdrToSdr(View view) {
        try {
            Setting.putHdrToSdrEnabled(!Setting.isHdrToSdrEnabled());
            mBinding.hdrToSdrText.setText(getSwitch(Setting.isHdrToSdrEnabled()));
        } catch (Throwable ignored) {
        }
    }


    private void setMpvScreenshotBtn(View view) {
        try {
            Setting.cycleMpvScreenshotMode();
            mBinding.mpvScreenshotBtnText.setText(Setting.getMpvScreenshotModeLabel());
        } catch (Throwable ignored) {
        }
    }

    private void setMpvScreenshotBrightnessBoost(View view) {
        try {
            Setting.cycleMpvScreenshotBrightnessBoost();
            mBinding.mpvScreenshotBrightnessBoostText.setText(Setting.getMpvScreenshotBrightnessBoostLabel());
        } catch (Throwable ignored) {
        }
    }

    /** MPV截图对比度：点一下切下一个等级（+5 … +35 → 关），只影响之后生成的截图 */
    private void setMpvScreenshotContrast(View view) {
        try {
            Setting.cycleMpvScreenshotContrast();
            mBinding.mpvScreenshotContrastText.setText(Setting.getMpvScreenshotContrastLabel());
        } catch (Throwable ignored) {
        }
    }

    private void setSmbEntry(View view) {
        Setting.putSmbEntryEnabled(!Setting.isSmbEntryEnabled());
        refreshTexts();
        try { RefreshEvent.home(); } catch (Throwable ignored) {}
    }

}
