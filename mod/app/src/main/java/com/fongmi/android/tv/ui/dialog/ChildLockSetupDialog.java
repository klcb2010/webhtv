package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;

/**
 * 个性设置 → 儿童管理：开关 + 设密 / 关锁验密。
 * UI 含密码显示/隐藏；功能逻辑不变。
 */
public final class ChildLockSetupDialog {

    private ChildLockSetupDialog() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_child_lock_setup, null);
        SwitchCompat sw = root.findViewById(R.id.childLockSwitch);
        EditText pwd = root.findViewById(R.id.childLockPassword);
        EditText confirm = root.findViewById(R.id.childLockPasswordConfirm);
        View confirmLabel = root.findViewById(R.id.childLockConfirmLabel);
        View confirmRow = root.findViewById(R.id.childLockConfirmRow);
        ImageButton togglePwd = root.findViewById(R.id.childLockPasswordToggle);
        ImageButton toggleConfirm = root.findViewById(R.id.childLockPasswordConfirmToggle);

        boolean enabled = Setting.isChildLockEnabled();
        sw.setChecked(enabled);
        updateConfirmVisibility(sw, confirm, confirmLabel, confirmRow);
        bindPasswordToggle(pwd, togglePwd);
        bindPasswordToggle(confirm, toggleConfirm);

        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateConfirmVisibility(sw, confirm, confirmLabel, confirmRow));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setPositiveButton(R.string.child_lock_save, null)
                .setNegativeButton(R.string.child_lock_cancel, (d, w) -> d.dismiss())
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                boolean wantEnable = sw.isChecked();
                String p1 = pwd.getText() != null ? pwd.getText().toString() : "";
                String p2 = confirm.getText() != null ? confirm.getText().toString() : "";
                if (wantEnable) {
                    if (TextUtils.isEmpty(p1)) {
                        Notify.show(R.string.child_lock_error_empty);
                        return;
                    }
                    if (Setting.isChildLockEnabled()) {
                        if (!TextUtils.isEmpty(p1)) {
                            if (!p1.equals(p2)) {
                                Notify.show(R.string.child_lock_error_mismatch);
                                return;
                            }
                            Setting.putChildLockPasswordHash(Setting.hashChildLockPassword(p1));
                        }
                        Setting.putChildLockEnabled(true);
                        Notify.show(R.string.child_lock_saved);
                        dialog.dismiss();
                        return;
                    }
                    if (!p1.equals(p2)) {
                        Notify.show(R.string.child_lock_error_mismatch);
                        return;
                    }
                    Setting.putChildLockPasswordHash(Setting.hashChildLockPassword(p1));
                    Setting.putChildLockEnabled(true);
                    Notify.show(R.string.child_lock_saved);
                    dialog.dismiss();
                } else {
                    if (!Setting.isChildLockEnabled()) {
                        dialog.dismiss();
                        return;
                    }
                    if (TextUtils.isEmpty(p1) || !Setting.verifyChildLockPassword(p1)) {
                        Notify.show(R.string.child_lock_error_wrong);
                        return;
                    }
                    Setting.putChildLockEnabled(false);
                    Setting.putChildLockPasswordHash("");
                    Notify.show(R.string.child_lock_disabled);
                    dialog.dismiss();
                }
            });
        });
        dialog.show();
    }

    private static void updateConfirmVisibility(SwitchCompat sw, EditText confirm, View confirmLabel, View confirmRow) {
        boolean showConfirm = sw.isChecked();
        int vis = showConfirm ? View.VISIBLE : View.GONE;
        if (confirm != null) confirm.setVisibility(vis);
        if (confirmLabel != null) confirmLabel.setVisibility(vis);
        if (confirmRow != null) confirmRow.setVisibility(vis);
    }

    private static void bindPasswordToggle(EditText edit, ImageButton toggle) {
        if (edit == null || toggle == null) return;
        final boolean[] visible = {false};
        toggle.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int start = edit.getSelectionStart();
            int end = edit.getSelectionEnd();
            if (visible[0]) {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            try {
                if (start >= 0) edit.setSelection(Math.min(start, edit.getText().length()), Math.min(end, edit.getText().length()));
            } catch (Throwable ignored) {
            }
        });
    }
}
