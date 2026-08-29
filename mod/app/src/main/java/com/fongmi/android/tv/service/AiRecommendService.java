package com.fongmi.android.tv.service;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 最小 AI 推荐列表：观影历史 → OpenAI 兼容 chat/completions → JSON 片名列表。
 * 不依赖 TMDB / 豆瓣。
 */
public final class AiRecommendService {

    private static final String TAG = "AiRecommend";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_HISTORY = 20;
    private static final String SYSTEM_PROMPT =
            "你是专业的影视推荐助手。根据用户播放历史分析偏好，推荐 12-16 部不同的影视作品。"
                    + "只返回可解析 JSON，不要 Markdown 或解释。"
                    + "格式：{\"items\":[{\"title\":\"片名\",\"year\":2024,\"mediaType\":\"movie 或 tv\",\"reason\":\"一句推荐理由\"}]}"
                    + "mediaType 只能是 movie 或 tv；reason 约 15-40 字；不要推荐历史里已出现的同名作品。";

    private AiRecommendService() {
    }

    public static final class Item {
        public final String title;
        public final int year;
        public final String mediaType;
        public final String reason;

        public Item(String title, int year, String mediaType, String reason) {
            this.title = title == null ? "" : title.trim();
            this.year = year;
            this.mediaType = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
            this.reason = reason == null ? "" : reason.trim();
        }

        public String label() {
            StringBuilder sb = new StringBuilder(title);
            List<String> bits = new ArrayList<>();
            bits.add("tv".equals(mediaType) ? "剧集" : "电影");
            if (year > 0) bits.add(String.valueOf(year));
            if (!bits.isEmpty()) sb.append("  (").append(String.join(" · ", bits)).append(")");
            if (!TextUtils.isEmpty(reason)) sb.append("\n").append(reason);
            return sb.toString();
        }
    }

    public static List<Item> load() throws Exception {
        if (!Setting.isAiRecommendReady()) {
            throw new IllegalStateException("ai_not_ready");
        }
        String user = buildUserPrompt();
        String body = chat(Setting.getAiEndpoint(), Setting.getAiApiKey(), Setting.getAiModel(), SYSTEM_PROMPT, user);
        return parseItems(body);
    }

    private static String buildUserPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("播放历史（越靠前越近）：\n");
        List<History> histories = History.get();
        int n = 0;
        if (histories != null) {
            for (History h : histories) {
                if (h == null || TextUtils.isEmpty(h.getVodName())) continue;
                sb.append("- ").append(h.getVodName().trim());
                if (!TextUtils.isEmpty(h.getVodRemarks())) sb.append(" / ").append(h.getVodRemarks().trim());
                sb.append("\n");
                if (++n >= MAX_HISTORY) break;
            }
        }
        if (n == 0) sb.append("- （暂无历史，请推荐近期口碑较好的热门影视）\n");
        sb.append("\n请按系统要求返回 JSON 推荐列表。");
        return sb.toString();
    }

    private static String chat(String endpoint, String apiKey, String model, String system, String user) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", user);
        messages.add(usr);
        body.add("messages", messages);
        body.addProperty("temperature", 0.7);

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                Log.w(TAG, "http " + response.code() + " " + excerpt(raw));
                throw new IllegalStateException("HTTP " + response.code());
            }
            return extractContent(raw);
        }
    }

    private static String extractContent(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            // OpenAI chat
            if (root.has("choices") && root.get("choices").isJsonArray()) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject c0 = choices.get(0).getAsJsonObject();
                    if (c0.has("message") && c0.get("message").isJsonObject()) {
                        String content = asString(c0.getAsJsonObject("message"), "content");
                        if (!TextUtils.isEmpty(content)) return content;
                    }
                    String text = asString(c0, "text");
                    if (!TextUtils.isEmpty(text)) return text;
                }
            }
            // some gateways return output_text
            String output = asString(root, "output_text");
            if (!TextUtils.isEmpty(output)) return output;
        } catch (Exception e) {
            Log.w(TAG, "extract fail: " + e.getMessage());
        }
        return raw;
    }

    private static List<Item> parseItems(String content) {
        List<Item> items = new ArrayList<>();
        if (TextUtils.isEmpty(content)) return items;
        String json = content.trim();
        // strip markdown fence
        if (json.contains("```")) {
            int a = json.indexOf('{');
            int b = json.lastIndexOf('}');
            if (a >= 0 && b > a) json = json.substring(a, b + 1);
        }
        try {
            JsonElement el = JsonParser.parseString(json);
            JsonArray arr = null;
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("items") && o.get("items").isJsonArray()) arr = o.getAsJsonArray("items");
                else if (o.has("recommendations") && o.get("recommendations").isJsonArray()) arr = o.getAsJsonArray("recommendations");
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            }
            if (arr == null) return items;
            Map<String, Item> dedupe = new LinkedHashMap<>();
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String title = first(o, "title", "name", "vodName");
                if (TextUtils.isEmpty(title)) continue;
                int year = asInt(o, "year");
                if (year <= 0) year = asInt(o, "releaseYear");
                String type = first(o, "mediaType", "type", "category");
                String reason = first(o, "reason", "desc", "overview");
                String key = title.toLowerCase(Locale.ROOT);
                if (!dedupe.containsKey(key)) dedupe.put(key, new Item(title, year, type, reason));
            }
            items.addAll(dedupe.values());
        } catch (Exception e) {
            Log.w(TAG, "parse fail: " + e.getMessage() + " content=" + excerpt(content));
        }
        return items;
    }

    private static String first(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = asString(o, k);
            if (!TextUtils.isEmpty(v)) return v;
        }
        return "";
    }

    private static String asString(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString().trim();
        } catch (Exception e) {
            try {
                return o.get(key).toString().replace("\"", "").trim();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static int asInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            try {
                return Integer.parseInt(o.get(key).getAsString().replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() <= 180 ? s : s.substring(0, 180);
    }
}
