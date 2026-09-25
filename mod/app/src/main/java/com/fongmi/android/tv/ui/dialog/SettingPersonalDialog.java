package com.fongmi.android.tv.ui.dialog;

import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 个性设置。首页源锁定 / 默认加载点播 / 首页最近观看 仅 TV（leanback）显示。
 */
public final class SettingPersonalDialog {

    private SettingPersonalDialog() {
    }

    public static void show(FragmentActivity activity) {
        if (activity == null) return;
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int rowPad = (int) (12 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        String[] historyModes = safeArray(activity, R.array.select_global_history_mode);
        String[] subLabels = safeArray(activity, R.array.select_subtitle_language);
        String[] subValues = safeArray(activity, R.array.select_subtitle_language_value);

        Row autoBackup = row(activity, rowPad, R.string.setting_auto_backup);
        Row episodeHistory = row(activity, rowPad, R.string.setting_episode_history);
        Row globalHistory = row(activity, rowPad, R.string.setting_global_history);
        Row playBack = row(activity, rowPad, R.string.setting_play_back_to_detail);
        Row searchThread = row(activity, rowPad, R.string.setting_search_thread);
        Row subAuto = row(activity, rowPad, R.string.setting_subtitle_auto_match);
        Row subLang = row(activity, rowPad, R.string.setting_subtitle_language);

        boolean tvOnly = isTv(activity);
        Row homeSiteLock = tvOnly ? row(activity, rowPad, R.string.setting_home_site_lock) : null;
        Row homeVodAutoLoad = tvOnly ? row(activity, rowPad, R.string.setting_home_vod_auto_load) : null;
        Row homeHistory = tvOnly ? row(activity, rowPad, R.string.setting_home_history) : null;

        Runnable refresh = () -> {
            autoBackup.value.setText(onOff(activity, Setting.isAutoBackup()));
            episodeHistory.value.setText(onOff(activity, Setting.isEpisodeHistory()));
            int gh = Setting.getGlobalHistoryMode();
            String ghLabel = (historyModes != null && gh >= 0 && gh < historyModes.length) ? historyModes[gh] : String.valueOf(gh);
            globalHistory.value.setText(ghLabel);
            playBack.value.setText(onOff(activity, Setting.isPlayBackToDetail()));
            searchThread.value.setText(String.valueOf(Setting.getSearchThread()));
            subAuto.value.setText(onOff(activity, Setting.isSubtitleAutoMatchEnabled()));
            String lang = Setting.getSubtitlePreferredLanguage();
            String langLabel = lang;
            if (subValues != null && subLabels != null) {
                for (int i = 0; i < subValues.length && i < subLabels.length; i++) {
                    if (subValues[i].equals(lang)) {
                        langLabel = subLabels[i];
                        break;
                    }
                }
            }
            subLang.value.setText(langLabel);
            if (homeSiteLock != null) homeSiteLock.value.setText(onOff(activity, Setting.isHomeSiteLock()));
            if (homeVodAutoLoad != null) homeVodAutoLoad.value.setText(onOff(activity, Setting.isHomeVodAutoLoad()));
            if (homeHistory != null) homeHistory.value.setText(onOff(activity, Setting.isHomeHistory()));
        };
        refresh.run();

        autoBackup.root.setOnClickListener(v -> {
            Setting.putAutoBackup(!Setting.isAutoBackup());
            refresh.run();
        });
        episodeHistory.root.setOnClickListener(v -> {
            Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
            refresh.run();
        });
        globalHistory.root.setOnClickListener(v -> {
            int size = historyModes == null || historyModes.length == 0 ? 3 : historyModes.length;
            Setting.putGlobalHistoryMode((Setting.getGlobalHistoryMode() + 1) % size);
            refresh.run();
        });
        playBack.root.setOnClickListener(v -> {
            Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
            refresh.run();
        });
        searchThread.root.setOnClickListener(v -> {
            int[] options = new int[]{1, 2, 4, 8, 16};
            int cur = Setting.getSearchThread();
            int idx = 0;
            for (int i = 0; i < options.length; i++) if (options[i] == cur) idx = i;
            Setting.putSearchThread(options[(idx + 1) % options.length]);
            refresh.run();
        });
        subAuto.root.setOnClickListener(v -> {
            Setting.putSubtitleAutoMatchEnabled(!Setting.isSubtitleAutoMatchEnabled());
            refresh.run();
        });
        subLang.root.setOnClickListener(v -> {
            if (subValues == null || subValues.length == 0) return;
            String cur = Setting.getSubtitlePreferredLanguage();
            int idx = 0;
            for (int i = 0; i < subValues.length; i++) if (subValues[i].equals(cur)) idx = i;
            Setting.putSubtitlePreferredLanguage(subValues[(idx + 1) % subValues.length]);
            refresh.run();
        });
        if (homeSiteLock != null) {
            homeSiteLock.root.setOnClickListener(v -> {
                Setting.putHomeSiteLock(!Setting.isHomeSiteLock());
                refresh.run();
            });
        }
        if (homeVodAutoLoad != null) {
            homeVodAutoLoad.root.setOnClickListener(v -> {
                Setting.putHomeVodAutoLoad(!Setting.isHomeVodAutoLoad());
                refresh.run();
            });
        }
        if (homeHistory != null) {
            homeHistory.root.setOnClickListener(v -> {
                Setting.putHomeHistory(!Setting.isHomeHistory());
                refresh.run();
            });
        }

        root.addView(autoBackup.root);
        root.addView(episodeHistory.root);
        root.addView(globalHistory.root);
        root.addView(playBack.root);
        root.addView(searchThread.root);
        if (homeSiteLock != null) root.addView(homeSiteLock.root);
        if (homeVodAutoLoad != null) root.addView(homeVodAutoLoad.root);
        if (homeHistory != null) root.addView(homeHistory.root);
        root.addView(subAuto.root);
        root.addView(subLang.root);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_personal)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
    }

    private static boolean isTv(FragmentActivity activity) {
        try {
            if ("leanback".equalsIgnoreCase(BuildConfig.FLAVOR_mode)) return true;
        } catch (Throwable ignored) {
        }
        int uiMode = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    private static final class Row {
        final LinearLayout root;
        final TextView value;

        Row(LinearLayout root, TextView value) {
            this.root = root;
            this.value = value;
        }
    }

    private static Row row(FragmentActivity activity, int pad, int titleRes) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        root.setLayoutParams(lp);

        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        value.setGravity(Gravity.END);
        value.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(title);
        root.addView(value);
        return new Row(root, value);
    }

    private static String onOff(FragmentActivity activity, boolean on) {
        try {
            return activity.getString(on ? R.string.setting_on : R.string.setting_off);
        } catch (Throwable e) {
            return on ? "ON" : "OFF";
        }
    }

    private static String[] safeArray(FragmentActivity activity, int id) {
        try {
            return activity.getResources().getStringArray(id);
        } catch (Throwable e) {
            return new String[0];
        }
    }
}
