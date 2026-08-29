package com.fongmi.android.tv.service;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
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

/**
 * 智能推荐（复刻 Silent 思路，不依赖 TMDB）：
 * 以当前影片元数据为主，播放历史为辅，请求 AI 返回 JSON 列表。
 */
public final class AiRecommendService {

    private static final String TAG = "AiRecommend";
    private static final int MAX_HISTORY = 16;
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
            List<String> bits = new ArrayList<>();
            bits.add("tv".equals(mediaType) ? "剧集" : "电影");
            if (year > 0) bits.add(String.valueOf(year));
            if (!bits.isEmpty()) sb.append("  (").append(String.join(" · ", bits)).append(")");
            if (!TextUtils.isEmpty(reason)) sb.append("\n").append(reason);
            return sb.toString();
        }
    }

    public static List<Item> loadForTitle(String currentTitle) throws Exception {
        return load(null, currentTitle);
    }

    public static List<Item> load(Vod current, String currentTitle) throws Exception {
        AiConfig config = Setting.getAiConfig();
        if (!config.isRecommendationEnabled()) throw new IllegalStateException("ai_recommend_off");
        String prompt = buildPrompt(current, currentTitle);
        Log.i(TAG, "prompt chars=" + prompt.length() + " title=" + currentTitle);
        String content = AiCompletionClient.complete(config, prompt);
        String exclude = currentTitle;
        if (TextUtils.isEmpty(exclude) && current != null) exclude = current.getName();
        return parseItems(content, exclude);
    }

    private static String buildPrompt(Vod current, String currentTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的影视推荐专家，熟悉电影、电视剧、动漫、纪录片、综艺。");
        sb.append("请根据用户「当前作品」和「播放历史」分析题材、地区、年代、导演/演员偏好，推荐 10-14 部相关作品。");
        sb.append("优先推荐与当前作品气质相近、但片名不同的内容；可适度拓展同类型口碑作。");
        sb.append("不要推荐播放历史里已出现的同名作品，不要推荐当前片名。");
        sb.append("只返回可解析 JSON，不要 Markdown 或解释。");
        sb.append("格式：{\"items\":[{\"title\":\"片名\",\"year\":2024,\"mediaType\":\"movie 或 tv\",\"reason\":\"一句推荐理由\"}]}。");
        sb.append("mediaType 只能是 movie 或 tv；reason 约 15-40 个中文字。\n\n");

        sb.append("【当前作品】\n");
        String title = !TextUtils.isEmpty(currentTitle) ? currentTitle.trim() : (current != null ? safe(current.getName()) : "");
        sb.append("title: ").append(TextUtils.isEmpty(title) ? "未知" : title).append("\n");
        if (current != null) {
            appendIf(sb, "year", current.getYear());
            appendIf(sb, "type", current.getTypeName());
            appendIf(sb, "area", current.getArea());
            appendIf(sb, "lang", current.getLang());
            appendIf(sb, "director", current.getDirector());
            appendIf(sb, "actor", current.getActor());
            appendIf(sb, "remarks", current.getRemarks());
            String content = safe(current.getContent());
            if (!TextUtils.isEmpty(content)) {
                if (content.length() > 220) content = content.substring(0, 220);
                sb.append("overview: ").append(content).append("\n");
            }
        }
        sb.append("\n【播放历史】（越靠前越近，权重更高）\n");
        int n = 0;
        List<History> histories = History.get();
        if (histories != null) {
            for (History h : histories) {
                if (h == null || TextUtils.isEmpty(h.getVodName())) continue;
                String hn = h.getVodName().trim();
                if (!TextUtils.isEmpty(title) && title.equals(hn)) continue;
                sb.append("- ").append(hn);
                if (!TextUtils.isEmpty(h.getVodRemarks())) sb.append(" / ").append(h.getVodRemarks().trim());
                if (!TextUtils.isEmpty(h.getSiteName())) sb.append(" @").append(h.getSiteName().trim());
                sb.append("\n");
                if (++n >= MAX_HISTORY) break;
            }
        }
        if (n == 0) sb.append("- （暂无历史）\n");
        sb.append("\n请输出 JSON。");
        return sb.toString();
    }

    private static void appendIf(StringBuilder sb, String key, String value) {
        if (TextUtils.isEmpty(value)) return;
        sb.append(key).append(": ").append(value.trim()).append("\n");
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
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
                else if (o.has("recommendations") && o.get("recommendations").isJsonArray()) arr = o.getAsJsonArray("recommendations");
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            }
            if (arr == null) {
                Log.w(TAG, "no items array, body=" + excerpt(content));
                return items;
            }
            Map<String, Item> dedupe = new LinkedHashMap<>();
            String exclude = excludeTitle == null ? "" : excludeTitle.trim().toLowerCase(Locale.ROOT);
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String title = first(o, "title", "name", "vodName");
                if (TextUtils.isEmpty(title)) continue;
                if (!TextUtils.isEmpty(exclude) && title.trim().toLowerCase(Locale.ROOT).equals(exclude)) continue;
                int year = asInt(o, "year");
                if (year <= 0) year = asInt(o, "releaseYear");
                String type = first(o, "mediaType", "type", "category");
                String reason = first(o, "reason", "desc", "overview");
                String key = title.toLowerCase(Locale.ROOT);
                if (!dedupe.containsKey(key)) dedupe.put(key, new Item(title, year, type, reason));
            }
            items.addAll(dedupe.values());
            Log.i(TAG, "parsed " + items.size());
        } catch (Exception e) {
            Log.w(TAG, "parse fail: " + e.getMessage() + " body=" + excerpt(content));
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
            try {
                return Integer.parseInt(o.get(key).getAsString().replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() <= 160 ? s : s.substring(0, 160);
    }
}
