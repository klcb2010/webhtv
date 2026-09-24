package com.fongmi.android.tv.subtitle;

import android.app.Activity;

import androidx.media3.common.C;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 在线字幕匹配（对齐 Silent 实用路径）：
 * - 射手网 Assrt（需 Token）
 * - 迅雷字幕（无需 Token，流媒体场景往往更有效）
 * 多关键词尝试；不依赖 TMDB / 实时 AI。
 */
public final class AssrtSubtitleMatch {

    private static final String TAG = "SubtitleMatch";
    private static final String ASSRT_API = "https://api.assrt.net/v1";
    private static final String XUNLEI_API = "https://api-shoulei-ssl.xunlei.com/oracle/subtitle?name=";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final AtomicInteger GEN = new AtomicInteger();
    /** 最近一次片名+集数，供手动搜索预填（不依赖对话框入参是否传到） */
    private static volatile String sLastKeyword = "";
    private static volatile History sLastHistory;
    private static volatile Episode sLastEpisode;

    private AssrtSubtitleMatch() {
    }

    public interface PlayerProvider {
        PlayerManager get();
    }

    public static final class Item {
        public final String provider; // assrt | xunlei
        public final String id;
        public final String name;
        public final String lang;
        public final String url; // xunlei direct url; empty for assrt

        Item(String provider, String id, String name, String lang, String url) {
            this.provider = provider;
            this.id = id;
            this.name = name;
            this.lang = lang;
            this.url = url == null ? "" : url;
        }

        public String label() {
            if (!TextUtils.isEmpty(lang)) return (name == null ? id : name) + "  (" + lang + ")";
            return name == null ? id : name;
        }

        public String sourceTag() {
            return "xunlei".equals(provider) ? "迅雷" : "射手";
        }
    }

    public static String displayName(Item item) {
        return displayNameForKeyword(item, item == null ? "" : item.name);
    }


    /** 立即挂载外挂字幕：写入 spec + 覆盖「禁用字幕」轨道记忆，避免还要再进字幕菜单点一次 */
    public static void applyToPlayer(PlayerManager player, File file, String display, String lang, String format) {
        if (player == null || file == null || !file.isFile()) return;
        if (TextUtils.isEmpty(format)) {
            format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(file.getName());
        }
        if (TextUtils.isEmpty(display)) display = file.getName();
        Sub sub = Sub.create(display, file.getAbsolutePath(), lang == null ? "" : lang, format);
        sub.setFlag(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED);
        player.setSub(sub);
        try {
            String key = player.getKey();
            if (!TextUtils.isEmpty(key)) {
                Track track = new Track(C.TRACK_TYPE_TEXT, display, TextUtils.isEmpty(format) ? "text/x-ssa" : format);
                track.setKey(key);
                track.setSelected(true);
                track.save();
            }
        } catch (Throwable ignored) {
        }
        try {
            rememberSub(sLastHistory, sLastEpisode, file, display, lang, format);
        } catch (Throwable ignored) {
        }
    }

