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
        binding.confirm.requestFocus();
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
