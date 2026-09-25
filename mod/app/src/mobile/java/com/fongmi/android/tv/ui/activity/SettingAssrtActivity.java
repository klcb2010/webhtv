package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingAssrtBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

public class SettingAssrtActivity extends BaseActivity {

    private ActivitySettingAssrtBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingAssrtActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingAssrtBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.token.setText(Setting.getSubtitleAssrtToken());
        mBinding.save.setOnClickListener(v -> {
            String token = mBinding.token.getText() == null ? "" : mBinding.token.getText().toString().trim();
            Setting.putSubtitleAssrtToken(token);
            Notify.show(R.string.dialog_positive);
            finish();
        });
    }
}
