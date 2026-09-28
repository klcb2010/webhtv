package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Process;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ChildLock;
import com.fongmi.android.tv.utils.Notify;

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
        TextView unlock = root.findViewById(R.id.childLockGateUnlock);
        TextView exit = root.findViewById(R.id.childLockGateExit);
        bindPasswordToggle(pwd, toggle);
        bindFocusLabel(unlock, 0xFF1565C0, 0xFFFFFFFF);
        bindFocusLabel(exit, 0xFF1565C0, 0xFFFFFFFF);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((DialogInterface d, int keyCode, KeyEvent event) ->
                keyCode == KeyEvent.KEYCODE_BACK);
        dialog.setOnDismissListener(d -> showing = false);

        if (unlock != null) {
            unlock.setOnClickListener(v -> {
                String p = pwd.getText() != null ? pwd.getText().toString() : "";
                if (TextUtils.isEmpty(p) || !Setting.verifyChildLockPassword(p)) {
                    Notify.show(R.string.child_lock_error_wrong);
                    pwd.setText("");
                    return;
                }
                ChildLock.unlock();
                dialog.dismiss();
            });
        }
        if (exit != null) {
            exit.setOnClickListener(v -> {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
                try {
                    activity.finishAffinity();
                } catch (Throwable ignored) {
                    try {
                        activity.finish();
                    } catch (Throwable ignored2) {
                    }
                }
                try {
                    Process.killProcess(Process.myPid());
                } catch (Throwable ignored) {
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
                if (pwd != null) pwd.requestFocus();
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
        } catch (Throwable e) {
            showing = false;
        }
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

    private static void bindFocusLabel(TextView tv, int normal, int focus) {
        if (tv == null) return;
        tv.setTextColor(normal);
        tv.setOnFocusChangeListener((v, hasFocus) -> {
            tv.setTextColor(hasFocus ? focus : normal);
            v.refreshDrawableState();
            v.invalidate();
        });
    }
}
