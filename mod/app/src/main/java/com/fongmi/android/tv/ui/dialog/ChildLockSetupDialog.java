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
import com.fongmi.android.tv.utils.Util;

/**
 * 个性设置 → 家长控制。功能逻辑共用；TV 才启用遥控焦点高亮。
 */
public final class ChildLockSetupDialog {

    private ChildLockSetupDialog() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final boolean tv = Util.isLeanback();
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
        if (sw != null) sw.setChecked(enabled);
        updateConfirmVisibility(sw, confirm, confirmRow);
        bindPasswordToggle(pwd, togglePwd);
        bindPasswordToggle(confirm, toggleConfirm);

        if (tv) {
            // 仅 TV：遥控焦点高亮
            applyTvFocusChrome(switchRow, sw, enableLabel, btnCancel, btnSave, pwd, confirm);
        } else {
            // 手机：正常触控，去掉 TV 焦点行样式干扰
            applyMobileTouchUi(switchRow, sw, enableLabel, btnCancel, btnSave, pwd, confirm, togglePwd, toggleConfirm);
        }

        if (switchRow != null) {
            switchRow.setOnClickListener(v -> {
                if (sw != null) sw.setChecked(!sw.isChecked());
            });
        }
        if (sw != null) {
            sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                    updateConfirmVisibility(sw, confirm, confirmRow));
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(true)
                .create();

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());
        if (btnSave != null) {
            btnSave.setOnClickListener(v -> onSave(dialog, sw, pwd, confirm));
        }

        dialog.setOnShowListener(d -> {
            try {
                Window w = dialog.getWindow();
                if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            } catch (Throwable ignored) {
            }
            // 仅 TV 默认定焦到开关
            if (tv && sw != null) {
                try {
                    sw.requestFocus();
                    sw.post(() -> {
                        try {
                            sw.requestFocus();
                            syncSwitchRowHighlight(switchRow, sw, enableLabel);
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
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

    private static void onSave(AlertDialog dialog, SwitchCompat sw, EditText pwd, EditText confirm) {
        boolean wantEnable = sw != null && sw.isChecked();
        String p1 = pwd != null && pwd.getText() != null ? pwd.getText().toString() : "";
        String p2 = confirm != null && confirm.getText() != null ? confirm.getText().toString() : "";
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
    }

    private static void applyMobileTouchUi(View switchRow, SwitchCompat sw, TextView enableLabel,
                                          View btnCancel, View btnSave,
                                          EditText pwd, EditText confirm,
                                          ImageButton togglePwd, ImageButton toggleConfirm) {
        // 行不抢焦点、不高亮
        if (switchRow != null) {
            switchRow.setFocusable(false);
            switchRow.setFocusableInTouchMode(false);
            switchRow.setBackground(null);
            switchRow.setSelected(false);
            switchRow.setActivated(false);
        }
        if (sw != null) {
            sw.setFocusable(true);
            sw.setFocusableInTouchMode(false);
            sw.setClickable(true);
        }
        if (enableLabel != null) enableLabel.setTextColor(0xFFE8EEF7);
        // 按钮保持可点，不做 TV 获焦变色
        for (View b : new View[]{btnCancel, btnSave}) {
            if (b == null) continue;
            b.setFocusable(false);
            b.setFocusableInTouchMode(false);
            b.setBackground(null);
            if (b instanceof TextView) ((TextView) b).setTextColor(0xFF6BA3E8);
        }
        for (ImageButton ib : new ImageButton[]{togglePwd, toggleConfirm}) {
            if (ib == null) continue;
            ib.setFocusable(false);
            ib.setFocusableInTouchMode(false);
            ib.setBackground(null);
        }
        // 输入框保留轻微描边即可，不强制光标焦点逻辑
        for (EditText e : new EditText[]{pwd, confirm}) {
            if (e == null) continue;
            e.setFocusable(true);
            e.setFocusableInTouchMode(true);
            e.setCursorVisible(true);
        }
    }

    private static void applyTvFocusChrome(View switchRow, SwitchCompat sw, TextView enableLabel,
                                          View btnCancel, View btnSave, EditText pwd, EditText confirm) {
        bindSwitchRowHighlight(switchRow, sw, enableLabel);
        bindFocusLabel(btnCancel, btnCancel instanceof TextView ? (TextView) btnCancel : null, 0xFF6BA3E8, 0xFFFFFFFF);
        bindFocusLabel(btnSave, btnSave instanceof TextView ? (TextView) btnSave : null, 0xFF6BA3E8, 0xFFFFFFFF);
        enhancePasswordFocus(pwd);
        enhancePasswordFocus(confirm);
        if (sw != null) {
            sw.setFocusable(true);
            sw.setFocusableInTouchMode(true);
        }
        if (switchRow != null) {
            switchRow.setFocusable(true);
            switchRow.setFocusableInTouchMode(true);
        }
    }

    private static void updateConfirmVisibility(SwitchCompat sw, EditText confirm, View confirmRow) {
        boolean show = sw != null && sw.isChecked();
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
            v.refreshDrawableState();
            v.invalidate();
        });
    }

    private static void bindSwitchRowHighlight(View switchRow, SwitchCompat sw, TextView enableLabel) {
        if (switchRow == null && sw == null) return;
        View.OnFocusChangeListener listener = (v, hasFocus) -> syncSwitchRowHighlight(switchRow, sw, enableLabel);
        if (switchRow != null) switchRow.setOnFocusChangeListener(listener);
        if (sw != null) sw.setOnFocusChangeListener(listener);
        syncSwitchRowHighlight(switchRow, sw, enableLabel);
    }

    private static void syncSwitchRowHighlight(View switchRow, SwitchCompat sw, TextView enableLabel) {
        boolean on = false;
        try {
            if (switchRow != null && switchRow.hasFocus()) on = true;
            if (sw != null && sw.hasFocus()) on = true;
        } catch (Throwable ignored) {
        }
        if (switchRow != null) {
            switchRow.setSelected(on);
            switchRow.setActivated(on);
            switchRow.refreshDrawableState();
            switchRow.invalidate();
        }
        if (enableLabel != null) {
            enableLabel.setTextColor(on ? 0xFFFFFFFF : 0xFFE8EEF7);
        }
    }

    private static void enhancePasswordFocus(EditText edit) {
        if (edit == null) return;
        edit.setCursorVisible(true);
        edit.setOnFocusChangeListener((v, hasFocus) -> {
            edit.setCursorVisible(hasFocus);
            if (hasFocus) {
                try {
                    edit.setSelection(edit.getText() != null ? edit.getText().length() : 0);
                } catch (Throwable ignored) {
                }
            }
            v.refreshDrawableState();
            v.invalidate();
        });
    }
}
