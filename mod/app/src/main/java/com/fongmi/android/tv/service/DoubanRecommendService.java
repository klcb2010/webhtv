package com.fongmi.android.tv.service;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆瓣相关推荐（无官方 Key）：suggest 取条目，再拉推荐列表。
 * 失败返回空列表，由上层隐藏面板，不抛到主线程。
 */
public final class DoubanRecommendService {

    private static final String TAG = "DoubanRecommend";
    private static final int MAX = 8;
    private static final Pattern SUBJECT_ID = Pattern.compile("/subject/(\\d+)/");
    private static final Pattern REC_BLOCK = Pattern.compile(
            "recommendations-bd[\\s\\S]*?</div>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REC_ITEM = Pattern.compile(
            "<a[^>]*href=\"[^\"]*/subject/(\\d+)/[^\"]*\"[^>]*>([^<]{1,40})</a>",
            Pattern.CASE_INSENSITIVE);

    private DoubanRecommendService() {}

    public static List<AiRecommendService.Item> load(String title) throws Exception {
        List<AiRecommendService.Item> out = new ArrayList<>();
        if (TextUtils.isEmpty(title)) return out;
        String q = title.trim();
        // 去掉常见集数后缀，提高命中
        q = q.replaceAll("(?i)[\\s\\-_]*第?[0-9一二三四五六七八九十百]+[集期话].*$", "").trim();
        q = q.replaceAll("(?i)[\\s\\-_]*S\\d{1,2}E\\d{1,3}.*$", "").trim();
        if (q.isEmpty()) q = title.trim();

        String subjectId = resolveSubjectId(q);
        if (TextUtils.isEmpty(subjectId)) {
            Log.w(TAG, "no subject for " + q);
            return out;
        }

        // 1) rexxar 接口（轻量 JSON）
        try {
            out.addAll(loadRexxar(subjectId, q));
        } catch (Throwable e) {
            Log.w(TAG, "rexxar fail: " + e.getMessage());
        }
        if (!out.isEmpty()) return limit(out);

        // 2) 网页「喜欢这部的人也喜欢」
        try {
            out.addAll(loadHtml(subjectId, q));
        } catch (Throwable e) {
            Log.w(TAG, "html fail: " + e.getMessage());
        }
        return limit(out);
    }

    private static List<AiRecommendService.Item> limit(List<AiRecommendService.Item> list) {
        if (list.size() <= MAX) return list;
        return new ArrayList<>(list.subList(0, MAX));
    }

    private static String resolveSubjectId(String q) throws Exception {
        String url = "https://movie.douban.com/j/subject_suggest?q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8.name());
        Map<String, String> headers = baseHeaders("https://movie.douban.com/");
        String body = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(body) || !body.trim().startsWith("[")) return null;
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("id") && !o.get("id").isJsonNull()) {
                return o.get("id").getAsString();
            }
            if (o.has("url") && !o.get("url").isJsonNull()) {
                Matcher m = SUBJECT_ID.matcher(o.get("url").getAsString());
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private static List<AiRecommendService.Item> loadRexxar(String subjectId, String exclude) throws Exception {
        List<AiRecommendService.Item> out = new ArrayList<>();
        // movie / tv 都试
        String[] paths = {
                "https://m.douban.com/rexxar/api/v2/movie/" + subjectId + "/recommendations?count=" + MAX,
                "https://m.douban.com/rexxar/api/v2/tv/" + subjectId + "/recommendations?count=" + MAX,
                "https://m.douban.com/rexxar/api/v2/subject/" + subjectId + "/recommendations?count=" + MAX
        };
        Map<String, String> headers = baseHeaders("https://m.douban.com/movie/subject/" + subjectId + "/");
        headers.put("Accept", "application/json, text/plain, */*");
        for (String url : paths) {
            try {
                String body = OkHttp.string(url, headers);
                if (TextUtils.isEmpty(body)) continue;
                JsonElement root = JsonParser.parseString(body);
                JsonArray arr = null;
                if (root.isJsonArray()) arr = root.getAsJsonArray();
                else if (root.isJsonObject()) {
                    JsonObject o = root.getAsJsonObject();
                    if (o.has("subjects") && o.get("subjects").isJsonArray()) arr = o.getAsJsonArray("subjects");
                    else if (o.has("items") && o.get("items").isJsonArray()) arr = o.getAsJsonArray("items");
                    else if (o.has("data") && o.get("data").isJsonArray()) arr = o.getAsJsonArray("data");
                }
                if (arr == null) continue;
                LinkedHashMap<String, AiRecommendService.Item> map = new LinkedHashMap<>();
                for (JsonElement el : arr) {
                    if (el == null || !el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    String title = firstString(o, "title", "name", "orginal_title", "original_title");
                    if (TextUtils.isEmpty(title)) continue;
                    title = title.trim();
                    if (title.equals(exclude) || title.equals(exclude.trim())) continue;
                    int year = 0;
                    try {
                        if (o.has("year") && !o.get("year").isJsonNull()) {
                            String ys = o.get("year").getAsString().replaceAll("\\D", "");
                            if (!ys.isEmpty()) year = Integer.parseInt(ys.substring(0, Math.min(4, ys.length())));
                        }
                    } catch (Throwable ignored) {}
                    map.put(title, new AiRecommendService.Item(title, year, "", ""));
                    if (map.size() >= MAX) break;
                }
                if (!map.isEmpty()) {
                    out.addAll(map.values());
                    return out;
                }
            } catch (Throwable e) {
                Log.w(TAG, "rexxar path fail " + url + " " + e.getMessage());
            }
        }
        return out;
    }

    private static List<AiRecommendService.Item> loadHtml(String subjectId, String exclude) throws Exception {
        List<AiRecommendService.Item> out = new ArrayList<>();
        String url = "https://movie.douban.com/subject/" + subjectId + "/";
        Map<String, String> headers = baseHeaders(url);
        String html = OkHttp.string(url, headers);
        if (TextUtils.isEmpty(html)) return out;
        Matcher block = REC_BLOCK.matcher(html);
        String section = html;
        if (block.find()) section = block.group();
        Matcher m = REC_ITEM.matcher(section);
        LinkedHashMap<String, AiRecommendService.Item> map = new LinkedHashMap<>();
        while (m.find()) {
            String title = m.group(2) == null ? "" : m.group(2).trim();
            if (title.isEmpty() || title.equals(exclude)) continue;
            if (title.contains("的图片") || title.length() > 30) continue;
            map.put(title, new AiRecommendService.Item(title, 0, "", ""));
            if (map.size() >= MAX) break;
        }
        out.addAll(map.values());
        return out;
    }

    private static String firstString(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try {
                    String s = o.get(k).getAsString();
                    if (!TextUtils.isEmpty(s)) return s;
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private static Map<String, String> baseHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        h.put("Referer", referer);
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return h;
    }
}
