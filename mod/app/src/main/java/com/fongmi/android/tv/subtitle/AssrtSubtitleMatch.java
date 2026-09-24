package com.fongmi.android.tv.subtitle;

import android.app.Activity;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
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
    private static volatile String sPendingSelectName;
    private static volatile String sPendingSelectFormat;

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


    /** 立即挂载外挂字幕：写入 spec + 用 player.getKey() 记选中轨（与 restoreTrack 同一把钥匙） */
    public static void applyToPlayer(PlayerManager player, File file, String display, String lang, String format) {
        if (player == null || file == null || !file.isFile()) return;
        if (TextUtils.isEmpty(format)) {
            format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(file.getName());
        }
        if (TextUtils.isEmpty(display)) display = file.getName();
        String trackLabel = trackLabelFor(display, format, file.getName());
        Sub sub = Sub.create(trackLabel, file.getAbsolutePath(), lang == null ? "" : lang, format);
        sub.setFlag(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED);
        player.setSub(sub);
        sPendingSelectName = trackLabel;
        sPendingSelectFormat = format;
        persistTextTrackSelection(player, trackLabel, format);
        try {
            rememberSub(sLastHistory, sLastEpisode, file, display, lang, format);
        } catch (Throwable ignored) {
        }
        // setMediaItem 后轨道恢复可能先选内嵌，延迟再强制选外挂名
        final String disp = trackLabel;
        final String fmt = format;
        final PlayerManager pm = player;
        App.post(() -> persistAndSelectText(pm, disp, fmt), 300);
        App.post(() -> persistAndSelectText(pm, disp, fmt), 800);
        App.post(() -> persistAndSelectText(pm, disp, fmt), 1600);
        App.post(() -> persistAndSelectText(pm, disp, fmt), 3200);
        App.post(() -> persistAndSelectText(pm, disp, fmt), 5000);
    }


    /** 与字幕列表 UI 对齐：奥德赛，SRT */
    private static String trackLabelFor(String display, String format, String fileName) {
        String base = !TextUtils.isEmpty(display) ? display.trim() : "";
        if (TextUtils.isEmpty(base) && !TextUtils.isEmpty(fileName)) {
            base = fileName;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        String tag = "SRT";
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT);
        String fn = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (f.contains("vtt") || fn.endsWith(".vtt")) tag = "VTT";
        else if (f.contains("ssa") || f.contains("ass") || fn.endsWith(".ass") || fn.endsWith(".ssa")) tag = "ASS";
        else if (f.contains("ttml") || fn.endsWith(".ttml")) tag = "TTML";
        else if (f.contains("subrip") || fn.endsWith(".srt") || f.contains("application/x-subrip")) tag = "SRT";
        if (!TextUtils.isEmpty(base) && base.toUpperCase(Locale.ROOT).contains(tag)) return base;
        if (TextUtils.isEmpty(base)) return tag;
        return base + "，" + tag;
    }

    private static void persistTextTrackSelection(PlayerManager player, String display, String format) {
        try {
            if (player == null) return;
            String key = player.getKey();
            if (TextUtils.isEmpty(key)) return;
            Track track = new Track(C.TRACK_TYPE_TEXT, display, TextUtils.isEmpty(format) ? "text/x-ssa" : format);
            track.setKey(key);
            track.setSelected(true);
            track.save();
        } catch (Throwable ignored) {
        }
    }

    private static void persistAndSelectText(PlayerManager player, String display, String format) {
        try {
            if (player == null || player.isEmpty()) return;
            persistTextTrackSelection(player, display, format);
            // 优先：从当前 Tracks 里找出外挂文字轨再 setTrack（名/ mime 与 Exo 一致）
            if (selectExternalFromCurrentTracks(player, display)) return;
            java.util.ArrayList<Track> list = new java.util.ArrayList<>();
            Track track = new Track(C.TRACK_TYPE_TEXT, display, TextUtils.isEmpty(format) ? "application/x-subrip" : format);
            track.setKey(player.getKey());
            track.setSelected(true);
            list.add(track);
            try {
                player.setTrack(list);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 在已加载的 Tracks 中选中外挂字幕组（SRT/VTT/SSA 或 id 含 external） */
    private static boolean selectExternalFromCurrentTracks(PlayerManager player, String display) {
        try {
            Tracks tracks = player.getCurrentTracks();
            if (tracks == null || tracks.isEmpty()) return false;
            String bestName = null;
            String bestMime = null;
            int bestScore = -1;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    Format f = group.getTrackFormat(i);
                    if (f == null) continue;
                    int score = scoreExternalFormat(f, display);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMime = f.sampleMimeType;
                        if (!TextUtils.isEmpty(f.label)) bestName = f.label;
                        else if (!TextUtils.isEmpty(f.id)) bestName = f.id;
                        else if (!TextUtils.isEmpty(display)) bestName = display;
                        else bestName = "sub";
                    }
                }
            }
            if (bestScore < 10 || TextUtils.isEmpty(bestName)) return false;
            // 尝试多个名字：列表里是「奥德赛，SRT」，setTrack 必须对得上
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            names.add(bestName);
            if (!TextUtils.isEmpty(display)) {
                names.add(display);
                int c = Math.max(display.indexOf('，'), display.indexOf(','));
                if (c > 0) names.add(display.substring(0, c).trim());
            }
            int c2 = Math.max(bestName.indexOf('，'), bestName.indexOf(','));
            if (c2 > 0) names.add(bestName.substring(0, c2).trim());
            String mime = bestMime == null ? "application/x-subrip" : bestMime;
            boolean ok = false;
            for (String nm : names) {
                if (TextUtils.isEmpty(nm)) continue;
                try {
                    java.util.ArrayList<Track> list = new java.util.ArrayList<>();
                    Track track = new Track(C.TRACK_TYPE_TEXT, nm, mime);
                    track.setKey(player.getKey());
                    track.setSelected(true);
                    list.add(track);
                    player.setTrack(list);
                    ok = true;
                    Log.i(TAG, "selectExternal score=" + bestScore + " name=" + nm + " mime=" + mime);
                } catch (Throwable ignored) {
                }
            }
            return ok;
        } catch (Throwable e) {
            Log.w(TAG, "selectExternal failed: " + e.getMessage());
            return false;
        }
    }

    private static int scoreExternalFormat(Format f, String display) {
        int s = 0;
        String id = f.id == null ? "" : f.id.toLowerCase(Locale.ROOT);
        String label = f.label == null ? "" : f.label;
        String labelLow = label.toLowerCase(Locale.ROOT);
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType.toLowerCase(Locale.ROOT);
        if (id.contains("external") || id.startsWith("ext")) s += 50;
        if (mime.contains("subrip") || mime.contains("application/x-subrip") || labelLow.contains("srt")) s += 80;
        if (mime.contains("vtt") || labelLow.contains("vtt")) s += 70;
        if (mime.contains("ssa") || mime.contains("ass") || labelLow.contains("ass") || labelLow.contains("ssa")) s += 70;
        if (mime.contains("ttml") || labelLow.contains("ttml")) s += 60;
        if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) s += 20;
        if ((f.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) s += 5;
        if (!TextUtils.isEmpty(display)) {
            String d = display.trim();
            String dBase = d;
            // display 可能是「奥德赛，SRT」
            int comma = Math.max(d.indexOf('，'), d.indexOf(','));
            if (comma > 0) dBase = d.substring(0, comma).trim();
            if (label.contains(d) || label.contains(dBase)) s += 40;
            if (labelLow.contains(dBase.toLowerCase(Locale.ROOT))) s += 20;
        }
        // 内嵌 PGS 等：大力降权（截图里默认项就是 PGS）
        if (mime.contains("pgs") || labelLow.contains("pgs") || mime.contains("vobsub") || mime.contains("dvb") || mime.startsWith("image/")) {
            s -= 100;
        }
        return s;
    }


    public static void selectPendingIfAny(PlayerManager player) {
        try {
            if (player == null || player.isEmpty()) return;
            String name = sPendingSelectName;
            String fmt = sPendingSelectFormat;
            if (TextUtils.isEmpty(name)) {
                // 无 pending 名时仍尝试选外挂轨
                selectExternalFromCurrentTracks(player, "");
                return;
            }
            persistAndSelectText(player, name, fmt);
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
                    if (attempt < 24) waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, attempt + 1);
                    return;
                }
                // 多试几次：历史刚进时 episode 可能尚未对齐
                selectPendingIfAny(player);
                if (tryRestoreSub(activity, history != null ? history : sLastHistory, episode != null ? episode : sLastEpisode, playerProvider)) {
                    selectPendingIfAny(player);
                    return;
                }
                if (attempt < 6) {
                    waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, attempt + 1);
                    return;
                }
                if (!Setting.isSubtitleAutoMatchEnabled()) return;
                if (TextUtils.isEmpty(keyword)) return;
                Task.execute(() -> doAutoMatch(activity, playerProvider, keyword, gen));
            } catch (Throwable e) {
                if (attempt < 24) waitPlayingThenRestoreOrMatch(activity, history, episode, playerProvider, keyword, gen, attempt + 1);
            }
        }, attempt == 0 ? 600 : 400);
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


    private static String subCacheKey(String historyKey, String episodePart) {
        String k = historyKey == null ? "" : historyKey;
        String e = episodePart == null ? "" : episodePart.trim();
        return "ext_sub_" + Util.md5(k + "|" + e);
    }

    /** 同一部片可能集名/备注不一致，写入多个键方便重进命中 */
    private static java.util.List<String> subCacheKeys(History history, Episode episode) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        String hk = "";
        try {
            if (history != null && history.getKey() != null) hk = history.getKey();
        } catch (Throwable ignored) {
        }
        String epName = "";
        String remarks = "";
        try {
            if (episode != null && episode.getName() != null) epName = episode.getName().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (history != null && history.getVodRemarks() != null) remarks = history.getVodRemarks().trim();
        } catch (Throwable ignored) {
        }
        if (!epName.isEmpty()) keys.add(subCacheKey(hk, epName));
        if (!remarks.isEmpty()) keys.add(subCacheKey(hk, remarks));
        keys.add(subCacheKey(hk, "")); // 仅按片
        // 兼容旧版单键
        keys.add(subCacheKey(history != null ? String.valueOf(history.getKey()) : "", epName));
        return new java.util.ArrayList<>(keys);
    }

    public static void rememberSub(History history, Episode episode, File file, String name, String lang, String format) {
        try {
            if (file == null || !file.isFile()) return;
            if (history == null) history = sLastHistory;
            if (episode == null) episode = sLastEpisode;
            String payload = file.getAbsolutePath() + "\u0001"
                    + (name == null ? "" : name) + "\u0001"
                    + (lang == null ? "" : lang) + "\u0001"
                    + (format == null ? "" : format);
            for (String key : subCacheKeys(history, episode)) {
                Prefers.put(key, payload);
            }
            Log.i(TAG, "remember sub keys=" + subCacheKeys(history, episode).size() + " file=" + file.getName());
        } catch (Throwable ignored) {
        }
    }


    /** 从缓存解析字幕文件信息，未命中返回 null */
    public static String[] loadCachedSubPayload(History history, Episode episode) {
        try {
            for (String key : subCacheKeys(history, episode)) {
                String v = Prefers.getString(key);
                if (!TextUtils.isEmpty(v)) {
                    String[] parts = v.split("\u0001", -1);
                    if (parts.length >= 1 && !TextUtils.isEmpty(parts[0]) && new File(parts[0]).isFile()) {
                        return parts;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 起播前挂到 Result.subs，避免先播默认轨再 setSub 被轨道恢复盖掉。
     * Result.setSubs 仅在空列表时生效，故用反射强制写入。
     */
    public static void attachRememberedSub(Object result, History history, Episode episode) {
        if (result == null) return;
        if (history == null) history = sLastHistory;
        if (episode == null) episode = sLastEpisode;
        String[] parts = loadCachedSubPayload(history, episode);
        if (parts == null) return;
        try {
            File file = new File(parts[0]);
            String name = parts.length > 1 && !TextUtils.isEmpty(parts[1]) ? parts[1] : file.getName();
            String lang = parts.length > 2 ? parts[2] : "";
            String format = parts.length > 3 ? parts[3] : "";
            if (TextUtils.isEmpty(format)) format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(file.getName());
            Sub sub = Sub.create(name, file.getAbsolutePath(), lang, format);
            sub.setFlag(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED);
            java.util.ArrayList<Sub> list = new java.util.ArrayList<>();
            list.add(sub);
            try {
                java.lang.reflect.Field f = result.getClass().getDeclaredField("subs");
                f.setAccessible(true);
                f.set(result, list);
            } catch (Throwable e) {
                try {
                    java.lang.reflect.Method m = result.getClass().getMethod("setSubs", java.util.List.class);
                    m.invoke(result, list);
                } catch (Throwable ignored) {
                }
            }
            // 清掉「禁用字幕」记忆，防止 restoreTrack 关掉外挂
            try {
                String hk = history != null ? history.getKey() : null;
                if (!TextUtils.isEmpty(hk)) {
                    Track track = new Track(C.TRACK_TYPE_TEXT, name, TextUtils.isEmpty(format) ? "text/x-ssa" : format);
                    track.setKey(hk);
                    track.setSelected(true);
                    track.save();
                }
            } catch (Throwable ignored) {
            }
            sPendingSelectName = name;
            sPendingSelectFormat = format;
            Log.i(TAG, "attachRememberedSub " + name);
        } catch (Throwable e) {
            Log.w(TAG, "attachRememberedSub failed: " + e.getMessage());
        }
    }

    public static boolean tryRestoreSub(Activity activity, History history, Episode episode, PlayerProvider playerProvider) {
        try {
            String raw = null;
            for (String key : subCacheKeys(history, episode)) {
                String v = Prefers.getString(key);
                if (!TextUtils.isEmpty(v)) {
                    raw = v;
                    break;
                }
            }
            if (TextUtils.isEmpty(raw)) {
                Log.i(TAG, "restore miss no cache");
                return false;
            }
            String[] parts = raw.split("\u0001", -1);
            if (parts.length < 1 || TextUtils.isEmpty(parts[0])) return false;
            File file = new File(parts[0]);
            if (!file.isFile()) {
                Log.w(TAG, "restore miss file gone " + parts[0]);
                return false;
            }
            String name = parts.length > 1 ? parts[1] : file.getName();
            String lang = parts.length > 2 ? parts[2] : "";
            String format = parts.length > 3 ? parts[3] : "";
            if (TextUtils.isEmpty(format)) format = com.fongmi.android.tv.player.PlayerHelper.getSubtitleMimeType(file.getName());
            PlayerManager player = playerProvider == null ? null : playerProvider.get();
            if (player == null || player.isEmpty()) return false;
            applyToPlayer(player, file, name, lang, format);
            // 起播后轨道恢复可能把默认内嵌轨抢回去，延迟再挂一次
            final File f2 = file;
            final String n2 = name, l2 = lang, fmt2 = format;
            final PlayerProvider pp = playerProvider;
            com.fongmi.android.tv.App.post(() -> {
                try {
                    PlayerManager p2 = pp.get();
                    if (p2 != null && !p2.isEmpty()) applyToPlayer(p2, f2, n2, l2, fmt2);
                } catch (Throwable ignored) {
                }
            }, 1200);
            com.fongmi.android.tv.App.post(() -> {
                try {
                    PlayerManager p2 = pp.get();
                    if (p2 != null && !p2.isEmpty()) applyToPlayer(p2, f2, n2, l2, fmt2);
                } catch (Throwable ignored) {
                }
            }, 2800);
            Log.i(TAG, "restored sub " + name + " path=" + file.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "restore sub failed: " + e.getMessage());
            return false;
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
