package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;

/**
 * 个性设置 → 儿童管理：开关 + 设密 / 关锁验密。
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

        boolean enabled = Setting.isChildLockEnabled();
        sw.setChecked(enabled);
        // 已开启：关锁只需当前密码，隐藏确认框；开锁需设新密则显示
        updateConfirmVisibility(sw, confirm, confirmLabel);

        sw.setOnCheckedChangeListener((buttonView, isChecked) -> updateConfirmVisibility(sw, confirm, confirmLabel));

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
                        // 已开启再保存：可改密（需原逻辑：输入新密两次）或保持
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
                    // 首次开启
                    if (!p1.equals(p2)) {
                        Notify.show(R.string.child_lock_error_mismatch);
                        return;
                    }
                    Setting.putChildLockPasswordHash(Setting.hashChildLockPassword(p1));
                    Setting.putChildLockEnabled(true);
                    Notify.show(R.string.child_lock_saved);
                    dialog.dismiss();
                } else {
                    // 关闭：必须验证当前密码
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

    private static void updateConfirmVisibility(SwitchCompat sw, EditText confirm, View confirmLabel) {
        boolean showConfirm = sw.isChecked();
        int vis = showConfirm ? View.VISIBLE : View.GONE;
        if (confirm != null) confirm.setVisibility(vis);
        if (confirmLabel != null) confirmLabel.setVisibility(vis);
    }
}
