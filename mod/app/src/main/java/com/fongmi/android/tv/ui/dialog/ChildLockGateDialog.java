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
import com.fongmi.android.tv.utils.Util;

/**
 * 启动锁。解锁/退出在内容区；TV 才启用遥控焦点高亮。
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

        final boolean tv = Util.isLeanback();
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_child_lock_gate, null);
        EditText pwd = root.findViewById(R.id.childLockGatePassword);
        ImageButton toggle = root.findViewById(R.id.childLockGatePasswordToggle);
        TextView unlock = root.findViewById(R.id.childLockGateUnlock);
        TextView exit = root.findViewById(R.id.childLockGateExit);
        bindPasswordToggle(pwd, toggle);

        if (tv) {
            bindFocusLabel(unlock, 0xFF1E88E5, 0xFFFFFFFF);
            bindFocusLabel(exit, 0xFF1E88E5, 0xFFFFFFFF);
            enhancePasswordFocus(pwd);
            if (toggle != null) {
                toggle.setFocusable(true);
                toggle.setFocusableInTouchMode(true);
            }
        } else {
            // 手机：触控为主，去掉 TV 焦点样式
            if (unlock != null) {
                unlock.setFocusable(false);
                unlock.setFocusableInTouchMode(false);
                unlock.setBackground(null);
                unlock.setTextColor(0xFF1E88E5);
            }
            if (exit != null) {
                exit.setFocusable(false);
                exit.setFocusableInTouchMode(false);
                exit.setBackground(null);
                exit.setTextColor(0xFF1E88E5);
            }
            if (toggle != null) {
                toggle.setFocusable(false);
                toggle.setFocusableInTouchMode(false);
                toggle.setBackground(null);
            }
            if (pwd != null) pwd.setCursorVisible(true);
        }

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
                String p = pwd != null && pwd.getText() != null ? pwd.getText().toString() : "";
                if (TextUtils.isEmpty(p) || !Setting.verifyChildLockPassword(p)) {
                    Notify.show(R.string.child_lock_error_wrong);
                    if (pwd != null) pwd.setText("");
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
            // 仅 TV 强制定焦到密码框
            if (tv && pwd != null) {
                try {
                    pwd.requestFocus();
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
