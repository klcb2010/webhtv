package com.fongmi.android.tv.ui.dialog;

import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 个性设置：自动备份 / 集数历史 / 全局历史 / 返回详情 / 搜索线程
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

        String[] modes = safeArray(activity, R.array.select_global_history_mode);

        TextView autoBackup = row(activity, rowPad);
        TextView episodeHistory = row(activity, rowPad);
        TextView globalHistory = row(activity, rowPad);
        TextView playBack = row(activity, rowPad);
        TextView searchThread = row(activity, rowPad);

        Runnable refresh = () -> {
            autoBackup.setText(activity.getString(R.string.setting_auto_backup) + "  ·  " + onOff(activity, Setting.isAutoBackup()));
            episodeHistory.setText(activity.getString(R.string.setting_episode_history) + "  ·  " + onOff(activity, Setting.isEpisodeHistory()));
            int gh = Setting.getGlobalHistoryMode();
            String ghLabel = (modes != null && gh >= 0 && gh < modes.length) ? modes[gh] : String.valueOf(gh);
            globalHistory.setText(activity.getString(R.string.setting_global_history) + "  ·  " + ghLabel);
            playBack.setText(activity.getString(R.string.setting_play_back_to_detail) + "  ·  " + onOff(activity, Setting.isPlayBackToDetail()));
            searchThread.setText(activity.getString(R.string.setting_search_thread) + "  ·  " + Setting.getSearchThread());
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
            int size = modes == null || modes.length == 0 ? 3 : modes.length;
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
            int next = options[(idx + 1) % options.length];
            Setting.putSearchThread(next);
            refresh.run();
        });

        root.addView(autoBackup);
        root.addView(episodeHistory);
        root.addView(globalHistory);
        root.addView(playBack);
        root.addView(searchThread);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_personal)
                .setView(scroll)
                .setPositiveButton(R.string.dialog_positive, null)
                .show();
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
            return new String[]{"Off", "Auto", "Search"};
        }
    }
}
