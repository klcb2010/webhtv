package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingGithubProxyBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.GithubProxy;
import com.fongmi.android.tv.utils.Notify;

import java.util.List;

public class SettingGithubProxyActivity extends BaseActivity {

    private ActivitySettingGithubProxyBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingGithubProxyActivity.class));
    }

    private String onOff(boolean v) {
        return getString(v ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingGithubProxyBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        refresh();
        mBinding.enabled.setOnClickListener(v -> {
            Setting.putGithubProxyEnabled(!Setting.isGithubProxyEnabled());
            mBinding.enabledText.setText(onOff(Setting.isGithubProxyEnabled()));
        });
        mBinding.add.setOnClickListener(v -> {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            input.setHint(R.string.setting_github_proxy_hint);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.setting_github_proxy)
                    .setView(input)
                    .setNegativeButton(R.string.dialog_negative, null)
                    .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                        String value = input.getText() == null ? "" : input.getText().toString().trim();
                        if (value.isEmpty()) return;
                        Setting.putGithubProxy(GithubProxy.addSource(value));
                        refresh();
                    })
                    .show();
        });
        mBinding.reset.setOnClickListener(v -> {
            Setting.putGithubProxy(GithubProxy.defaultSources());
            refresh();
            Notify.show(R.string.dialog_positive);
        });
    }

    private void refresh() {
        mBinding.enabledText.setText(onOff(Setting.isGithubProxyEnabled()));
        mBinding.activeText.setText(GithubProxy.getActive());
        LinearLayout list = mBinding.sourceList;
        list.removeAllViews();
        List<String> sources = GithubProxy.getSources();
        String active = GithubProxy.getActive();
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (10 * density);
        for (String source : sources) {
            com.google.android.material.textview.MaterialTextView tv =
                    new com.google.android.material.textview.MaterialTextView(this);
            boolean isActive = source.equals(active);
            tv.setText((isActive ? "✓ " : "  ") + source);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(14);
            tv.setPadding(pad, pad, pad, pad);
            tv.setBackgroundColor(isActive ? 0x55FFFFFF : 0x22FFFFFF);
            tv.setFocusable(true);
            tv.setClickable(true);
            tv.setOnClickListener(v -> {
                Setting.putGithubProxy(GithubProxy.setActive(source));
                refresh();
            });
            tv.setOnLongClickListener(v -> {
                if (GithubProxy.isBuiltIn(source)) {
                    Notify.show(R.string.setting_github_proxy_builtin);
                    return true;
                }
                Setting.putGithubProxy(GithubProxy.removeSource(source));
                refresh();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * density);
            tv.setLayoutParams(lp);
            list.addView(tv);
        }
    }
}
