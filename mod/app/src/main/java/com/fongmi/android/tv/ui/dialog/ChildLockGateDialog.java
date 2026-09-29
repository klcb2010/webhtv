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
import android.view.WindowManager;
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
 * 启动锁：不可取消、不可点外部关闭；仅解锁成功或主动退出。
 */
public final class ChildLockGateDialog {

    private static boolean showing;
    private static boolean unlockedOk;

    private ChildLockGateDialog() {
    }

    public static void showIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!ChildLock.needsGate()) return;
        if (showing) return;
        showing = true;
        unlockedOk = false;

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
        // 必须在 create 后、show 前后都设一次，部分机型只认其一
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnKeyListener((DialogInterface d, int keyCode, KeyEvent event) -> {
            // 拦截返回键，不允许绕过
            if (keyCode == KeyEvent.KEYCODE_BACK) return true;
            return false;
        });
        dialog.setOnCancelListener(d -> {
            // 理论上 cancelable=false 不会进；若进了则阻止
        });
        dialog.setOnDismissListener(d -> {
            showing = false;
            // 未解锁却被关掉（异常路径）→ 立刻再弹
            if (!unlockedOk && ChildLock.needsGate()) {
                try {
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        activity.getWindow().getDecorView().post(() -> {
                            try {
                                showIfNeeded(activity);
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        });

        if (unlock != null) {
            unlock.setOnClickListener(v -> {
                String p = pwd != null && pwd.getText() != null ? pwd.getText().toString() : "";
                if (TextUtils.isEmpty(p) || !Setting.verifyChildLockPassword(p)) {
                    Notify.show(R.string.child_lock_error_wrong);
                    if (pwd != null) pwd.setText("");
                    return;
                }
                unlockedOk = true;
                ChildLock.unlock();
                dialog.dismiss();
            });
        }
        if (exit != null) {
            exit.setOnClickListener(v -> {
                unlockedOk = true; // 主动退出，不必再弹
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
            hardenWindow(dialog);
            if (tv && pwd != null) {
                try {
                    pwd.requestFocus();
                } catch (Throwable ignored) {
                }
            }
        });
        try {
            dialog.show();
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            hardenWindow(dialog);
        } catch (Throwable e) {
            showing = false;
        }
    }

    /** 禁止点外部关闭、吃掉外部触摸、半透明遮罩 */
    private static void hardenWindow(AlertDialog dialog) {
        try {
            Window w = dialog.getWindow();
            if (w == null) return;
            // 内容区透明圆角，但窗口仍拦截外部触摸
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            if (lp != null) {
                lp.dimAmount = 0.65f;
                w.setAttributes(lp);
            }
            // 部分 ROM：外部点击仍 dismiss，再挡一层
            View decor = w.getDecorView();
            if (decor != null) {
                decor.setOnTouchListener((v, event) -> {
                    // 消费 decor 边缘区域触摸，不往下传成 cancel
                    return false; // false 让子 View 仍可点；cancel 由 setCanceledOnTouchOutside 管
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
        } catch (Throwable ignored) {
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
