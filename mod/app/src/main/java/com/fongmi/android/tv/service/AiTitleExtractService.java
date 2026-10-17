package com.fongmi.android.tv.service;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** AI 真实剧名识别：从含广告/清晰度/乱码的标题中提取正式片名。 */
public final class AiTitleExtractService {

    private static final String TAG = "AiTitleExtract";
    private static final String PROMPT =
            "从下列可能含广告、清晰度、编码、合集标记的资源标题中，提取中文正式片名。"
                    + "只返回 JSON：{\"title\":\"正式片名\"}。不要解释。"
                    + "若无法识别则 title 为空字符串。\n标题：";

    private AiTitleExtractService() {
    }

    public static String extract(String rawTitle) {
        if (TextUtils.isEmpty(rawTitle)) return "";
        AiConfig config = Setting.getAiConfig();
        if (!config.isTitleExtractionEnabled()) return rawTitle.trim();
        try {
            String content = AiCompletionClient.complete(config, PROMPT + rawTitle.trim());
            String title = parseTitle(content);
            if (!TextUtils.isEmpty(title)) {
                Log.i(TAG, "extract " + rawTitle + " -> " + title);
                return title;
            }
        } catch (Exception e) {
            Log.w(TAG, "extract fail: " + e.getMessage());
        }
        return rawTitle.trim();
    }

    private static String parseTitle(String content) {
        if (TextUtils.isEmpty(content)) return "";
        String json = content.trim();
        if (json.contains("```")) {
            int a = json.indexOf('{');
            int b = json.lastIndexOf('}');
            if (a >= 0 && b > a) json = json.substring(a, b + 1);
        }
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.has("title") && !o.get("title").isJsonNull()) return o.get("title").getAsString().trim();
            if (o.has("name") && !o.get("name").isJsonNull()) return o.get("name").getAsString().trim();
        } catch (Exception e) {
            // plain text fallback
            if (!json.contains("{") && json.length() < 40) return json.replace("\"", "").trim();
        }
        return "";
    }
}
