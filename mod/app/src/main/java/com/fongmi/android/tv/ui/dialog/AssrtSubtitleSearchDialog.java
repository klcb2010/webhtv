package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

public final class AssrtSubtitleSearchDialog {

    private AssrtSubtitleSearchDialog() {
    }

    public static void show(FragmentActivity activity, PlayerManager player, String defaultKeyword) {
        if (activity == null || player == null) return;
        // 迅雷不需要 Token；有 Token 时一并搜射手
        String keyword = defaultKeyword == null ? "" : defaultKeyword.trim();
        if (TextUtils.isEmpty(keyword) && activity instanceof TrackDialog.SubtitleSearchHost host) {
            keyword = host.getSubtitleSearchKeyword();
            if (keyword == null) keyword = "";
        }

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint(R.string.search_keyword);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        if (!TextUtils.isEmpty(keyword)) {
            input.setText(keyword);
            input.setSelection(keyword.length());
        }

        final String prefill = keyword;
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.subtitle_manual_search)
                .setView(input)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();
        dialog.setOnShowListener(d -> {
            // 再保险填一次（部分机型 setText 在 show 前会被清）
            if (TextUtils.isEmpty(input.getText()) && !TextUtils.isEmpty(prefill)) {
                input.setText(prefill);
                input.setSelection(prefill.length());
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String q = input.getText() == null ? "" : input.getText().toString().trim();
                if (TextUtils.isEmpty(q)) {
                    Notify.show(R.string.search_keyword);
                    return;
                }
                dialog.dismiss();
                search(activity, player, q);
            });
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
                }
                return false;
            });
        });
        dialog.show();
    }

    private static void search(FragmentActivity activity, PlayerManager player, String query) {
        Notify.show(R.string.subtitle_manual_searching);
        Task.execute(() -> {
            try {
                List<AssrtSubtitleMatch.Item> items = AssrtSubtitleMatch.searchList(query);
                App.post(() -> {
                    if (activity.isFinishing()) return;
                    if (items == null || items.isEmpty()) {
                        Notify.show(R.string.subtitle_manual_search_empty);
                        return;
                    }
                    showCandidates(activity, player, items);
                });
            } catch (Exception e) {
                App.post(() -> Notify.show(R.string.subtitle_manual_search_failed));
            }
        });
    }

    private static void showCandidates(FragmentActivity activity, PlayerManager player, List<AssrtSubtitleMatch.Item> items) {
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label();
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.subtitle_manual_select_title, items.size()))
                .setItems(labels, (d, which) -> resolve(activity, player, items.get(which)))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private static void resolve(FragmentActivity activity, PlayerManager player, AssrtSubtitleMatch.Item item) {
        Notify.show(R.string.subtitle_manual_resolving);
        Task.execute(() -> {
            try {
                File file = AssrtSubtitleMatch.downloadItem(item);
                App.post(() -> {
                    if (activity.isFinishing()) return;
                    if (file == null || !file.isFile()) {
                        Notify.show(R.string.subtitle_manual_apply_failed);
                        return;
                    }
                    if (player == null || player.isEmpty()) {
                        Notify.show(R.string.subtitle_manual_inactive);
                        return;
                    }
                    String display = AssrtSubtitleMatch.displayName(item);
                    String format = PlayerHelper.getSubtitleMimeType(item.name);
                    if (TextUtils.isEmpty(format)) format = PlayerHelper.getSubtitleMimeType(file.getName());
                    Sub sub = Sub.create(display, file.getAbsolutePath(), item.lang, format);
                    sub.setFlag(androidx.media3.common.C.SELECTION_FLAG_FORCED);
                    player.setSub(sub);
                    Notify.show(activity.getString(R.string.subtitle_manual_applied, display));
                });
            } catch (Exception e) {
                App.post(() -> Notify.show(R.string.subtitle_manual_apply_failed));
            }
        });
    }
}
