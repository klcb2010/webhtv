package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 设置页风格对话框：深色背景，贴近设置界面。
 */
public final class SettingStyleDialog {

    private static final int BG = 0xFF1A1A1A;
    private static final int FG = 0xFFFFFFFF;

    private SettingStyleDialog() {
    }

    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context);
    }

    public static void apply(AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
        }
        View decor = window != null ? window.getDecorView() : null;
        if (decor instanceof ViewGroup) {
            tintTexts((ViewGroup) decor);
        }
    }

    public static void tintContent(View root) {
        if (root == null) return;
        root.setBackgroundColor(BG);
        if (root instanceof ViewGroup) tintTexts((ViewGroup) root);
        else if (root instanceof TextView) ((TextView) root).setTextColor(FG);
    }

    private static void tintTexts(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                // keep button/primary colors roughly readable
                int c = tv.getCurrentTextColor();
                // if near-black, force white
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (r < 80 && g < 80 && b < 80) tv.setTextColor(FG);
            } else if (child instanceof ViewGroup) {
                tintTexts((ViewGroup) child);
            }
        }
    }
}
