package com.fongmi.android.tv.ui.dialog;

import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 个性设置：自动备份 / 集数历史 / 全局历史 / 返回详情 / 搜索线程 /
 * 自动匹配字幕 / 字幕偏好语言
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

        TextView autoBackup = row(activity, rowPad);
        TextView episodeHistory = row(activity, rowPad);
        TextView globalHistory = row(activity, rowPad);
        TextView playBack = row(activity, rowPad);
        TextView searchThread = row(activity, rowPad);
        TextView subAuto = row(activity, rowPad);
        TextView subLang = row(activity, rowPad);

        Runnable refresh = () -> {
            autoBackup.setText(label(activity, R.string.setting_auto_backup, onOff(activity, Setting.isAutoBackup())));
            episodeHistory.setText(label(activity, R.string.setting_episode_history, onOff(activity, Setting.isEpisodeHistory())));
            int gh = Setting.getGlobalHistoryMode();
            String ghLabel = (historyModes != null && gh >= 0 && gh < historyModes.length) ? historyModes[gh] : String.valueOf(gh);
            globalHistory.setText(label(activity, R.string.setting_global_history, ghLabel));
            playBack.setText(label(activity, R.string.setting_play_back_to_detail, onOff(activity, Setting.isPlayBackToDetail())));
            searchThread.setText(label(activity, R.string.setting_search_thread, String.valueOf(Setting.getSearchThread())));
            subAuto.setText(label(activity, R.string.setting_subtitle_auto_match, onOff(activity, Setting.isSubtitleAutoMatchEnabled())));
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
            subLang.setText(label(activity, R.string.setting_subtitle_language, langLabel));
        };
        refresh.run();

        autoBackup.setOnClickListener(v -> {
            Setting.putAutoBackup(!Setting.isAutoBackup());
            refresh.run();
        });
        episodeHistory.setOnClickListener(v -> {
            Setting.putEpisodeHistory(!Setting.isEpisodeHistory());
            refresh.run();
        });
        globalHistory.setOnClickListener(v -> {
            int size = historyModes == null || historyModes.length == 0 ? 3 : historyModes.length;
            Setting.putGlobalHistoryMode((Setting.getGlobalHistoryMode() + 1) % size);
            refresh.run();
        });
        playBack.setOnClickListener(v -> {
            Setting.putPlayBackToDetail(!Setting.isPlayBackToDetail());
            refresh.run();
        });
        searchThread.setOnClickListener(v -> {
            int[] options = new int[]{1, 2, 4, 8, 16};
            int cur = Setting.getSearchThread();
            int idx = 0;
            for (int i = 0; i < options.length; i++) if (options[i] == cur) idx = i;
            Setting.putSearchThread(options[(idx + 1) % options.length]);
            refresh.run();
        });
        subAuto.setOnClickListener(v -> {
            Setting.putSubtitleAutoMatchEnabled(!Setting.isSubtitleAutoMatchEnabled());
            refresh.run();
        });
        subLang.setOnClickListener(v -> {
            if (subValues == null || subValues.length == 0) return;
            String cur = Setting.getSubtitlePreferredLanguage();
            int idx = 0;
            for (int i = 0; i < subValues.length; i++) if (subValues[i].equals(cur)) idx = i;
            Setting.putSubtitlePreferredLanguage(subValues[(idx + 1) % subValues.length]);
            refresh.run();
        });

        root.addView(autoBackup);
        root.addView(episodeHistory);
        root.addView(globalHistory);
        root.addView(playBack);
        root.addView(searchThread);
        root.addView(subAuto);
        root.addView(subLang);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_personal)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
    }

    private static String label(FragmentActivity activity, int titleRes, String value) {
        return activity.getString(titleRes) + "  ·  " + value;
    }

    private static TextView row(FragmentActivity activity, int pad) {
        TextView tv = new TextView(activity);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setPadding(pad, pad, pad, pad);
        tv.setBackgroundColor(0x18FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = pad / 2;
        tv.setLayoutParams(lp);
        return tv;
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
