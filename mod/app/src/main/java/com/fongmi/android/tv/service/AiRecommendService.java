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
        // 详情标题常只有系列名（流人），季数在备注/历史；拼成「流人 第4季」再展开
        String seriesTitle = resolveSeriesTitle(currentTitle, current);
        String prompt = buildPrompt(current, seriesTitle);
        Log.i(TAG, "prompt chars=" + prompt.length() + " title=" + currentTitle + " series=" + seriesTitle);
        String content = AiCompletionClient.complete(config, prompt);
        String exclude = currentTitle;
        if (TextUtils.isEmpty(exclude) && current != null) exclude = current.getName();
        List<Item> aiItems = parseItems(content, exclude);
        List<Item> sequels = expandSequelCandidates(seriesTitle, 6);
        if (sequels.isEmpty() && !TextUtils.isEmpty(currentTitle) && !currentTitle.equals(seriesTitle)) {
            sequels = expandSequelCandidates(currentTitle, 6);
        }
        if (sequels.isEmpty()) return aiItems;
        LinkedHashMap<String, Item> map = new LinkedHashMap<>();
        for (Item it : sequels) {
            if (it == null || TextUtils.isEmpty(it.title)) continue;
            map.put(it.title.toLowerCase(Locale.ROOT), it);
        }
        for (Item it : aiItems) {
            if (it == null || TextUtils.isEmpty(it.title)) continue;
            String k = it.title.toLowerCase(Locale.ROOT);
            if (!map.containsKey(k)) map.put(k, it);
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 把「流人」+ 备注/历史里的「第4季」拼成可用于续集展开的完整标题。
     * 若标题本身已含季数则原样返回。
     */
    public static String resolveSeriesTitle(String title, Vod current) {
        String base = title == null ? "" : title.trim();
        if (TextUtils.isEmpty(base) && current != null) base = safe(current.getName());
        if (TextUtils.isEmpty(base)) return "";
        // 标题已带季/部/Season → 直接用
        if (hasSeasonHint(base)) return base;

        List<String> hints = new ArrayList<>();
        if (current != null) {
            hints.add(safe(current.getRemarks()));
            hints.add(safe(current.getName()));
            try { hints.add(safe(current.getTypeName())); } catch (Throwable ignored) {}
        }
        // 播放历史：同名条目的备注常带「第N季 / 更新至…」
        try {
            List<History> histories = History.get();
            if (histories != null) {
                String baseKey = normalizeSeriesKey(base);
                for (History h : histories) {
                    if (h == null) continue;
                    String hn = safe(h.getVodName());
                    if (TextUtils.isEmpty(hn)) continue;
                    String hk = normalizeSeriesKey(hn);
                    if (hk.equals(baseKey) || hn.contains(base) || base.contains(stripSeason(hn))) {
                        hints.add(safe(h.getVodRemarks()));
                        hints.add(hn);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        int season = -1;
        String unit = "季";
        for (String hint : hints) {
            if (TextUtils.isEmpty(hint)) continue;
            int[] found = extractSeason(hint);
            if (found[0] > 0) {
                season = found[0];
                unit = found[1] == 2 ? "部" : "季";
                break;
            }
        }
        if (season < 1) return base;
        String out = base + " 第" + toCnNum(season) + unit;
        Log.i(TAG, "resolveSeriesTitle " + base + " → " + out);
        return out;
    }

    private static boolean hasSeasonHint(String s) {
        if (TextUtils.isEmpty(s)) return false;
        if (java.util.regex.Pattern.compile("第[0-9一二三四五六七八九十百]+[季部]").matcher(s).find()) return true;
        if (java.util.regex.Pattern.compile("(?i)S(?:eason)?[\\s._-]*[0-9]{1,2}").matcher(s).find()) return true;
        return false;
    }

    /** @return int[2] = {season, unitCode} unitCode 1=季 2=部；找不到则 season=-1 */
    private static int[] extractSeason(String s) {
        int[] none = new int[]{-1, 1};
        if (TextUtils.isEmpty(s)) return none;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "第([0-9一二三四五六七八九十百]+)([季部])").matcher(s);
        if (m.find()) {
            int n = parseCnNum(m.group(1));
            if (n >= 1) return new int[]{n, "部".equals(m.group(2)) ? 2 : 1};
        }
        m = java.util.regex.Pattern.compile("(?i)S(?:eason)?[\\s._-]*([0-9]{1,2})").matcher(s);
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= 30) return new int[]{n, 1};
            } catch (Exception ignored) {}
        }
        // 「更新至第12集」不算季；「第四季完结」已覆盖
        return none;
    }

    private static String stripSeason(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = java.util.regex.Pattern.compile("[\\s·\\-_]*第[0-9一二三四五六七八九十百]+[季部].*$").matcher(t).replaceFirst("");
        t = java.util.regex.Pattern.compile("(?i)[\\s·\\-_]*S(?:eason)?[\\s._-]*[0-9]{1,2}.*$").matcher(t).replaceFirst("");
        return t.trim();
    }

    private static String normalizeSeriesKey(String s) {
        return stripSeason(s).toLowerCase(Locale.ROOT).replaceAll("[\\s·\\-_]", "");
    }

    private static String buildPrompt(Vod current, String currentTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的影视推荐专家，熟悉电影、电视剧、动漫、纪录片、综艺。");
        sb.append("请根据用户「当前作品」和「播放历史」推荐 10-14 部作品。");
        sb.append("【排序规则，必须严格遵守】");
        sb.append("1) 若当前作品是系列剧/电影的某一季或某一集，优先推荐同一系列的后续季/部（如《闪电侠》第三季 → 第四季、第五季…直到季终），reason 写「正片续集/下一季」。");
        sb.append("2) 同一系列续集排在最前，再推荐同一宇宙/关联作品（如绿箭宇宙相关），最后才是题材相似的其他作品。");
        sb.append("3) 不要推荐当前已播的同一季，不要推荐播放历史里已出现的同名作品。");
        sb.append("只返回可解析 JSON，不要 Markdown 或解释。");
        sb.append("格式：{\"items\":[{\"title\":\"片名\",\"year\":2024,\"mediaType\":\"movie 或 tv\",\"reason\":\"一句推荐理由\"}]}。");
        sb.append("mediaType 只能是 movie 或 tv；reason 约 10-30 个中文字。\n\n");

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
    /**
     * 根据当前片名本地生成「下一季/下一部」候选，插到推荐列表最前。
     * 例：闪电侠第三季 → 闪电侠第四季…第七季
     */
    public static List<Item> expandSequelCandidates(String title, int maxSeasonsAhead) {
        List<Item> out = new ArrayList<>();
        if (TextUtils.isEmpty(title)) return out;
        String t = title.trim();
        // 中文：第N季 / 第N部
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "^(.*?)[\\s·\\-_]*第([0-9一二三四五六七八九十百]+)([季部])(.*)$").matcher(t);
        int cur = -1;
        String prefix = null;
        String suffix = "";
        String unit = "季";
        if (m.find()) {
            prefix = m.group(1).trim();
            cur = parseCnNum(m.group(2));
            unit = m.group(3);
            suffix = m.group(4) == null ? "" : m.group(4).trim();
        } else {
            // 英文 Season N / S0N
            m = java.util.regex.Pattern.compile(
                    "(?i)^(.*?)[\\s·\\-_]*S(?:eason)?[\\s\\._-]*([0-9]{1,2})(.*)$").matcher(t);
            if (m.find()) {
                prefix = m.group(1).trim();
                try { cur = Integer.parseInt(m.group(2)); } catch (Exception e) { cur = -1; }
                unit = "季";
                suffix = m.group(3) == null ? "" : m.group(3).trim();
            }
        }
        if (prefix == null || cur < 1) {
            // 末尾数字季：闪电侠3 / 绿箭侠 第3季 已覆盖；补「名称+数字」
            m = java.util.regex.Pattern.compile("^(.*?)[\\s·\\-_]*([0-9]{1,2})[\\s·\\-_]*$").matcher(t);
            if (m.find()) {
                String cand = m.group(1).trim();
                int n;
                try { n = Integer.parseInt(m.group(2)); } catch (Exception e) { n = -1; }
                if (n >= 1 && n <= 20 && cand.length() >= 2) {
                    prefix = cand;
                    cur = n;
                    unit = "季";
                    suffix = "";
                }
            }
        }
        if (prefix == null || prefix.isEmpty() || cur < 1) return out;
        int ahead = Math.max(1, Math.min(maxSeasonsAhead, 6));
        for (int i = 1; i <= ahead; i++) {
            int n = cur + i;
            if (n > 20) break;
            String name = prefix + " 第" + toCnNum(n) + unit;
            if (!suffix.isEmpty()) name = name + suffix;
            String reason = (i == ahead) ? ("系列第" + n + unit + "（可能季终/后续）") : ("正片续集 · 第" + n + unit);
            out.add(new Item(name, 0, "tv", reason));
        }
        return out;
    }

    private static int parseCnNum(String s) {
        if (s == null || s.isEmpty()) return -1;
        try { return Integer.parseInt(s); } catch (Exception ignored) {}
        String[] cn = {"零","一","二","三","四","五","六","七","八","九","十"};
        if ("十".equals(s)) return 10;
        if (s.startsWith("十") && s.length() == 2) {
            for (int i = 1; i <= 9; i++) if (s.equals("十" + cn[i])) return 10 + i;
        }
        if (s.endsWith("十") && s.length() == 2) {
            for (int i = 1; i <= 9; i++) if (s.equals(cn[i] + "十")) return i * 10;
        }
        for (int i = 1; i <= 10; i++) if (s.equals(cn[i])) return i;
        return -1;
    }

    private static String toCnNum(int n) {
        if (n <= 0) return String.valueOf(n);
        if (n <= 10) {
            String[] cn = {"零","一","二","三","四","五","六","七","八","九","十"};
            return cn[n];
        }
        if (n < 20) return "十" + toCnNum(n - 10);
        if (n % 10 == 0) return toCnNum(n / 10) + "十";
        return toCnNum(n / 10) + "十" + toCnNum(n % 10);
    }


}
