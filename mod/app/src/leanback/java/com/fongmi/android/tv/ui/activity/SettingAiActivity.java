package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.databinding.ActivitySettingAiBinding;
import com.fongmi.android.tv.service.AiCompletionClient;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;

import java.util.List;

public class SettingAiActivity extends BaseActivity {

    private ActivitySettingAiBinding mBinding;
    private AiConfig config;
    private int protocolIndex;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingAiActivity.class));
    }

    private String onOff(boolean v) {
        return getString(v ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingAiBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        config = Setting.getAiConfig();
        if (config == null) config = new AiConfig();
        protocolIndex = 0;
        String[] values = AiConfig.protocolValues();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(config.getProtocol())) {
                protocolIndex = i;
                break;
            }
        }
        bind();
        mBinding.enable.setOnClickListener(v -> {
            config.setEnabled(!config.isEnabled());
            mBinding.enableText.setText(onOff(config.isEnabled()));
        });
        mBinding.recommend.setOnClickListener(v -> {
            config.setRecommendation(!config.isRecommendation());
            mBinding.recommendText.setText(onOff(config.isRecommendation()));
        });
        mBinding.titleExtract.setOnClickListener(v -> {
            config.setTitleExtraction(!config.isTitleExtraction());
            mBinding.titleExtractText.setText(onOff(config.isTitleExtraction()));
        });
        mBinding.protocol.setOnClickListener(v -> {
            String[] labels = AiConfig.protocolLabels();
            protocolIndex = (protocolIndex + 1) % labels.length;
            config.setProtocol(AiConfig.protocolValues()[protocolIndex]);
            mBinding.protocolText.setText(labels[protocolIndex]);
        });
        mBinding.fetchModels.setOnClickListener(v -> fetchModels());
        mBinding.test.setOnClickListener(v -> test());
        mBinding.save.setOnClickListener(v -> save(true));
    }

    private void bind() {
        mBinding.enableText.setText(onOff(config.isEnabled()));
        mBinding.recommendText.setText(onOff(config.isRecommendation()));
        mBinding.titleExtractText.setText(onOff(config.isTitleExtraction()));
        String[] labels = AiConfig.protocolLabels();
        mBinding.protocolText.setText(labels[Math.min(protocolIndex, Math.max(labels.length - 1, 0))]);
        mBinding.endpoint.setText(config.getEndpoint());
        mBinding.apiKey.setText(config.getApiKey());
        mBinding.model.setText(config.getModel());
    }

    private void collect() {
        config.setEndpoint(text(mBinding.endpoint));
        config.setApiKey(text(mBinding.apiKey));
        config.setModel(text(mBinding.model));
        String[] values = AiConfig.protocolValues();
        if (protocolIndex >= 0 && protocolIndex < values.length) {
            config.setProtocol(values[protocolIndex]);
        }
    }

    private String text(android.widget.EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void save(boolean finish) {
        collect();
        Setting.putAiConfig(config.sanitize());
        Notify.show(R.string.dialog_positive);
        if (finish) finish();
    }

    private void fetchModels() {
        collect();
        AiConfig tmp = config.sanitize();
        if (!tmp.isModelFetchReady()) {
            Notify.show(R.string.setting_unconfigured);
            return;
        }
        Notify.show(R.string.setting_ai_fetch_models);
        Task.execute(() -> {
            try {
                AiCompletionClient.ModelFetchResult fr = AiCompletionClient.fetchModels(tmp);
                List<AiCompletionClient.ModelInfo> models = fr == null ? null : fr.models;
                App.post(() -> {
                    if (fr == null || !fr.ok || models == null || models.isEmpty()) {
                        Notify.show(fr != null && fr.message != null && !fr.message.isEmpty() ? fr.message : getString(R.string.setting_ai_test_fail, ""));
                        return;
                    }
                    String[] names = new String[models.size()];
                    for (int i = 0; i < models.size(); i++) names[i] = models.get(i).id;
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(R.string.setting_ai_model)
                            .setItems(names, (d, which) -> mBinding.model.setText(names[which]))
                            .show();
                });
            } catch (Throwable e) {
                App.post(() -> Notify.show(e.getMessage() == null ? "error" : e.getMessage()));
            }
        });
    }

    private void test() {
        collect();
        AiConfig tmp = config.sanitize();
        Task.execute(() -> {
            try {
                AiCompletionClient.TestResult result = AiCompletionClient.testConfig(tmp);
                App.post(() -> {
                    if (result != null && result.ok) {
                        Notify.show(getString(R.string.setting_ai_test_ok, result.message));
                    } else {
                        Notify.show(getString(R.string.setting_ai_test_fail, result == null ? "" : result.message));
                    }
                });
            } catch (Throwable e) {
                App.post(() -> Notify.show(e.getMessage() == null ? "error" : e.getMessage()));
            }
        });
    }
}
