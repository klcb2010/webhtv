package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;

/**
 * 个性设置 → 儿童管理。功能逻辑不变；TV 可焦点 + 眼睛开闭图标 + 获焦深蓝。
 */
public final class ChildLockSetupDialog {

    private ChildLockSetupDialog() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_child_lock_setup, null);
        SwitchCompat sw = root.findViewById(R.id.childLockSwitch);
        View switchRow = root.findViewById(R.id.childLockSwitchRow);
        TextView enableLabel = root.findViewById(R.id.childLockEnableLabel);
        EditText pwd = root.findViewById(R.id.childLockPassword);
        EditText confirm = root.findViewById(R.id.childLockPasswordConfirm);
        View confirmLabel = root.findViewById(R.id.childLockConfirmLabel);
        View confirmRow = root.findViewById(R.id.childLockConfirmRow);
        ImageButton togglePwd = root.findViewById(R.id.childLockPasswordToggle);
        ImageButton toggleConfirm = root.findViewById(R.id.childLockPasswordConfirmToggle);
        View btnCancel = root.findViewById(R.id.childLockCancel);
        View btnSave = root.findViewById(R.id.childLockSave);

        boolean enabled = Setting.isChildLockEnabled();
        sw.setChecked(enabled);
        updateConfirmVisibility(sw, confirm, confirmLabel, confirmRow);
        bindPasswordToggle(pwd, togglePwd);
        bindPasswordToggle(confirm, toggleConfirm);
        bindFocusTextColor(switchRow, enableLabel);
        bindFocusTextColor(btnCancel, btnCancel instanceof TextView ? (TextView) btnCancel : null);
        bindFocusTextColor(btnSave, btnSave instanceof TextView ? (TextView) btnSave : null);

        // 整行或开关都可切换（方便 TV）
        View.OnClickListener toggleSw = v -> sw.setChecked(!sw.isChecked());
        if (switchRow != null) switchRow.setOnClickListener(toggleSw);
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateConfirmVisibility(sw, confirm, confirmLabel, confirmRow));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
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
        }

        dialog.setOnShowListener(d -> {
            try {
                Window w = dialog.getWindow();
                if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            } catch (Throwable ignored) {
            }
            try {
                if (switchRow != null) switchRow.requestFocus();
                else if (sw != null) sw.requestFocus();
            } catch (Throwable ignored) {
            }
        });
        try {
            dialog.show();
            try {
                Window w = dialog.getWindow();
                if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void updateConfirmVisibility(SwitchCompat sw, EditText confirm, View confirmLabel, View confirmRow) {
        boolean showConfirm = sw.isChecked();
        int vis = showConfirm ? View.VISIBLE : View.GONE;
        if (confirm != null) confirm.setVisibility(vis);
        if (confirmLabel != null) confirmLabel.setVisibility(vis);
        if (confirmRow != null) confirmRow.setVisibility(vis);
    }

    /** 闭眼=隐藏密码，睁眼=显示明文 */
    private static void bindPasswordToggle(EditText edit, ImageButton toggle) {
        if (edit == null || toggle == null) return;
        final boolean[] visible = {false};
        toggle.setImageResource(R.drawable.ic_child_lock_eye_closed);
        toggle.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int start = edit.getSelectionStart();
            int end = edit.getSelectionEnd();
            if (visible[0]) {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                toggle.setImageResource(R.drawable.ic_child_lock_eye_open);
            } else {
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                toggle.setImageResource(R.drawable.ic_child_lock_eye_closed);
            }
            try {
                if (start >= 0) {
                    edit.setSelection(Math.min(start, edit.getText().length()), Math.min(end, edit.getText().length()));
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void bindFocusTextColor(View focusView, TextView label) {
        if (focusView == null) return;
        final int normal = 0xFF1565C0;
        final int onFocus = 0xFFFFFFFF;
        focusView.setOnFocusChangeListener((v, hasFocus) -> {
            if (label != null) label.setTextColor(hasFocus ? onFocus : normal);
            else if (v instanceof TextView) ((TextView) v).setTextColor(hasFocus ? onFocus : normal);
        });
    }
}
