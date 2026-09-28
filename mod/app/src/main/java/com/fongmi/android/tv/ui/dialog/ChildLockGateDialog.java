package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ChildLock;
import com.fongmi.android.tv.utils.Notify;

/**
 * 启动锁：不可取消，密码正确后解锁本进程。
 * UI 含密码显示/隐藏；功能逻辑不变。
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
        ImageButton toggle = root.findViewById(R.id.childLockGatePasswordToggle);
        bindPasswordToggle(pwd, toggle);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(false)
                .setPositiveButton(R.string.child_lock_unlock, null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((DialogInterface d, int keyCode, KeyEvent event) -> {
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
