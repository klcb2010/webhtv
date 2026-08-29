package com.fongmi.android.tv.service;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 基于「当前影片」的 AI 推荐列表（不依赖 TMDB）。 */
public final class AiRecommendService {

    private static final String TAG = "AiRecommend";
    private static final int MAX_HISTORY = 12;
    private static final AtomicInteger GEN = new AtomicInteger();

    private AiRecommendService() {
    }

    public static int nextGen() {
        return GEN.incrementAndGet();
    }

    public static int currentGen() {
        return GEN.get();
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
            if (year > 0) sb.append(" (").append(year).append(")");
            if (!TextUtils.isEmpty(reason)) sb.append(" · ").append(reason);
            return sb.toString();
        }
    }

    /** @param currentTitle 当前播放/详情片名，推荐围绕它展开 */
    public static List<Item> loadForTitle(String currentTitle) throws Exception {
        AiConfig config = Setting.getAiConfig();
        if (!config.isRecommendationEnabled()) throw new IllegalStateException("ai_recommend_off");
        String title = currentTitle == null ? "" : currentTitle.trim();
        String prompt = buildPrompt(title);
        String content = AiCompletionClient.complete(config, prompt);
        return parseItems(content, title);
    }

    private static String buildPrompt(String currentTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的影视推荐助手。用户正在观看或浏览一部作品，请推荐 8-12 部相似或相关的影视。");
        sb.append("只返回可解析 JSON，不要 Markdown。");
        sb.append("格式：{\"items\":[{\"title\":\"片名\",\"year\":2024,\"mediaType\":\"movie 或 tv\",\"reason\":\"一句推荐理由\"}]}。");
        sb.append("不要推荐与当前片名相同的作品。\n\n");
        sb.append("当前作品：").append(TextUtils.isEmpty(currentTitle) ? "（未知）" : currentTitle).append("\n");
        sb.append("近期播放历史：\n");
        int n = 0;
        List<History> histories = History.get();
        if (histories != null) {
            for (History h : histories) {
                if (h == null || TextUtils.isEmpty(h.getVodName())) continue;
                if (!TextUtils.isEmpty(currentTitle) && currentTitle.equals(h.getVodName().trim())) continue;
                sb.append("- ").append(h.getVodName().trim()).append("\n");
                if (++n >= MAX_HISTORY) break;
            }
        }
        if (n == 0) sb.append("- （无）\n");
        return sb.toString();
    }

    private static List<Item> parseItems(String content, String excludeTitle) {
        List<Item> items = new ArrayList<>();
        if (TextUtils.isEmpty(content)) return items;
        String json = content.trim();
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
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            }
            if (arr == null) return items;
            Map<String, Item> dedupe = new LinkedHashMap<>();
            String exclude = excludeTitle == null ? "" : excludeTitle.trim().toLowerCase(Locale.ROOT);
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String title = first(o, "title", "name");
                if (TextUtils.isEmpty(title)) continue;
                if (!TextUtils.isEmpty(exclude) && title.trim().toLowerCase(Locale.ROOT).equals(exclude)) continue;
                int year = asInt(o, "year");
                String type = first(o, "mediaType", "type");
                String reason = first(o, "reason", "desc");
                String key = title.toLowerCase(Locale.ROOT);
                if (!dedupe.containsKey(key)) dedupe.put(key, new Item(title, year, type, reason));
            }
            items.addAll(dedupe.values());
        } catch (Exception e) {
            Log.w(TAG, "parse fail: " + e.getMessage());
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
            return "";
        }
    }

    private static int asInt(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }
}
