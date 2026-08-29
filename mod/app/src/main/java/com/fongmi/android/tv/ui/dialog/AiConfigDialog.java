package com.fongmi.android.tv.ui.dialog;

import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.service.AiCompletionClient;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AiConfigDialog {

    private AiConfigDialog() {
    }

    public interface Callback {
        void onSaved(AiConfig config);
    }

    public static void show(FragmentActivity activity, Callback callback) {
        if (activity == null) return;
        AiConfig config = Setting.getAiConfig();

        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        int gap = (int) (10 * density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad / 2);

        CheckBox enable = new CheckBox(activity);
        enable.setText(R.string.setting_ai_enable);
        enable.setChecked(config.isEnabled());
        root.addView(enable);

        root.addView(label(activity, R.string.setting_ai_protocol, gap));
        Spinner protocol = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, AiConfig.protocolLabels());
        protocol.setAdapter(adapter);
        int protocolIndex = 0;
        String[] values = AiConfig.protocolValues();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(config.getProtocol())) {
                protocolIndex = i;
                break;
            }
        }
        protocol.setSelection(protocolIndex);
        root.addView(protocol);

        root.addView(label(activity, R.string.setting_ai_endpoint, gap));
        EditText endpoint = field(activity, config.getEndpoint());
        root.addView(endpoint);

        root.addView(label(activity, R.string.setting_ai_api_key, gap));
        EditText apiKey = field(activity, config.getApiKey());
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKey);

        root.addView(label(activity, R.string.setting_ai_model, gap));
        EditText model = field(activity, config.getModel());
        root.addView(model);

        root.addView(label(activity, R.string.setting_ai_user_agent, gap));
        EditText ua = field(activity, config.getCustomUserAgent());
        ua.setHint(R.string.setting_ai_user_agent_hint);
        root.addView(ua);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.setting_ai_config)
                .setView(scroll)
                .setNegativeButton(R.string.dialog_negative, null)
                .setNeutralButton(R.string.setting_ai_test, null)
                .setPositiveButton(R.string.dialog_positive, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                AiConfig next = read(enable, protocol, endpoint, apiKey, model, ua);
                Setting.putAiConfig(next);
                Notify.show(R.string.ai_config_saved);
                if (callback != null) callback.onSaved(next);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                AiConfig next = read(enable, protocol, endpoint, apiKey, model, ua);
                next.setEnabled(true);
                Notify.show(R.string.setting_ai_testing);
                Task.execute(() -> {
                    AiCompletionClient.TestResult result = AiCompletionClient.testConfig(next);
                    App.post(() -> Notify.show(result.ok
                            ? activity.getString(R.string.setting_ai_test_ok, result.message)
                            : activity.getString(R.string.setting_ai_test_fail, result.message)));
                });
            });
        });
        dialog.show();
    }

    private static AiConfig read(CheckBox enable, Spinner protocol, EditText endpoint, EditText apiKey, EditText model, EditText ua) {
        AiConfig next = new AiConfig();
        next.setEnabled(enable.isChecked());
        int idx = Math.max(0, protocol.getSelectedItemPosition());
        String[] values = AiConfig.protocolValues();
        next.setProtocol(values[Math.min(idx, values.length - 1)]);
        next.setEndpoint(text(endpoint));
        next.setApiKey(text(apiKey));
        next.setModel(text(model));
        next.setCustomUserAgent(text(ua));
        return next.sanitize();
    }

    private static String text(EditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private static TextView label(FragmentActivity activity, int res, int top) {
        TextView tv = new TextView(activity);
        tv.setText(res);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        tv.setLayoutParams(lp);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return tv;
    }

    private static EditText field(FragmentActivity activity, String value) {
        EditText edit = new EditText(activity);
        edit.setSingleLine(true);
        if (!TextUtils.isEmpty(value)) edit.setText(value);
        return edit;
    }
}
