package com.fongmi.android.tv.subtitle;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 精简版射手网(Assrt)自动匹配字幕：播放就绪后按片名+集名搜索并应用。
 * 不依赖 TMDB / 实时 AI 字幕。
 */
public final class AssrtSubtitleMatch {

    private static final String TAG = "AssrtSub";
    private static final String API = "https://api.assrt.net/v1";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final AtomicInteger GEN = new AtomicInteger();

    private AssrtSubtitleMatch() {
    }

    public interface PlayerProvider {
        PlayerManager get();
    }

    public static void onPlayerReady(Activity activity, History history, Episode episode, PlayerProvider playerProvider) {
        if (activity == null || playerProvider == null) return;
        if (!Setting.isSubtitleAutoMatchEnabled()) return;
        if (TextUtils.isEmpty(Setting.getSubtitleAssrtToken())) return;
        String title = history != null ? history.getVodName() : "";
        String ep = episode != null ? episode.getName() : "";
        if (TextUtils.isEmpty(title)) return;
        final int gen = GEN.incrementAndGet();
        final String query = buildQuery(title, ep);
        Task.execute(() -> {
            try {
                Candidate hit = searchBest(query);
                if (hit == null) {
                    Log.i(TAG, "no candidate q=" + query);
                    return;
                }
                if (gen != GEN.get()) return;
                File file = resolve(hit);
                if (file == null || !file.isFile()) {
                    Log.w(TAG, "resolve failed id=" + hit.id);
                    return;
                }
                if (gen != GEN.get()) return;
                Sub sub = Sub.from(file.getAbsolutePath());
                if (!TextUtils.isEmpty(hit.name)) {
                    // Sub.from uses path as name; keep file path url
                }
                App.post(() -> {
                    if (gen != GEN.get()) return;
                    if (activity.isFinishing()) return;
                    PlayerManager player = playerProvider.get();
                    if (player == null || player.isEmpty()) return;
                    player.setSub(sub);
                    Notify.show(activity.getString(R.string.subtitle_auto_match_hit, hit.name));
                    Log.i(TAG, "applied " + hit.name);
                });
            } catch (Exception e) {
                Log.w(TAG, "auto match failed q=" + query + " err=" + e.getMessage());
            }
        });
    }

    public static void cancel() {
        GEN.incrementAndGet();
    }

    private static String buildQuery(String title, String episode) {
        String q = title == null ? "" : title.trim();
        if (!TextUtils.isEmpty(episode)) q = q + " " + episode.trim();
        return q.trim();
    }

    private static Candidate searchBest(String query) throws Exception {
        String token = Setting.getSubtitleAssrtToken();
        String url = API + "/sub/search?token=" + enc(token) + "&q=" + enc(query) + "&is_file=1&cnt=15";
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (safeInt(root, "status") != 0) return null;
            JsonObject sub = safeObject(root, "sub");
            JsonArray subs = safeArray(sub, "subs");
            String prefer = Setting.getSubtitlePreferredLanguage();
            List<Candidate> all = new ArrayList<>();
            for (JsonElement el : subs) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                String id = first(item, "id", "fileid");
                if (TextUtils.isEmpty(id)) continue;
                String name = first(item, "name", "sub_name", "m_version", "m_title");
                String lang = "";
                JsonObject langObj = safeObject(item, "lang");
                if (langObj.size() > 0) lang = first(langObj, "desc");
                if (TextUtils.isEmpty(lang)) lang = first(item, "m_lang");
                all.add(new Candidate(id, name, lang));
            }
            if (all.isEmpty()) return null;
            Candidate best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Candidate c : all) {
                int score = score(c, prefer);
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            return best;
        }
    }

    private static int score(Candidate c, String prefer) {
        int s = 0;
        String blob = ((c.name == null ? "" : c.name) + " " + (c.lang == null ? "" : c.lang)).toLowerCase(Locale.ROOT);
        if ("zh".equals(prefer) || "chs".equals(prefer) || "cht".equals(prefer)) {
            if (blob.contains("简") || blob.contains("chs") || blob.contains("zh-cn") || blob.contains("简体")) s += 30;
            if (blob.contains("繁") || blob.contains("cht") || blob.contains("zh-tw")) s += "cht".equals(prefer) ? 30 : 10;
            if (blob.contains("中文") || blob.contains("chinese") || blob.contains("zh")) s += 15;
        } else if ("en".equals(prefer)) {
            if (blob.contains("英") || blob.contains("eng") || blob.contains("english") || blob.matches(".*\\ben\\b.*")) s += 30;
        }
        if (blob.contains(".srt") || blob.contains("srt")) s += 5;
        if (blob.contains(".ass") || blob.contains("ass")) s += 3;
        return s;
    }

    private static File resolve(Candidate candidate) throws Exception {
        String token = Setting.getSubtitleAssrtToken();
        String url = API + "/sub/detail?token=" + enc(token) + "&id=" + enc(candidate.id);
        try (Response response = OkHttp.client().newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JsonObject root = JsonParser.parseString(response.body().string()).getAsJsonObject();
            if (safeInt(root, "status") != 0) return null;
            JsonObject sub = safeObject(root, "sub");
            JsonArray subs = safeArray(sub, "subs");
            if (subs.size() == 0) return null;
            JsonObject first = null;
            for (JsonElement el : subs) if (el.isJsonObject()) { first = el.getAsJsonObject(); break; }
            if (first == null) return null;
            String downloadUrl = first(first, "url", "filelist_url");
            // some responses nest filelist
            if (TextUtils.isEmpty(downloadUrl)) {
                JsonArray filelist = safeArray(first, "filelist");
                for (JsonElement el : filelist) {
                    if (!el.isJsonObject()) continue;
                    downloadUrl = first(el.getAsJsonObject(), "url");
                    if (!TextUtils.isEmpty(downloadUrl)) break;
                }
            }
            if (TextUtils.isEmpty(downloadUrl)) return null;
            String filename = first(first, "filename", "name");
            if (TextUtils.isEmpty(filename)) filename = candidate.name;
            File dir = new File(Path.cache(), "assrt_sub");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("mkdir_assrt");
            String suffix = suffix(filename);
            File target = new File(dir, Util.md5(candidate.id) + suffix);
            download(downloadUrl, target);
            if (isZip(target) || suffix.equalsIgnoreCase(".zip")) {
                File folder = new File(dir, Util.md5(candidate.id) + "_zip");
                if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("mkdir_zip");
                FileUtil.zipDecompress(target, folder);
                File picked = pickSubtitle(folder);
                return picked != null ? picked : target;
            }
            return target;
        }
    }

    private static void download(String url, File target) throws Exception {
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
        if (filename != null && filename.contains(".")) return filename.substring(filename.lastIndexOf('.'));
        return ".srt";
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String first(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try {
                    String s = o.get(k).getAsString();
                    if (!TextUtils.isEmpty(s)) return s;
                } catch (Exception ignored) {
                }
            }
        }
        return "";
    }

    private static int safeInt(JsonObject o, String key) {
        try {
            return o != null && o.has(key) ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static JsonObject safeObject(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray safeArray(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static final class Candidate {
        final String id, name, lang;
        Candidate(String id, String name, String lang) {
            this.id = id;
            this.name = name;
            this.lang = lang;
        }
    }
}
