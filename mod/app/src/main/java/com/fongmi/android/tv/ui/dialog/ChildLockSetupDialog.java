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
        View confirmRow = root.findViewById(R.id.childLockConfirmRow);
        ImageButton togglePwd = root.findViewById(R.id.childLockPasswordToggle);
        ImageButton toggleConfirm = root.findViewById(R.id.childLockPasswordConfirmToggle);
        View btnCancel = root.findViewById(R.id.childLockCancel);
        View btnSave = root.findViewById(R.id.childLockSave);

        boolean enabled = Setting.isChildLockEnabled();
        sw.setChecked(enabled);
        updateConfirmVisibility(sw, confirm, confirmRow);
        bindPasswordToggle(pwd, togglePwd);
        bindPasswordToggle(confirm, toggleConfirm);
        bindFocusLabel(btnCancel, btnCancel instanceof TextView ? (TextView) btnCancel : null, 0xFF1565C0, 0xFFFFFFFF);
        bindFocusLabel(btnSave, btnSave instanceof TextView ? (TextView) btnSave : null, 0xFF1565C0, 0xFFFFFFFF);
        // 开关或整行任一获焦 → 行高亮 + 标签白字（下移再回来落在开关上也能亮）
        bindSwitchRowHighlight(switchRow, sw, enableLabel);

        if (switchRow != null) {
            switchRow.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        }
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                updateConfirmVisibility(sw, confirm, confirmRow));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
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
                // 默认焦点落在开关上，行同步高亮
                if (sw != null) {
                    sw.setFocusable(true);
                    sw.setFocusableInTouchMode(true);
                    sw.requestFocus();
                    sw.post(() -> {
                        sw.requestFocus();
                        syncSwitchRowHighlight(switchRow, sw, enableLabel);
                    });
                } else if (switchRow != null) {
                    switchRow.requestFocus();
                }
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

    private static void updateConfirmVisibility(SwitchCompat sw, EditText confirm, View confirmRow) {
        boolean show = sw.isChecked();
        int vis = show ? View.VISIBLE : View.GONE;
        if (confirm != null) confirm.setVisibility(vis);
        if (confirmRow != null) confirmRow.setVisibility(vis);
    }

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

    private static void bindFocusLabel(View focusView, TextView label, int normalColor, int focusColor) {
        if (focusView == null) return;
        if (label != null) label.setTextColor(normalColor);
        else if (focusView instanceof TextView) ((TextView) focusView).setTextColor(normalColor);
        focusView.setOnFocusChangeListener((v, hasFocus) -> {
            int c = hasFocus ? focusColor : normalColor;
            if (label != null) label.setTextColor(c);
            else if (v instanceof TextView) ((TextView) v).setTextColor(c);
            // 强制刷新背景 selector（部分 TV 回焦不重绘）
            v.refreshDrawableState();
            v.invalidate();
        });
    }
}
