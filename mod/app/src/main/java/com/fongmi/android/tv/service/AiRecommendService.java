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
        sb.append("推荐顺序非常重要：如果当前作品是电视剧/动漫并且存在未观看的后续季，必须优先推荐同一系列的后续季，按季数从下一季开始连续排列；例如当前为《闪电侠》第三季，应优先给出《闪电侠》第四季、第五季、第六季、第七季（以及存在的更后续季），再推荐《绿箭侠》等同宇宙/相似作品。");
        sb.append("不要因为片名相同就排除后续季；只有当前正在观看的同一季，以及播放历史中已经明确观看过的同一季，才应排除。");
        sb.append("若后续季不足，再补充同系列衍生剧、同宇宙作品、再补充题材相似作品。前 4-7 个位置尽量用于同系列后续季；没有后续季时直接进入相似推荐。");
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
            prioritizeSeriesSeasons(items, excludeTitle);
            Log.i(TAG, "parsed " + items.size());
        } catch (Exception e) {
            Log.w(TAG, "parse fail: " + e.getMessage() + " body=" + excerpt(content));
        }
        return items;
    }


    /**
     * 同系列后续季优先：AI 排序偶尔不可靠时，在本地再兜底一次。
     * 例如“闪电侠第三季”后，第四、第五、第六、第七季排在其它相似剧之前。
     */
    private static void prioritizeSeriesSeasons(List<Item> items, String excludeTitle) {
        if (items == null || items.size() < 2 || TextUtils.isEmpty(excludeTitle)) return;

        String base = seriesKey(excludeTitle);
        int currentSeason = extractSeason(excludeTitle);
        if (TextUtils.isEmpty(base) || currentSeason <= 0) return;

        List<Item> original = new ArrayList<>(items);
        original.sort((a, b) -> {
            int pa = seasonPriority(a, base, currentSeason);
            int pb = seasonPriority(b, base, currentSeason);
            if (pa != pb) return Integer.compare(pa, pb);
            return 0;
        });
        items.clear();
        items.addAll(original);
    }

    private static int seasonPriority(Item item, String base, int currentSeason) {
        if (item == null || TextUtils.isEmpty(item.title)) return 10000;
        String itemBase = seriesKey(item.title);
        if (!base.equals(itemBase)) return 10000;

        int season = extractSeason(item.title);
        if (season > currentSeason) return season;
        if (season == currentSeason) return 9000;
        if (season > 0) return 8000 + season;
        return 7000;
    }

    /** 去掉“第X季 / Sxx / Season xx”等季数标记后得到系列名。 */
    private static String seriesKey(String title) {
        if (TextUtils.isEmpty(title)) return "";
        String s = title.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("第[0-9零一二两三四五六七八九十百千万]+季", "");
        s = s.replaceAll("\\b(?:season|s)\\s*[0-9]{1,2}\\b", "");
        s = s.replaceAll("\\s*[\\(\\[【（]?\\s*[0-9]{1,2}\\s*[季\\)\\]】）]\\s*$", "");
        s = s.replaceAll("[：:·•._\\-–—\\s]+", "");
        return s;
    }

    private static int extractSeason(String title) {
        if (TextUtils.isEmpty(title)) return 0;
        String s = title.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("第([0-9零一二两三四五六七八九十百千万]+)季")
                .matcher(s);
        if (m.find()) return chineseNumber(m.group(1));

        m = java.util.regex.Pattern.compile("\\bseason\\s*([0-9]{1,2})\\b").matcher(s);
        if (m.find()) return parseSeasonNumber(m.group(1));

        m = java.util.regex.Pattern.compile("\\bs\\s*([0-9]{1,2})\\b").matcher(s);
        if (m.find()) return parseSeasonNumber(m.group(1));

        m = java.util.regex.Pattern.compile("[\\(\\[【（]?\\s*([0-9]{1,2})\\s*[季\\)\\]】）]").matcher(s);
        if (m.find()) return parseSeasonNumber(m.group(1));
        return 0;
    }

    private static int parseSeasonNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int chineseNumber(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        int total = 0;
        int section = 0;
        int number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit;
            switch (c) {
                case '零': digit = 0; break;
                case '一': digit = 1; break;
                case '二':
                case '两': digit = 2; break;
                case '三': digit = 3; break;
                case '四': digit = 4; break;
                case '五': digit = 5; break;
                case '六': digit = 6; break;
                case '七': digit = 7; break;
                case '八': digit = 8; break;
                case '九': digit = 9; break;
                default: digit = -1;
            }
            if (digit >= 0) {
                number = number * 10 + digit;
            } else if (c == '十') {
                section += number == 0 ? 10 : number * 10;
                number = 0;
            } else if (c == '百') {
                section += (number == 0 ? 1 : number) * 100;
                number = 0;
            } else if (c == '千') {
                section += (number == 0 ? 1 : number) * 1000;
                number = 0;
            } else if (c == '万') {
                total += (section + (number == 0 ? 0 : number)) * 10000;
                section = 0;
                number = 0;
            }
        }
        return total + section + number;
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
