package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.AutoBackupPolicy;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.AboutDialog;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.BackupProgressDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener {

    private ActivitySettingBinding mBinding;
    private String[] size;
    private String[] language;
    private String[] globalHistoryMode;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.vod.requestFocus();
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        setWallText();
        mBinding.versionText.setText(AppVersion.fullName());
        setCacheText();
        setOtherText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.autoBackupText.setText(getSwitch(isAutoBackupEnabled()));
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
        mBinding.subtitleLanguageText.setText(getSubtitleLanguageLabel());
        mBinding.subtitleAssrtTokenText.setText(getSubtitleTokenLabel());
        refreshAiTexts();
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        mBinding.homeVodAutoLoadText.setText(getSwitch(Setting.isHomeVodAutoLoad()));
        mBinding.episodeHistoryText.setText(getSwitch(Setting.isEpisodeHistory()));
        mBinding.playBackToDetailText.setText(getSwitch(Setting.isPlayBackToDetail()));
        mBinding.homeSiteLockText.setText(getSwitch(Setting.isHomeSiteLock()));
        mBinding.searchThreadText.setText(String.valueOf(Setting.getSearchThread()));
        mBinding.globalHistoryText.setText((globalHistoryMode = getResources().getStringArray(R.array.select_global_history_mode))[Setting.getGlobalHistoryMode()]);
        mBinding.languageText.setText((language = ResUtil.getStringArray(R.array.select_language))[Setting.getLanguageIndex()]);
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.language.setOnClickListener(this::setLanguage);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.enhance.setOnClickListener(this::onEnhance);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.autoBackup.setOnClickListener(this::setAutoBackup);
        mBinding.subtitleAutoMatch.setOnClickListener(this::setSubtitleAutoMatch);
        mBinding.subtitleLanguage.setOnClickListener(this::setSubtitleLanguage);
        mBinding.subtitleAssrtToken.setOnClickListener(this::setSubtitleAssrtToken);
        mBinding.aiRecommendation.setOnClickListener(this::setAiRecommendation);
        mBinding.aiEndpoint.setOnClickListener(this::setAiEndpoint);
        mBinding.aiApiKey.setOnClickListener(this::setAiApiKey);
        mBinding.aiModel.setOnClickListener(this::setAiModel);
        mBinding.aiFetch.setOnClickListener(this::fetchAiRecommend);
        mBinding.homeHistory.setOnClickListener(this::setHomeHistory);
        mBinding.homeVodAutoLoad.setOnClickListener(this::setHomeVodAutoLoad);
        mBinding.episodeHistory.setOnClickListener(this::setEpisodeHistory);
        mBinding.playBackToDetail.setOnClickListener(this::setPlayBackToDetail);
        mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);
        mBinding.searchThread.setOnClickListener(this::setSearchThread);
        mBinding.globalHistory.setOnClickListener(this::setGlobalHistory);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
    }

    @Override
    public void setConfig(Config config) {
        if (config == null) return;
        String url = config.getUrl();
        if (!TextUtils.isEmpty(url) && url.startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().action().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.create().action().show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        SettingPlayerActivity.start(this);
    }

    private void onEnhance(View view) {
        SettingEnhanceActivity.start(this);
    }

    private void onDanmaku(View view) {
        SettingDanmakuActivity.start(this);
    }

    private void onVersion(View view) {
        AboutDialog.show(this, () -> Updater.create().force().start(this));
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.nextDefaultWall());
        Setting.putWallType(0);
        setWallText();
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private boolean isAutoBackupEnabled() {
        return AutoBackupPolicy.isEffective(Setting.isAutoBackup(), Setting.hasFileAccess());
    }

    private void setAutoBackup(View view) {
        if (isAutoBackupEnabled()) {
            Setting.putAutoBackup(false);
            mBinding.autoBackupText.setText(getSwitch(false));
            return;
        }
        PermissionUtil.requestFile(this, allGranted -> {
            if (!allGranted) {
                Notify.show(R.string.backup_permission_denied);
                return;
            }
            Setting.putAutoBackup(true);
            mBinding.autoBackupText.setText(getSwitch(true));
        });
    }

    private void setHomeHistory(View view) {
        Setting.putHomeHistory(!Setting.isHomeHistory());
        mBinding.homeHistoryText.setText(getSwitch(Setting.isHomeHistory()));
        RefreshEvent.history();
        RefreshEvent.home();
    }

    private void setHomeVodAutoLoad(View view) {
        Setting.putHomeVodAutoLoad(!Setting.isHomeVodAutoLoad());
        mBinding.homeVodAutoLoadText.setText(getSwitch(Setting.isHomeVodAutoLoad()));
        RefreshEvent.home();
    }

    private void setEpisodeHistory(View view) {
        Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
        mBinding.episodeHistoryText.setText(getSwitch(Setting.isEpisodeHistory()));
    }

    private void setPlayBackToDetail(View view) {
        Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
        mBinding.playBackToDetailText.setText(getSwitch(Setting.isPlayBackToDetail()));
    }

    private void setHomeSiteLock(View view) {
        Setting.putHomeSiteLock(!Setting.isHomeSiteLock());
        mBinding.homeSiteLockText.setText(getSwitch(Setting.isHomeSiteLock()));
    }

    private void setSearchThread(View view) {
    int current = Setting.getSearchThread();
    int next;

    switch (current) {
        case 15:
            next = 20;
            break;
        case 20:
            next = 40;
            break;
        case 40:
            next = 60;
            break;
        case 60:
            next = 80;
            break;
        case 80:
        default:
            next = 15;
            break;
    }

    Setting.putSearchThread(next);
    mBinding.searchThreadText.setText(String.valueOf(Setting.getSearchThread()));
}

    private void setGlobalHistory(View view) {
        Setting.putGlobalHistoryMode((Setting.getGlobalHistoryMode() + 1) % 3);
        mBinding.globalHistoryText.setText((globalHistoryMode = getResources().getStringArray(R.array.select_global_history_mode))[Setting.getGlobalHistoryMode()]);
        RefreshEvent.history();
    }

    private void setSize(View view) {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        mBinding.sizeText.setText(size[index]);
        PlayerSetting.putSize(index);
        RefreshEvent.size();
    }

    private void setLanguage(View view) {
        int index = (Setting.getLanguageIndex() + 1) % language.length;
        Setting.putLanguageIndex(index);
        RefreshEvent.language();
    }

    private void setDoh(View view) {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> {
            BackupProgressDialog progress = BackupProgressDialog.open(getSupportFragmentManager(), "备份应用数据");
            AppDatabase.backup(new Callback() {
            @Override
            public void success() {
                progress.finish();
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                progress.finish();
                Notify.show(R.string.backup_fail);
            }
            }, progress::update);
        });
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) {
            setWallText();
            return;
        }
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        setWallText();
    }

    private void setWallText() {
        mBinding.wallUrl.setText(Setting.getWallDesc(WallConfig.getDesc()));
    }


    private String getSubtitleLanguageLabel() {
        String[] labels = getResources().getStringArray(R.array.select_subtitle_language);
        String[] values = getResources().getStringArray(R.array.select_subtitle_language_value);
        String cur = Setting.getSubtitlePreferredLanguage();
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) return labels[i];
        return cur;
    }

    private String getSubtitleTokenLabel() {
        return getString(android.text.TextUtils.isEmpty(Setting.getSubtitleAssrtToken()) ? R.string.setting_unconfigured : R.string.setting_configured);
    }

    private void setSubtitleAutoMatch(View view) {
        Setting.putSubtitleAutoMatchEnabled(!Setting.isSubtitleAutoMatchEnabled());
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
    }

    private void setSubtitleLanguage(View view) {
        String[] labels = getResources().getStringArray(R.array.select_subtitle_language);
        String[] values = getResources().getStringArray(R.array.select_subtitle_language_value);
        int idx = 0;
        String cur = Setting.getSubtitlePreferredLanguage();
        for (int i = 0; i < values.length; i++) if (values[i].equals(cur)) idx = i;
        idx = (idx + 1) % values.length;
        Setting.putSubtitlePreferredLanguage(values[idx]);
        mBinding.subtitleLanguageText.setText(labels[idx]);
    }

    private void setSubtitleAssrtToken(View view) {
        final android.widget.EditText input = new android.widget.EditText(view.getContext());
        input.setHint(R.string.subtitle_token_hint);
        input.setText(Setting.getSubtitleAssrtToken());
        input.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(view.getContext())
                .setTitle(R.string.player_subtitle_assrt_token)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    Setting.putSubtitleAssrtToken(input.getText() == null ? "" : input.getText().toString());
                    mBinding.subtitleAssrtTokenText.setText(getSubtitleTokenLabel());
                })
                .show();
    }


    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return getString(R.string.setting_unconfigured);
        if (key.length() <= 8) return getString(R.string.setting_configured);
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void refreshAiTexts() {
        mBinding.aiRecommendationText.setText(getSwitch(Setting.isAiRecommendation()));
        mBinding.aiEndpointText.setText(Setting.getAiEndpoint());
        mBinding.aiApiKeyText.setText(maskKey(Setting.getAiApiKey()));
        mBinding.aiModelText.setText(Setting.getAiModel());
    }

    private void setAiRecommendation(View view) {
        Setting.putAiRecommendation(!Setting.isAiRecommendation());
        mBinding.aiRecommendationText.setText(getSwitch(Setting.isAiRecommendation()));
    }

    private void setAiEndpoint(View view) {
        final android.widget.EditText input = new android.widget.EditText(view.getContext());
        input.setText(Setting.getAiEndpoint());
        input.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(view.getContext())
                .setTitle(R.string.setting_ai_endpoint)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    Setting.putAiEndpoint(input.getText() == null ? "" : input.getText().toString());
                    mBinding.aiEndpointText.setText(Setting.getAiEndpoint());
                })
                .show();
    }

    private void setAiApiKey(View view) {
        final android.widget.EditText input = new android.widget.EditText(view.getContext());
        input.setText(Setting.getAiApiKey());
        input.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(view.getContext())
                .setTitle(R.string.setting_ai_api_key)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    Setting.putAiApiKey(input.getText() == null ? "" : input.getText().toString());
                    mBinding.aiApiKeyText.setText(maskKey(Setting.getAiApiKey()));
                })
                .show();
    }

    private void setAiModel(View view) {
        final android.widget.EditText input = new android.widget.EditText(view.getContext());
        input.setText(Setting.getAiModel());
        input.setSingleLine(true);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(view.getContext())
                .setTitle(R.string.setting_ai_model)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    Setting.putAiModel(input.getText() == null ? "" : input.getText().toString());
                    mBinding.aiModelText.setText(Setting.getAiModel());
                })
                .show();
    }

    private void fetchAiRecommend(View view) {
        if (!Setting.isAiRecommendReady()) {
            com.fongmi.android.tv.utils.Notify.show(R.string.ai_recommend_not_ready);
            return;
        }
        com.fongmi.android.tv.utils.Notify.show(R.string.ai_recommend_loading);
        final android.app.Activity activity = getActivitySafe();
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> items =
                        com.fongmi.android.tv.service.AiRecommendService.load();
                com.fongmi.android.tv.App.post(() -> showAiRecommendList(activity, items));
            } catch (Exception e) {
                com.fongmi.android.tv.App.post(() ->
                        com.fongmi.android.tv.utils.Notify.show(R.string.ai_recommend_failed));
            }
        });
    }

    private android.app.Activity getActivitySafe() {
        if (this instanceof android.app.Activity) return (android.app.Activity) this;
        try {
            return requireActivity();
        } catch (Throwable e) {
            return null;
        }
    }

    private void showAiRecommendList(android.app.Activity activity, java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> items) {
        if (activity == null || activity.isFinishing()) return;
        if (items == null || items.isEmpty()) {
            com.fongmi.android.tv.utils.Notify.show(R.string.ai_recommend_empty);
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.ai_recommend_title, items.size()))
                .setItems(labels, (d, which) -> {
                    String title = items.get(which).title;
                    try {
                        com.fongmi.android.tv.ui.activity.SearchActivity.start(activity, title);
                    } catch (Throwable e) {
                        com.fongmi.android.tv.utils.Notify.show(title);
                    }
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

}
