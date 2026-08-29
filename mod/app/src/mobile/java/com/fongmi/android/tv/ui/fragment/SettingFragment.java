package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.AutoBackupPolicy;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.AboutDialog;
import com.fongmi.android.tv.ui.dialog.AppearanceDialog;
import com.fongmi.android.tv.ui.dialog.ChoiceDialog;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.BackupProgressDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingFragment extends BaseFragment implements ConfigListener, SiteListener, LiveListener {

    private String[] globalHistoryMode;

    private FragmentSettingBinding mBinding;

    public static SettingFragment newInstance() {
        return new SettingFragment();
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

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        setWallText();
        mBinding.versionText.setText(AppVersion.fullName());
        setOtherText();
        setCacheText();
    }

    private void setOtherText() {
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.autoBackupText.setText(getSwitch(isAutoBackupEnabled()));
        mBinding.subtitleAutoMatchText.setText(getSwitch(Setting.isSubtitleAutoMatchEnabled()));
        mBinding.subtitleLanguageText.setText(getSubtitleLanguageLabel());
        mBinding.subtitleAssrtTokenText.setText(getSubtitleTokenLabel());
        refreshAiTexts();
        mBinding.episodeHistoryText.setText(getSwitch(Setting.isEpisodeHistory()));
        mBinding.playBackToDetailText.setText(getSwitch(Setting.isPlayBackToDetail()));
        mBinding.searchThreadText.setText(String.valueOf(Setting.getSearchThread()));
        globalHistoryMode = getResources().getStringArray(R.array.select_global_history_mode);
        int _gh = Setting.getGlobalHistoryMode();
        if (globalHistoryMode != null && globalHistoryMode.length > 0) {
            if (_gh < 0 || _gh >= globalHistoryMode.length) _gh = 0;
            mBinding.globalHistoryText.setText(globalHistoryMode[_gh]);
        }
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
        mBinding.appearance.setOnClickListener(this::onAppearance);
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
        mBinding.aiConfig.setOnClickListener(this::openAiConfig);
        mBinding.episodeHistory.setOnClickListener(this::setEpisodeHistory);
        mBinding.playBackToDetail.setOnClickListener(this::setPlayBackToDetail);
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
            requireView().post(() -> PermissionUtil.requestFile(this, allGranted -> load(config)));
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
                Notify.progress(requireActivity());
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
        SiteDialog.create().search().change().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        getRoot().change(2);
    }

    private void onDanmaku(View view) {
        getRoot().change(4);
    }

    private void onEnhance(View view) {
        getRoot().change(3);
    }

    private void onAppearance(View view) {
        AppearanceDialog.show(this);
    }


    private void onVersion(View view) {
        AboutDialog.show(requireActivity(), () -> Updater.create().force().start(requireActivity()));
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



    private void setEpisodeHistory(View view) {
        Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
        mBinding.episodeHistoryText.setText(getSwitch(Setting.isEpisodeHistory()));
    }

    private void setPlayBackToDetail(View view) {
        Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
        mBinding.playBackToDetailText.setText(getSwitch(Setting.isPlayBackToDetail()));
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
        globalHistoryMode = getResources().getStringArray(R.array.select_global_history_mode);
        int size = globalHistoryMode == null || globalHistoryMode.length == 0 ? 1 : globalHistoryMode.length;
        int next = (Setting.getGlobalHistoryMode() + 1) % size;
        Setting.putGlobalHistoryMode(next);
        int idx = Setting.getGlobalHistoryMode();
        if (idx < 0 || idx >= size) idx = 0;
        mBinding.globalHistoryText.setText(globalHistoryMode[idx]);
    }

    private void setDoh(View view) {
        ChoiceDialog.showSingle(this, R.string.setting_doh, getDohList(), getDohIndex(), which -> {
            setDoh(VodConfig.get().getDoh().get(which));
        });
    }

    private void setDoh(Doh doh) {
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
            BackupProgressDialog progress = BackupProgressDialog.open(getParentFragmentManager(), "备份应用数据");
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
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }));
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

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        setCacheText();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
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












    private void refreshAiTexts() {
        try {
            boolean ready = Setting.isAiRecommendReady();
            mBinding.aiConfigText.setText(ready ? R.string.setting_ai_status_on : R.string.setting_ai_status_off);
        } catch (Throwable ignored) {
        }
    }

    private void openAiConfig(View view) {
        android.app.Activity activity = getActivitySafe();
        if (!(activity instanceof androidx.fragment.app.FragmentActivity)) return;
        com.fongmi.android.tv.ui.dialog.AiConfigDialog.show(
                (androidx.fragment.app.FragmentActivity) activity,
                config -> refreshAiTexts());
    }


    private android.app.Activity getActivitySafe() {
        try {
            return requireActivity();
        } catch (Throwable e) {
            try {
                return getActivity();
            } catch (Throwable ignored) {
                return null;
            }
        }
    }


}
