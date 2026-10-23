package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * 仅用于承载 jar 的登录弹窗：透明、无界面。
 * 避免在综合设置页上直接 SiteApi.action 导致关窗后露出综合设置。
 */
public class JarLoginHostActivity extends Activity {

    public static final String EXTRA_KEY = "site_key";
    public static final String EXTRA_ACT = "login_action";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean fired = false;
    private int ticks = 0;
    private int actIndex = 0;

    public static void start(Context ctx, String siteKey, String action) {
        Intent i = new Intent(ctx, JarLoginHostActivity.class);
        i.putExtra(EXTRA_KEY, siteKey);
        i.putExtra(EXTRA_ACT, action);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (!(ctx instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {
        }

        final String key = getIntent().getStringExtra(EXTRA_KEY);
        final String act = getIntent().getStringExtra(EXTRA_ACT);
        handler.postDelayed(() -> fire(key, act), 350);
    }

    private void fire(String key, String act) {
        if (fired) return;
        fired = true;
        try {
            com.github.catvod.Init.setActivity(this);
            com.github.catvod.Init.set(this);
        } catch (Throwable ignored) {
        }
        try {
            if (key != null && act != null) {
                com.fongmi.android.tv.api.SiteApi.action(key, act);
            }
        } catch (Throwable ignored) {
        }
        // 可选：尝试 pending 列表中的下一个
        handler.postDelayed(this::tryNextOrWatch, 1200);
    }

    private void tryNextOrWatch() {
        try {
            java.util.ArrayList<String> pending =
                    com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.takeLoginPending();
            String key = com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.getLoginSiteKey();
            if (pending != null && key != null && actIndex + 1 < pending.size()) {
                // 若仍无对话框焦点，再试下一个 action
                if (hasWindowFocus()) {
                    actIndex++;
                    String next = pending.get(actIndex);
                    try {
                        com.github.catvod.Init.setActivity(this);
                        com.fongmi.android.tv.api.SiteApi.action(key, next);
                    } catch (Throwable ignored) {
                    }
                    handler.postDelayed(this::tryNextOrWatch, 1200);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        watch();
    }

    private void watch() {
        if (isFinishing()) return;
        ticks++;
        // 无焦点且过了几轮 → 认为弹窗已关或未弹出
        if ((!hasWindowFocus() && ticks > 2) || ticks > 100) {
            try {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.clearLoginForce();
            } catch (Throwable ignored) {
            }
            finish();
            return;
        }
        handler.postDelayed(this::watch, 900);
    }

    @Override
    public void onBackPressed() {
        try {
            com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.clearLoginForce();
        } catch (Throwable ignored) {
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.clearLoginForce();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