    public static void onPlayerReady(Activity activity, History history, Episode episode, PlayerProvider playerProvider) {
        if (activity == null || playerProvider == null) return;
        String title = history != null && history.getVodName() != null ? history.getVodName().trim() : "";
        String ep = episode != null && episode.getName() != null ? episode.getName().trim() : "";
        sLastHistory = history;
        sLastEpisode = episode;
        final String keyword = formatKeyword(title, ep);
        updateKeyword(keyword);
        // 历史重进：优先恢复上次选用的外挂字幕文件
        final int gen = GEN.incrementAndGet();
        waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, 0);
    }

    private static void waitPlayingThenRestoreOrMatch(Activity activity, History history, Episode episode, PlayerProvider playerProvider, String keyword, int gen, int attempt) {
        App.post(() -> {
            if (gen != GEN.get() || activity.isFinishing()) return;
            try {
                PlayerManager player = playerProvider.get();
                if (player == null || player.isEmpty()) {
                    if (attempt < 20) waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, attempt + 1);
                    return;
                }
                if (tryRestoreSub(activity, history, episode, playerProvider)) {
                    return;
                }
                if (!Setting.isSubtitleAutoMatchEnabled()) return;
                if (TextUtils.isEmpty(keyword)) return;
                Task.execute(() -> doAutoMatch(activity, playerProvider, keyword, gen));
            } catch (Throwable e) {
                if (attempt < 20) waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, attempt + 1);
            }
        }, attempt == 0 ? 800 : 500);
    }

    /**
     * 从过长片源标题里抽出适合搜字幕的短名。
     * 例：2026恐怖片《奥德赛》/The.xxx → 奥德赛
     */
    public static String cleanTitleForSearch(String title) {
        if (title == null) return "";
        String t = title.trim();
        if (t.isEmpty()) return "";
        try {
            Matcher m = Pattern.compile("《([^》]+)》").matcher(t);
            if (m.find()) {
                String inside = m.group(1).trim();
                if (!inside.isEmpty()) return inside;
            }
            // 中文名在括号里：xxx（奥德赛）
            m = Pattern.compile("[（(]([\u4e00-\u9fff]{2,20})[）)]").matcher(t);
            if (m.find()) return m.group(1).trim();
        } catch (Throwable ignored) {
        }
        // 去掉年份前缀与常见类型词
        t = t.replaceAll("^\\d{4}\\s*", "");
        t = t.replaceAll("(?i)^(恐怖片|剧情片|喜剧片|动作片|爱情片|科幻片|悬疑片|战争片|纪录片|综艺|动漫|电影|电视剧)[\\s:：]*", "");
        // 取 / 或 | 前的中文段
        int cut = -1;
        for (char c : new char[]{'/', '|', '\\'}) {
            int i = t.indexOf(c);
            if (i > 0 && (cut < 0 || i < cut)) cut = i;
        }
        if (cut > 0) t = t.substring(0, cut).trim();
        // 去掉残留书名号
        t = t.replace("《", "").replace("》", "").trim();
        // 若仍很长且含空格，优先连续中文
        try {
            Matcher m = Pattern.compile("[\\u4e00-\\u9fff]{2,30}").matcher(t);
            if (m.find() && t.length() > 20) return m.group().trim();
        } catch (Throwable ignored) {
        }
        return t.trim();
    }

    /** 片名 + 集数；片名先 clean，避免整串文件名 */
    public static String formatKeyword(String title, String episode) {
        String t = cleanTitleForSearch(title);
        if (TextUtils.isEmpty(t) && title != null) t = title.trim();
        String e = episode == null ? "" : episode.trim();
        // 集数若是「xxx.mp4」这类文件名则忽略
        if (!TextUtils.isEmpty(e) && (e.contains(".mp4") || e.contains(".mkv") || e.contains(".ts"))) {
            e = "";
        }
        if (!TextUtils.isEmpty(t) && !TextUtils.isEmpty(e)) {
            if (t.contains(e)) return t;
            return t + " " + e;
        }
        if (!TextUtils.isEmpty(t)) return t;
        return e;
    }

    private static String subCacheKey(History history, Episode episode) {
        String k = history != null ? String.valueOf(history.getKey()) : "";
        String e = "";
        try {
            if (episode != null && episode.getName() != null) e = episode.getName().trim();
            else if (history != null && history.getVodRemarks() != null) e = history.getVodRemarks().trim();
        } catch (Throwable ignored) {
        }
        return "ext_sub_" + Util.md5(k + "|" + e);
    }

    /** 记住当前片+集选用的外挂字幕，历史重进可恢复 */
    public static void rememberSub(History history, Episode episode, File file, String name, String lang, String format) {
        try {
            if (file == null || !file.isFile()) return;
            if (history == null) history = sLastHistory;
            if (episode == null) episode = sLastEpisode;
            String payload = file.getAbsolutePath() + "\u0001"
                    + (name == null ? "" : name) + "\u0001"
                    + (lang == null ? "" : lang) + "\u0001"
                    + (format == null ? "" : format);
            Prefers.put(subCacheKey(history, episode), payload);
        } catch (Throwable ignored) {
        }
    }

    public static boolean tryRestoreSub(Activity activity, History history, Episode episode, PlayerProvider playerProvider) {
        try {
            String raw = Prefers.getString(subCacheKey(history, episode));
            if (TextUtils.isEmpty(raw)) return false;
            String[] parts = raw.split("\u0001", -1);
            if (parts.length < 1 || TextUtils.isEmpty(parts[0])) return false;
            File file = new File(parts[0]);
            if (!file.isFile()) return false;
            String name = parts.length > 1 ? parts[1] : file.getName();
            String lang = parts.length > 2 ? parts[2] : "";
            String format = parts.length > 3 ? parts[3] : "";
            if (TextUtils.isEmpty(format)) format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(file.getName());
            PlayerManager player = playerProvider == null ? null : playerProvider.get();
            if (player == null || player.isEmpty()) return false;
            applyToPlayer(player, file, name, lang, format);
            Log.i(TAG, "restored sub " + name + " path=" + file.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "restore sub failed: " + e.getMessage());
            return false;
        }
    }

    public static void updateKeyword(String title, String episode) {
        String k = formatKeyword(title, episode);
        if (!TextUtils.isEmpty(k)) sLastKeyword = k;
    }

    public static void updateKeyword(String keyword) {
        if (!TextUtils.isEmpty(keyword)) sLastKeyword = keyword.trim();
    }

    public static String lastKeyword() {
        return sLastKeyword == null ? "" : sLastKeyword;
    }

    private static void waitPlayingThenMatch(Activity activity, PlayerProvider playerProvider, String keyword, int gen, int attempt) {
        if (gen != GEN.get()) return;
        long delayMs = attempt == 0 ? 2000L : 1000L;
        Task.schedule(() -> {
            if (gen != GEN.get()) return;
            App.post(() -> {
                if (gen != GEN.get() || activity.isFinishing()) return;
                PlayerManager player = playerProvider.get();
                boolean ready = player != null && !player.isEmpty();
                if (!ready) {
                    if (attempt < 20) waitPlayingThenMatch(activity, playerProvider, keyword, gen, attempt + 1);
                    else Log.i(TAG, "auto match give up, player not ready keyword=" + keyword);
                    return;
                }
                Task.execute(() -> doAutoMatch(activity, playerProvider, keyword, gen));
            });
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void doAutoMatch(Activity activity, PlayerProvider playerProvider, String keyword, int gen) {
        try {
            Map<String, Item> map = new LinkedHashMap<>();
            for (String q : buildQueriesFromKeyword(keyword)) {
                for (Item it : searchAllSources(q)) {
                    String key = it.provider + ":" + it.id;
                    if (!map.containsKey(key)) map.put(key, it);
                }
            }
            List<Item> items = new ArrayList<>(map.values());
            if (items.isEmpty()) {
                Log.i(TAG, "auto match empty keyword=" + keyword);
                return;
            }
            if (gen != GEN.get()) return;
            Item hit = pickBest(items);
            File file = downloadItem(hit);
            if (file == null || !file.isFile()) {
                Log.w(TAG, "auto resolve failed " + hit.label());
                return;
            }
            if (gen != GEN.get()) return;
            final File subFile = file;
            final Item applied = hit;
            final String display = displayNameForKeyword(applied, keyword);
            App.post(() -> {
                if (gen != GEN.get() || activity.isFinishing()) return;
                PlayerManager player = playerProvider.get();
                if (player == null || player.isEmpty()) return;
                String format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(applied.name);
                if (TextUtils.isEmpty(format)) format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(subFile.getName());
                applyToPlayer(player, subFile, display, applied.lang, format);
                Notify.show(activity.getString(R.string.subtitle_auto_match_hit, display));
                Log.i(TAG, "auto applied " + display + " src=" + applied.label());
            });
        } catch (Exception e) {
            Log.w(TAG, "auto match failed: " + e.getMessage());
        }
    }

    private static List<String> buildQueriesFromKeyword(String keyword) {
        List<String> qs = new ArrayList<>();
        if (!TextUtils.isEmpty(keyword)) qs.add(keyword.trim());
        String cleaned = keyword == null ? "" : keyword.trim();
        cleaned = cleaned.replaceAll("(?i)[\\s\\-_]*第?[0-9一二三四五六七八九十百]+[集期话].*$", "").trim();
        cleaned = cleaned.replaceAll("(?i)[\\s\\-_]*S\\d{1,2}E\\d{1,3}.*$", "").trim();
        if (!TextUtils.isEmpty(cleaned) && !cleaned.equals(keyword == null ? "" : keyword.trim())) qs.add(cleaned);
        return qs;
    }

    /** 显示名 = 片名 集数（与预填一致），不用远程乱文件名、不带来源前缀 */
    public static String displayNameForKeyword(Item item, String keyword) {
        if (!TextUtils.isEmpty(keyword)) return keyword.trim();
        if (item == null) return "";
        if (!TextUtils.isEmpty(item.name)) return item.name;
        return item.id == null ? "" : item.id;
    }

    public static void cancel() {
        GEN.incrementAndGet();
    }

    public static List<Item> searchList(String query) throws Exception {
        if (TextUtils.isEmpty(query)) return new ArrayList<>();
        Map<String, Item> map = new LinkedHashMap<>();
        // 手工搜索：先用用户词，再试去掉集数后缀的变体
        for (String q : buildQueries(query, "")) {
            for (Item it : searchAllSources(q)) {
                String key = it.provider + ":" + it.id;
                if (!map.containsKey(key)) map.put(key, it);
            }
        }
        List<Item> all = new ArrayList<>(map.values());
        all.sort((a, b) -> Integer.compare(score(b), score(a)));
        Log.i(TAG, "searchList q=" + query + " count=" + all.size());
        return all;
    }

    public static File downloadItem(Item item) throws Exception {
        if (item == null) return null;
        if ("xunlei".equals(item.provider)) return downloadXunlei(item);
        return resolveAssrt(item);
    }

    private static List<String> buildQueries(String title, String episode) {
        List<String> qs = new ArrayList<>();
        String t = title == null ? "" : title.trim();
        String e = episode == null ? "" : episode.trim();
        if (!TextUtils.isEmpty(t) && !TextUtils.isEmpty(e)) qs.add(t + " " + e);
        if (!TextUtils.isEmpty(t)) qs.add(t);
        String cleaned = t.replaceAll("(?i)[\\s\\-_]*第?[0-9一二三四五六七八九十百]+[集期话].*$", "").trim();
        cleaned = cleaned.replaceAll("(?i)[\\s\\-_]*S\\d{1,2}E\\d{1,3}.*$", "").trim();
        if (!TextUtils.isEmpty(cleaned) && !cleaned.equals(t)) {
            if (!TextUtils.isEmpty(e)) qs.add(cleaned + " " + e);
            qs.add(cleaned);
        }
        List<String> out = new ArrayList<>();
        for (String q : qs) {
            if (TextUtils.isEmpty(q)) continue;
            if (!out.contains(q)) out.add(q);
        }
        return out;
    }

    private static List<Item> searchAllSources(String query) {
        List<Item> items = new ArrayList<>();
        try {
            items.addAll(searchAssrt(query));
        } catch (Exception e) {
            Log.w(TAG, "assrt search err q=" + query + " " + e.getMessage());
        }
        try {
            items.addAll(searchXunlei(query));
        } catch (Exception e) {
            Log.w(TAG, "xunlei search err q=" + query + " " + e.getMessage());
        }
        return items;
    }

    private static List<Item> searchAssrt(String query) throws Exception {
        List<Item> items = new ArrayList<>();
        String token = Setting.getSubtitleAssrtToken();
        if (TextUtils.isEmpty(token)) {
            Log.i(TAG, "assrt skip empty token");
            return items;
        }
        // 先 is_file=1（与 Silent 一致），空结果再放宽一次
        items.addAll(searchAssrtOnce(query, token, true));
        if (items.isEmpty()) items.addAll(searchAssrtOnce(query, token, false));
        Log.i(TAG, "assrt total candidates=" + items.size() + " q=" + query);
        return items;
    }

    private static List<Item> searchAssrtOnce(String query, String token, boolean isFile) throws Exception {
        List<Item> items = new ArrayList<>();
        String url = ASSRT_API + "/sub/search?token=" + enc(token) + "&q=" + enc(query) + "&cnt=20";
        if (isFile) url += "&is_file=1";
        Log.i(TAG, "assrt search q=" + query + " is_file=" + isFile);
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://assrt.net/")
                .get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "assrt http " + response.code());
                return items;
            }
            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int status = asInt(root, "status", Integer.MIN_VALUE);
            if (status != Integer.MIN_VALUE && status != 0) {
                Log.w(TAG, "assrt status=" + status + " body=" + body.substring(0, Math.min(200, body.length())));
                return items;
            }
            JsonArray subs = asArray(asObject(root, "sub"), "subs");
            if (subs.size() == 0) subs = asArray(root, "subs");
            for (JsonElement el : subs) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String id = first(item, "id", "fileid");
                if (TextUtils.isEmpty(id)) continue;
                String name = first(item, "native_name", "name", "sub_name", "m_version", "m_title");
                if (TextUtils.isEmpty(name)) name = first(item, "videoname", "m_videoname");
                if (TextUtils.isEmpty(name)) name = "assrt-" + id;
                String lang = first(asObject(item, "lang"), "desc");
                if (TextUtils.isEmpty(lang)) lang = first(item, "m_lang", "lang");
                items.add(new Item("assrt", id, name, lang, ""));
            }
        }
        return items;
    }

    private static List<Item> searchXunlei(String query) throws Exception {
        List<Item> items = new ArrayList<>();
        String url = XUNLEI_API + enc(query);
        Log.i(TAG, "xunlei search q=" + query);
        Request request = new Request.Builder().url(url).header("User-Agent", UA).header("Referer", "https://sl-m-ssl.xunlei.com/").header("Connection", "close").get().build();
        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                Log.w(TAG, "xunlei http " + response.code());
                return items;
            }
            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            int code = asInt(root, "code", 0);
            if (code != 0) {
                Log.w(TAG, "xunlei code=" + code);
                return items;
            }
            String result = first(root, "result");
            if (!TextUtils.isEmpty(result) && !"ok".equalsIgnoreCase(result)) return items;
            JsonArray data = asArray(root, "data");
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String dl = first(item, "url");
                String id = first(item, "cid", "gcid");
                if (TextUtils.isEmpty(id)) id = dl;
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(dl)) continue;
                String name = first(item, "name");
                if (TextUtils.isEmpty(name)) name = id;
                String lang = "";
                JsonArray languages = asArray(item, "languages");
                for (JsonElement le : languages) {
                    try {
                        if (le != null && !le.isJsonNull()) {
                            lang = le.getAsString();
                            if (!TextUtils.isEmpty(lang)) break;
                        }
                    } catch (Exception ignored) {
                    }
                }
                items.add(new Item("xunlei", id, name, lang, dl));
            }
            Log.i(TAG, "xunlei candidates=" + items.size());
        }
        return items;
    }

    private static Item pickBest(List<Item> items) {
        Item best = items.get(0);
        int bestScore = score(best);
        for (Item it : items) {
            int s = score(it);
            if (s > bestScore) {
                bestScore = s;
                best = it;
            }
        }
        return best;
    }

    private static int score(Item c) {
        int s = 0;
        String prefer = Setting.getSubtitlePreferredLanguage();
        String blob = ((c.name == null ? "" : c.name) + " " + (c.lang == null ? "" : c.lang)).toLowerCase(Locale.ROOT);
        if ("zh".equals(prefer) || "chs".equals(prefer) || "cht".equals(prefer)) {
            if (blob.contains("简") || blob.contains("chs") || blob.contains("zh-cn") || blob.contains("简体")) s += 30;
            if (blob.contains("繁") || blob.contains("cht") || blob.contains("zh-tw")) s += "cht".equals(prefer) ? 30 : 10;
            if (blob.contains("中文") || blob.contains("chinese") || blob.contains("zh") || blob.contains("中字")) s += 15;
        } else if ("en".equals(prefer)) {
            if (blob.contains("英") || blob.contains("eng") || blob.contains("english")) s += 30;
        }
        if (blob.contains(".srt") || blob.endsWith("srt")) s += 5;
        if (blob.contains(".ass") || blob.contains("ass")) s += 3;
        if ("xunlei".equals(c.provider)) s += 2; // 流媒体场景略优先迅雷直链
        return s;
    }

    private static File resolveAssrt(Item candidate) throws Exception {
        String token = Setting.getSubtitleAssrtToken();
        if (TextUtils.isEmpty(token)) throw new IllegalStateException("no_token");
        String url = ASSRT_API + "/sub/detail?token=" + enc(token) + "&id=" + enc(candidate.id);
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url).header("User-Agent", UA).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            int status = asInt(root, "status", 0);
            if (status != 0) return null;
            JsonObject sub = asObject(root, "sub");
            JsonArray subs = asArray(sub, "subs");
            if (subs.size() == 0) return null;
            JsonObject first = null;
            for (JsonElement el : subs) if (el.isJsonObject()) { first = el.getAsJsonObject(); break; }
            if (first == null) return null;
            String downloadUrl = first(first, "url");
            if (TextUtils.isEmpty(downloadUrl)) {
                JsonArray filelist = asArray(first, "filelist");
                for (JsonElement el : filelist) {
                    if (!el.isJsonObject()) continue;
                    downloadUrl = first(el.getAsJsonObject(), "url");
                    if (!TextUtils.isEmpty(downloadUrl)) break;
                }
            }
            if (TextUtils.isEmpty(downloadUrl)) return null;
            String filename = first(first, "filename", "name");
            if (TextUtils.isEmpty(filename)) filename = candidate.name;
            File dir = new File(Path.cache(), "online_sub");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir");
            String suffix = suffix(filename);
            File target = new File(dir, Util.md5("assrt_" + candidate.id) + suffix);
            downloadRedirect(downloadUrl, target);
            if (isZip(target) || suffix.equalsIgnoreCase(".zip")) {
                File folder = new File(dir, Util.md5("assrt_" + candidate.id) + "_zip");
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("mkdir_zip");
                FileUtil.zipDecompress(target, folder);
                File picked = pickSubtitle(folder);
                return picked != null ? picked : target;
            }
            return target;
        }
    }

    private static File downloadXunlei(Item item) throws Exception {
        if (TextUtils.isEmpty(item.url)) return null;
        File dir = new File(Path.cache(), "online_sub");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir");
        String suffix = suffix(item.name);
        File target = new File(dir, Util.md5("xunlei_" + item.id) + suffix);
        Request request = new Request.Builder().url(item.url).header("User-Agent", UA).header("Referer", "https://sl-m-ssl.xunlei.com/").get().build();
        try (Response response = OkHttp.client().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("dl_" + response.code());
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
        return target;
    }

    private static void downloadRedirect(String url, File target) throws Exception {
        String current = url;
        for (int i = 0; i < 5; i++) {
            Request request = new Request.Builder().url(current).header("User-Agent", UA).header("Referer", "https://assrt.net/").get().build();
            Response response = OkHttp.noRedirect().newCall(request).execute();
            int code = response.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = response.header("Location");
                response.close();
                HttpUrl resolved = response.request().url().resolve(loc == null ? "" : loc);
                if (resolved == null) throw new IllegalStateException("redirect");
                current = resolved.toString();
                continue;
            }
            if (!response.isSuccessful() || response.body() == null) {
                response.close();
                throw new IllegalStateException("dl_" + code);
            }
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } finally {
                response.close();
            }
            return;
        }
        throw new IllegalStateException("redirect_overflow");
    }

    private static boolean isZip(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 0x50 && h[1] == 0x4B && h[2] == 0x03 && h[3] == 0x04;
        } catch (Exception e) {
            return false;
        }
    }

    private static File pickSubtitle(File folder) {
        List<File> hits = new ArrayList<>();
        collect(folder, hits);
        hits.sort((a, b) -> Integer.compare(weight(b.getName()), weight(a.getName())));
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static void collect(File file, List<File> out) {
        if (file == null) return;
        if (file.isFile()) {
            String n = file.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".srt") || n.endsWith(".ass") || n.endsWith(".ssa") || n.endsWith(".vtt")) out.add(file);
            return;
        }
        File[] children = file.listFiles();
        if (children != null) for (File c : children) collect(c, out);
    }

    private static int weight(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".srt")) return 3;
        if (n.endsWith(".ass") || n.endsWith(".ssa")) return 2;
        if (n.endsWith(".vtt")) return 1;
        return 0;
    }

    private static String suffix(String filename) {
        if (filename != null && filename.contains(".")) {
            String s = filename.substring(filename.lastIndexOf('.'));
            if (s.length() <= 8) return s;
        }
        return ".srt";
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /** 数字/字符串都能读成文本（Assrt id 常为 number） */
    private static String first(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (!o.has(k) || o.get(k).isJsonNull()) continue;
            JsonElement e = o.get(k);
            try {
                if (e.isJsonPrimitive()) {
                    JsonPrimitive p = e.getAsJsonPrimitive();
                    if (p.isString()) {
                        if (!TextUtils.isEmpty(p.getAsString())) return p.getAsString();
                    } else if (p.isNumber()) {
                        return p.getAsNumber().toString();
                    } else if (p.isBoolean()) {
                        return Boolean.toString(p.getAsBoolean());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static int asInt(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            JsonElement e = o.get(key);
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isNumber()) return p.getAsInt();
                if (p.isString()) return Integer.parseInt(p.getAsString().trim());
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    private static JsonObject asObject(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray asArray(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }
}
