package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateSettingsBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.OciMirror;
import com.fongmi.android.tv.update.UpdateSource;
import com.fongmi.android.tv.update.UpdateUrl;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.tabs.TabLayout;

public final class UpdateSettingsDialog {

    private static final int TAB_OCI = 0;
    private static final int TAB_GITHUB = 1;

    private UpdateSettingsDialog() {
    }

    public static void show(FragmentActivity activity) {
        DialogUpdateSettingsBinding binding = DialogUpdateSettingsBinding.inflate(LayoutInflater.from(activity));
        State state = State.load();
        // LightDialog 默认 factor/maxDp 为 0 会把窗口宽度算成 0，保存按钮像“消失”
        Dialog dialog = LightDialog.create(activity, null, binding.getRoot(), 0.62f, 0.92f, 640);
        setupTabs(activity, binding, state);
        bind(activity, dialog, binding, state);
        render(activity, binding, state);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        configureWindow(activity, dialog);
        configureTvFocus(binding, state);
    }

    private static void bind(FragmentActivity activity, Dialog dialog, DialogUpdateSettingsBinding binding, State state) {
        binding.close.setOnClickListener(view -> dialog.dismiss());
        binding.githubModeGroup.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) state.githubMode = id == R.id.githubModeStrip ? GithubProxy.MODE_STRIP_SCHEME : GithubProxy.MODE_FULL_URL;
        });
        binding.githubProxy.setOnClickListener(view -> chooseGithub(activity, binding, state));
        binding.ociMirror.setOnClickListener(view -> chooseOci(activity, binding, state));
        // 保存改到「加速镜像列表」底部；主设置页不再放保存
    }

    private static void setupTabs(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        binding.sourceTabs.setTabMode(TabLayout.MODE_FIXED);
        binding.sourceTabs.setTabGravity(TabLayout.GRAVITY_FILL);
        binding.sourceTabs.addTab(binding.sourceTabs.newTab().setText(R.string.update_source_oci), false);
        binding.sourceTabs.addTab(binding.sourceTabs.newTab().setText(R.string.update_source_github), false);
        binding.sourceTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                state.source = tab.getPosition() == TAB_GITHUB ? UpdateSource.GITHUB : UpdateSource.OCI;
                renderSource(binding, state);
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        int position = UpdateSource.GITHUB.equals(state.source) ? TAB_GITHUB : TAB_OCI;
        binding.sourceTabs.selectTab(binding.sourceTabs.getTabAt(position));
    }


    /** 加速镜像列表：底部 左保存 右取消 */
    private static void showProxyList(FragmentActivity activity, String title, CharSequence[] labels, int selected, ChoiceDialog.OnChoice onSave) {
        final int[] pending = new int[]{Math.max(0, selected)};
        android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(16), ResUtil.dp2px(20), ResUtil.dp2px(12));

        com.google.android.material.textview.MaterialTextView titleView = new com.google.android.material.textview.MaterialTextView(activity);
        titleView.setText(title);
        titleView.setTextSize(18);
        titleView.setTextColor(android.graphics.Color.parseColor("#202124"));
        titleView.setPadding(0, 0, 0, ResUtil.dp2px(12));
        root.addView(titleView);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        android.widget.LinearLayout list = new android.widget.LinearLayout(activity);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        final com.google.android.material.button.MaterialButton[] itemBtns = new com.google.android.material.button.MaterialButton[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            com.google.android.material.button.MaterialButton btn = new com.google.android.material.button.MaterialButton(activity);
            btn.setText(labels[i]);
            btn.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
            styleProxyListItem(btn, index == pending[0]);
            btn.setOnClickListener(v -> {
                pending[0] = index;
                for (int j = 0; j < itemBtns.length; j++) styleProxyListItem(itemBtns[j], j == pending[0]);
            });
            itemBtns[i] = btn;
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(46));
            lp.bottomMargin = ResUtil.dp2px(8);
            list.addView(btn, lp);
        }
        scroll.addView(list);
        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, ResUtil.dp2px(280)));

        android.widget.LinearLayout actions = new android.widget.LinearLayout(activity);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actions.setPadding(0, ResUtil.dp2px(12), 0, 0);

        com.google.android.material.button.MaterialButton saveBtn = new com.google.android.material.button.MaterialButton(activity);
        saveBtn.setText(R.string.update_settings_save);
        com.google.android.material.button.MaterialButton cancelBtn = new com.google.android.material.button.MaterialButton(activity);
        try {
            cancelBtn.setText(R.string.dialog_negative);
        } catch (Throwable e) {
            cancelBtn.setText("取消");
        }
        android.widget.LinearLayout.LayoutParams half = new android.widget.LinearLayout.LayoutParams(0, ResUtil.dp2px(44), 1f);
        half.setMarginEnd(ResUtil.dp2px(8));
        android.widget.LinearLayout.LayoutParams half2 = new android.widget.LinearLayout.LayoutParams(0, ResUtil.dp2px(44), 1f);
        half2.setMarginStart(ResUtil.dp2px(8));
        // 左保存 右取消
        actions.addView(saveBtn, half);
        actions.addView(cancelBtn, half2);
        root.addView(actions);

        Dialog listDialog = LightDialog.create(activity, null, root, 0.55f, 0.88f, 520);
        listDialog.setCanceledOnTouchOutside(false);
        saveBtn.setOnClickListener(v -> {
            if (onSave != null) onSave.onChoice(pending[0]);
            listDialog.dismiss();
        });
        cancelBtn.setOnClickListener(v -> listDialog.dismiss());
        listDialog.show();
        Window window = listDialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.55f : 0.88f));
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setAttributes(params);
            window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void styleProxyListItem(com.google.android.material.button.MaterialButton btn, boolean selected) {
        if (btn == null) return;
        btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(selected ? "#E8F0FE" : "#F1F3F4")));
        btn.setTextColor(android.graphics.Color.parseColor(selected ? "#1A73E8" : "#202124"));
        try { btn.setElevation(0); } catch (Throwable ignored) {}
    }


    private static void chooseGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        GithubProxy.Preset[] presets = GithubProxy.presets();
        CharSequence[] labels = new CharSequence[presets.length];
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = label(activity, presets[i].label, presets[i].id);
            if (presets[i].id.equals(state.githubProxy)) selected = i;
        }
        showProxyList(activity, activity.getString(R.string.update_github_proxy), labels, selected, which -> {
            state.githubProxy = presets[which].id;
            Setting.putUpdateGithubProxy(state.githubProxy);
            Setting.putUpdateGithubProxyUrl(state.githubCustom);
            Setting.putUpdateGithubProxyMode(state.githubMode);
            Setting.putUpdateSource(state.source);
            renderGithub(activity, binding, state);
            Notify.show(R.string.update_settings_saved);
        });
    }

    private static void chooseOci(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        OciMirror.Preset[] presets = OciMirror.presets();
        CharSequence[] labels = new CharSequence[presets.length];
        int selected = 0;
        for (int i = 0; i < presets.length; i++) {
            labels[i] = label(activity, presets[i].label, presets[i].id);
            if (presets[i].id.equals(state.ociMirror)) selected = i;
        }
        showProxyList(activity, activity.getString(R.string.update_oci_mirror), labels, selected, which -> {
            state.ociMirror = presets[which].id;
            Setting.putUpdateOciMirror(state.ociMirror);
            Setting.putUpdateOciMirrorUrl(state.ociCustom);
            Setting.putUpdateSource(state.source);
            renderOci(activity, binding, state);
            Notify.show(R.string.update_settings_saved);
        });
    }

    private static String label(FragmentActivity activity, String label, String id) {
        if (GithubProxy.DIRECT.equals(id) || OciMirror.DIRECT.equals(id)) return activity.getString(R.string.update_proxy_direct);
        if (GithubProxy.CUSTOM.equals(id) || OciMirror.CUSTOM.equals(id)) return activity.getString(R.string.update_proxy_custom);
        return label;
    }

    private static void save(FragmentActivity activity, Dialog dialog, DialogUpdateSettingsBinding binding, State state) {
        state.githubCustom = text(binding.githubCustom.getText());
        state.ociCustom = text(binding.ociCustom.getText());
        binding.githubCustomLayout.setError(null);
        binding.ociCustomLayout.setError(null);
        try {
            if (UpdateSource.GITHUB.equals(state.source) && GithubProxy.CUSTOM.equals(state.githubProxy)) UpdateUrl.requireHttpsOrigin(state.githubCustom);
        } catch (Exception e) {
            binding.githubCustomLayout.setError(activity.getString(R.string.update_proxy_invalid));
            return;
        }
        try {
            if (UpdateSource.OCI.equals(state.source) && OciMirror.CUSTOM.equals(state.ociMirror)) UpdateUrl.requireHttpsOrigin(state.ociCustom);
        } catch (Exception e) {
            binding.ociCustomLayout.setError(activity.getString(R.string.update_proxy_invalid));
            return;
        }
        Setting.putUpdateSource(state.source);
        Setting.putUpdateGithubProxy(state.githubProxy);
        Setting.putUpdateGithubProxyUrl(state.githubCustom);
        Setting.putUpdateGithubProxyMode(state.githubMode);
        Setting.putUpdateOciMirror(state.ociMirror);
        Setting.putUpdateOciMirrorUrl(state.ociCustom);
        dialog.dismiss();
        Notify.show(R.string.update_settings_saved);
    }

    private static void render(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        binding.githubCustom.setText(state.githubCustom);
        binding.ociCustom.setText(state.ociCustom);
        binding.githubModeGroup.check(GithubProxy.MODE_STRIP_SCHEME.equals(state.githubMode) ? R.id.githubModeStrip : R.id.githubModeFull);
        renderGithub(activity, binding, state);
        renderOci(activity, binding, state);
        renderSource(binding, state);
    }

    private static void renderSource(DialogUpdateSettingsBinding binding, State state) {
        boolean github = UpdateSource.GITHUB.equals(state.source);
        binding.githubPanel.setVisibility(github ? View.VISIBLE : View.GONE);
        binding.ociPanel.setVisibility(github ? View.GONE : View.VISIBLE);
        if (Util.isLeanback()) binding.sourceTabs.post(() -> configureTvFocus(binding, state));
    }

    private static void renderGithub(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        GithubProxy.Preset preset = GithubProxy.find(state.githubProxy);
        binding.githubProxy.setText(activity.getString(R.string.update_github_proxy_value, label(activity, preset.label, preset.id)));
        boolean custom = GithubProxy.CUSTOM.equals(preset.id);
        binding.githubCustomLayout.setVisibility(custom ? View.VISIBLE : View.GONE);
        binding.githubModeGroup.setVisibility(custom ? View.VISIBLE : View.GONE);
    }

    private static void renderOci(FragmentActivity activity, DialogUpdateSettingsBinding binding, State state) {
        OciMirror.Preset preset = OciMirror.find(state.ociMirror);
        binding.ociMirror.setText(activity.getString(R.string.update_oci_mirror_value, label(activity, preset.label, preset.id)));
        binding.ociCustomLayout.setVisibility(OciMirror.CUSTOM.equals(preset.id) ? View.VISIBLE : View.GONE);
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static void configureWindow(FragmentActivity activity, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int w = (int) (ResUtil.getScreenWidth(activity) * (ResUtil.isLand(activity) ? 0.62f : 0.92f));
        if (w < ResUtil.dp2px(280)) w = ResUtil.dp2px(280);
        params.width = w;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.CENTER;
        params.dimAmount = 0.58f;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setAttributes(params);
        window.setLayout(params.width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void configureTvFocus(DialogUpdateSettingsBinding binding, State state) {
        if (!Util.isLeanback()) return;
        tvFocusable(binding.close);
        try { if (binding.save.getVisibility() == View.VISIBLE) tvFocusable(binding.save); } catch (Throwable ignored) {}
        binding.close.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusSelectedTab(binding));
        binding.save.setOnKeyListener((view, keyCode, event) -> event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && focusLastControl(binding, state));
        binding.githubProxy.setOnKeyListener((view, keyCode, event) -> focusFromPrimary(binding, keyCode, event));
        binding.ociMirror.setOnKeyListener((view, keyCode, event) -> focusFromPrimary(binding, keyCode, event));
        configureTabFocus(binding, state);
        focusSelectedTab(binding);
    }

    private static void configureTabFocus(DialogUpdateSettingsBinding binding, State state) {
        if (binding.sourceTabs.getChildCount() == 0) return;
        View strip = binding.sourceTabs.getChildAt(0);
        if (!(strip instanceof ViewGroup tabs)) return;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View tab = tabs.getChildAt(i);
            tvFocusable(tab);
            tab.setBackgroundResource(R.drawable.selector_mpv_tab_focus);
            tab.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return binding.close.requestFocus();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return focusPrimary(binding, state);
                return false;
            });
        }
    }

    private static boolean focusFromPrimary(DialogUpdateSettingsBinding binding, int keyCode, KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP && focusSelectedTab(binding);
    }

    private static boolean focusSelectedTab(DialogUpdateSettingsBinding binding) {
        if (binding.sourceTabs.getChildCount() == 0) return false;
        View strip = binding.sourceTabs.getChildAt(0);
        if (!(strip instanceof ViewGroup tabs)) return false;
        int position = Math.max(TAB_OCI, binding.sourceTabs.getSelectedTabPosition());
        if (position >= tabs.getChildCount()) position = TAB_OCI;
        return tabs.getChildAt(position).requestFocus();
    }

    private static boolean focusPrimary(DialogUpdateSettingsBinding binding, State state) {
        return (UpdateSource.GITHUB.equals(state.source) ? binding.githubProxy : binding.ociMirror).requestFocus();
    }

    private static boolean focusLastControl(DialogUpdateSettingsBinding binding, State state) {
        if (UpdateSource.GITHUB.equals(state.source)) {
            if (GithubProxy.CUSTOM.equals(state.githubProxy)) {
                int checked = binding.githubModeGroup.getCheckedButtonId();
                View mode = checked == View.NO_ID ? binding.githubModeFull : binding.githubModeGroup.findViewById(checked);
                if (mode != null) return mode.requestFocus();
            }
            return binding.githubProxy.requestFocus();
        }
        if (OciMirror.CUSTOM.equals(state.ociMirror)) return binding.ociCustom.requestFocus();
        return binding.ociMirror.requestFocus();
    }

    private static void tvFocusable(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
    }

    private static final class State {

        private String source;
        private String githubProxy;
        private String githubCustom;
        private String githubMode;
        private String ociMirror;
        private String ociCustom;

        private static State load() {
            State state = new State();
            state.source = Setting.getUpdateSource();
            state.githubProxy = Setting.getUpdateGithubProxy();
            state.githubCustom = Setting.getUpdateGithubProxyUrl();
            state.githubMode = Setting.getUpdateGithubProxyMode();
            state.ociMirror = Setting.getUpdateOciMirror();
            state.ociCustom = Setting.getUpdateOciMirrorUrl();
            return state;
        }
    }
}
