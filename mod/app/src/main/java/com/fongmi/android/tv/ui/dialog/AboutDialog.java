package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAboutBinding;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

/**
 * 关于弹窗：检查更新 + 我已知 + 齿轮(更新设置，含 GitHub/OCI 加速)。
 * 发布检测仍走 klcb2010/webhtv Releases。
 */
public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(FragmentActivity activity, Runnable updateAction) {
        DialogAboutBinding binding = DialogAboutBinding.inflate(LayoutInflater.from(activity));
        binding.version.setText(activity.getString(
                R.string.about_version,
                AppVersion.fullName(),
                BuildConfig.FLAVOR_mode,
                BuildConfig.FLAVOR_abi));
        configureContentHeight(activity, binding);

        Dialog dialog = LightDialog.create(activity, null, binding.getRoot());
        binding.confirm.setOnClickListener(v -> dialog.dismiss());
        binding.checkUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            if (updateAction != null) updateAction.run();
        });
        // 齿轮：更新设置（加速源在 GitHub 页）
        try {
            binding.updateSettings.setOnClickListener(v -> {
                dialog.dismiss();
                UpdateSettingsDialog.show(activity);
            });
            binding.updateSettings.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable e) {
            try {
                binding.updateSettings.setVisibility(android.view.View.GONE);
            } catch (Throwable ignored) {
            }
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        configureWindow(activity, dialog);
        // 仅 TV(leanback) 需要遥控器焦点样式；手机端不处理
        if (Util.isLeanback()) {
            styleAboutActions(binding);
            try { binding.checkUpdate.requestFocus(); } catch (Throwable e) { binding.confirm.requestFocus(); }
        }
    }


    /** 检查更新 / 我已悉知 / 齿轮：统一获焦高亮 */
    private static boolean isTvMode() {
        try {
            String mode = BuildConfig.FLAVOR_mode;
            return mode != null && mode.toLowerCase().contains("leanback");
        } catch (Throwable e) {
            return false;
        }
    }

    private static void styleAboutActions(DialogAboutBinding binding) {
        try {
            unifyFocusButton(binding.checkUpdate);
            unifyFocusButton(binding.confirm);
        } catch (Throwable ignored) {}
        try {
            android.view.View gear = binding.updateSettings;
            gear.setFocusable(true);
            gear.setFocusableInTouchMode(true);
            // 与主按钮一致：获焦深蓝底
            android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
            normal.setColor(android.graphics.Color.parseColor("#E8F0FE"));
            normal.setCornerRadius(ResUtil.dp2px(8));
            android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable();
            focused.setColor(android.graphics.Color.parseColor("#0B57D0"));
            focused.setCornerRadius(ResUtil.dp2px(8));
            android.graphics.drawable.StateListDrawable sel = new android.graphics.drawable.StateListDrawable();
            sel.addState(new int[]{android.R.attr.state_focused}, focused);
            sel.addState(new int[]{android.R.attr.state_pressed}, focused);
            sel.addState(new int[]{}, normal);
            gear.setBackground(sel);
            if (gear instanceof android.widget.ImageView) {
                ((android.widget.ImageView) gear).setColorFilter(android.graphics.Color.parseColor("#174EA6"));
                gear.setOnFocusChangeListener((v, hasFocus) -> {
                    ((android.widget.ImageView) gear).setColorFilter(
                            hasFocus ? android.graphics.Color.WHITE : android.graphics.Color.parseColor("#174EA6"));
                });
            }
        } catch (Throwable ignored) {}
    }

    private static void unifyFocusButton(android.view.View view) {
        if (view == null) return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        try {
            if (view instanceof com.google.android.material.button.MaterialButton) {
                com.google.android.material.button.MaterialButton btn = (com.google.android.material.button.MaterialButton) view;
                int[][] states = new int[][]{
                        new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                };
                int[] bg = new int[]{
                        android.graphics.Color.parseColor("#0B57D0"),
                        android.graphics.Color.parseColor("#0B57D0"),
                        android.graphics.Color.parseColor("#E8F0FE")
                };
                int[] fg = new int[]{
                        android.graphics.Color.WHITE,
                        android.graphics.Color.WHITE,
                        android.graphics.Color.parseColor("#174EA6")
                };
                btn.setBackgroundTintList(new android.content.res.ColorStateList(states, bg));
                btn.setTextColor(new android.content.res.ColorStateList(states, fg));
            }
        } catch (Throwable ignored) {}
    }

    private static void configureContentHeight(FragmentActivity activity, DialogAboutBinding binding) {
        try {
            int screenHeight = ResUtil.getScreenHeight(activity);
            int maxHeight = (int) (screenHeight * (ResUtil.isLand(activity) ? 0.42f : 0.38f));
            int minHeight = ResUtil.dp2px(220);
            ViewGroup.LayoutParams params = binding.contentScroll.getLayoutParams();
            params.height = Math.max(minHeight, Math.min(maxHeight, ResUtil.dp2px(420)));
            binding.contentScroll.setLayoutParams(params);
        } catch (Throwable ignored) {
        }
    }

    private static boolean configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return false;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
        return true;
    }
}
