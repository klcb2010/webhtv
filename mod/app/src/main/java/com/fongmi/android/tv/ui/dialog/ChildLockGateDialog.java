package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ChildLock;
import com.fongmi.android.tv.utils.Notify;

/**
 * 启动锁：不可取消，密码正确后解锁本进程。
 */
public final class ChildLockGateDialog {

    private static boolean showing;

    private ChildLockGateDialog() {
    }

    public static void showIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!ChildLock.needsGate()) return;
        if (showing) return;
        showing = true;

        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_child_lock_gate, null);
        EditText pwd = root.findViewById(R.id.childLockGatePassword);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(false)
                .setPositiveButton(R.string.child_lock_unlock, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((DialogInterface d, int keyCode, KeyEvent event) -> {
            // 拦截返回，不允许绕过
            return keyCode == KeyEvent.KEYCODE_BACK;
        });
        dialog.setOnDismissListener(d -> showing = false);
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String p = pwd.getText() != null ? pwd.getText().toString() : "";
                if (TextUtils.isEmpty(p) || !Setting.verifyChildLockPassword(p)) {
                    Notify.show(R.string.child_lock_error_wrong);
                    pwd.setText("");
                    return;
                }
                ChildLock.unlock();
                dialog.dismiss();
            });
            try {
                pwd.requestFocus();
            } catch (Throwable ignored) {
            }
        });
        try {
            dialog.show();
        } catch (Throwable e) {
            showing = false;
        }
    }
}
