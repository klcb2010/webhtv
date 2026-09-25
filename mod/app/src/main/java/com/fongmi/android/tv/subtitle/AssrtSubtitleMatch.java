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

    private static final String TAG = "AssrtSub";
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
    /** 用户明确选过外挂（或 apply 过文件）时，有内嵌也优先恢复外挂 */
    private static volatile boolean sPreferExternal;
    private static volatile long sLastForceOkAt;
    private static volatile boolean sForceSettled;
    /** true=不起播期 Media3 Override，避免有声无画 */
    private static final boolean SOFT_RESTORE_ONLY = true;

    private AssrtSubtitleMatch() {
    }

    static {
        try {
            android.util.Log.e("AssrtSub", "AssrtSubtitleMatch class loaded v2");
            android.util.Log.e("SubRestore", "AssrtSubtitleMatch class loaded v2");
            android.util.Log.e("SubtitleMatch", "AssrtSubtitleMatch class loaded v2");
        } catch (Throwable ignored) {}
    }

    private static void logi(String msg) {
        try {
            android.util.Log.i(TAG, msg);
            android.util.Log.i("SubtitleMatch", msg);
        } catch (Throwable ignored) {}
    }

    public static void primeExternalPreference(String name, String format) {
        try {
            if (!TextUtils.isEmpty(name)) sPendingSelectName = name;
            if (!TextUtils.isEmpty(format)) sPendingSelectFormat = format;
            sPreferExternal = true;
            sForceSettled = false;
            android.util.Log.i(TAG, "primeExternalPreference name=" + name + " fmt=" + format);
        } catch (Throwable ignored) {
        }
    }

    /** 由 Coordinator.onUserSetSub 同步写入 Assrt 文件缓存，保证 attach/tryRestore 命中 */
    public static void rememberSubFromCoordinator(History history, String url, String name, String lang, String format) {
        try {
            if (TextUtils.isEmpty(url)) return;
            if (url.contains("://")) {
                // 远程 URL：只记名字意图
                if (!TextUtils.isEmpty(name)) {
                    sPendingSelectName = name;
                    sPendingSelectFormat = format == null ? "" : format;
                    sPreferExternal = true;
                }
                return;
            }
            java.io.File file = new java.io.File(url);
            if (!file.isFile()) return;
            rememberSub(history, sLastEpisode, file, name, lang, format);
        } catch (Throwable e) {
            android.util.Log.w(TAG, "rememberSubFromCoordinator: " + e.getMessage());
        }
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
        sPreferExternal = true;
        sForceSettled = false;
        persistTextTrackSelection(player, trackLabel, format);
        try {
            rememberSub(sLastHistory, sLastEpisode, file, display, lang, format);
        } catch (Throwable ignored) {
        }
        // setMediaItem 后轨道恢复可能先选内嵌，延迟再强制选外挂名
        final String disp = trackLabel;
        final String fmt = format;
        final PlayerManager pm = player;
        // 少次延迟即可；过密 setTrack/Override 会触发 reprepare，续播时「拉扯」
        App.post(() -> persistAndSelectText(pm, disp, fmt), 1500);
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
            if (TextUtils.isEmpty(display)) return;
            String fmt = TextUtils.isEmpty(format) ? "application/x-subrip" : format;
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            try {
                if (player != null && !TextUtils.isEmpty(player.getKey())) keys.add(player.getKey());
            } catch (Throwable ignored) {
            }
            try {
                if (sLastHistory != null && !TextUtils.isEmpty(sLastHistory.getKey())) keys.add(sLastHistory.getKey());
            } catch (Throwable ignored) {
            }
            // 仅 history 时也要能写
            if (keys.isEmpty() && sLastHistory != null) {
                try { keys.add(String.valueOf(sLastHistory.getKey())); } catch (Throwable ignored) {}
            }
            for (String key : keys) {
                if (TextUtils.isEmpty(key)) continue;
                Track track = new Track(C.TRACK_TYPE_TEXT, display, fmt);
                track.setKey(key);
                track.setSelected(true);
                track.save();
            }
            // Prefers 再记一份名字，不依赖 Room key
            try {
                if (sLastHistory != null && !TextUtils.isEmpty(sLastHistory.getKey())) {
                    putCommit("ext_sub_name_" + Util.md5(sLastHistory.getKey()), display);
                    putCommit("ext_sub_fmt_" + Util.md5(sLastHistory.getKey()), fmt);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persistAndSelectText(PlayerManager player, String display, String format) {
        try {
            if (player == null || player.isEmpty()) return;
            persistTextTrackSelection(player, display, format);
            // 最稳：Media3 Override 直接点名 SRT 组
            if (forceSelectExternalViaMedia3(player)) return;
            // 其次：按名字 setTrack
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

    /**
     * 用 Media3 TrackSelectionOverride 强制选中外挂 SRT（不依赖名字是否带「，SRT」）。
     * 通过反射拿到 ExoPlayer，避免引擎类名变更。
     */
    @SuppressWarnings("unchecked")
    private static boolean forceSelectExternalViaMedia3(PlayerManager player) {
        try {
            if (player == null) return false;
            if (SOFT_RESTORE_ONLY) {
                // 软恢复：只按名字 setTrack，不做 TrackSelectionOverride
                String remembered = !TextUtils.isEmpty(sPendingSelectName)
                        ? sPendingSelectName
                        : loadRememberedTrackName(sLastHistory, sLastEpisode);
                if (selectExternalFromCurrentTracks(player, remembered == null ? "" : remembered)) {
                    sForceSettled = true;
                    sLastForceOkAt = System.currentTimeMillis();
                    Log.i(TAG, "softSelect OK name=" + remembered);
                    return true;
                }
                return false;
            }
            Object engine = null;
            try {
                java.lang.reflect.Field f = player.getClass().getDeclaredField("engine");
                f.setAccessible(true);
                engine = f.get(player);
            } catch (Throwable ignored) {
            }
            if (engine == null) return false;
            Object exo = null;
            for (String mName : new String[]{"getPlayer", "getExoPlayer"}) {
                try {
                    java.lang.reflect.Method mth = engine.getClass().getMethod(mName);
                    exo = mth.invoke(engine);
                    if (exo != null) break;
                } catch (Throwable ignored) {
                }
            }
            if (exo == null) {
                try {
                    java.lang.reflect.Field f = engine.getClass().getDeclaredField("player");
                    f.setAccessible(true);
                    exo = f.get(engine);
                } catch (Throwable ignored) {
                }
            }
            if (!(exo instanceof androidx.media3.common.Player)) return false;
            androidx.media3.common.Player pl = (androidx.media3.common.Player) exo;
            Tracks tracks = pl.getCurrentTracks();
            if (tracks == null || tracks.isEmpty()) return false;

            String remembered = !TextUtils.isEmpty(sPendingSelectName)
                    ? sPendingSelectName
                    : loadRememberedTrackName(sLastHistory, sLastEpisode);
            boolean wantExternal = sPreferExternal || loadCachedSubPayload(sLastHistory, sLastEpisode) != null;

            Tracks.Group bestGroup = null;
            int bestIndex = -1;
            int bestScore = -1;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                if (!group.isSupported()) continue;
                for (int i = 0; i < group.length; i++) {
                    if (!group.isTrackSupported(i)) continue;
                    Format f = group.getTrackFormat(i);
                    int score = scoreByRemembered(f, remembered, wantExternal);
                    if (score > bestScore) {
                        bestScore = score;
                        bestGroup = group;
                        bestIndex = i;
                    }
                }
            }
            // 有外挂偏好但名字没对上：再扫一遍只认外挂 mime
            if ((bestGroup == null || bestIndex < 0 || bestScore < 15) && wantExternal) {
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_TEXT || !group.isSupported()) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSupported(i)) continue;
                        Format f = group.getTrackFormat(i);
                        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType.toLowerCase(Locale.ROOT);
                        String lab = f.label == null ? "" : f.label.toLowerCase(Locale.ROOT);
                        int sc = 0;
                        if (mime.contains("subrip") || mime.contains("ssa") || mime.contains("ass")
                                || mime.contains("vtt") || mime.contains("ttml") || mime.startsWith("text/")) sc = 50;
                        if (lab.contains("srt") || lab.contains("ass") || lab.contains("vtt") || lab.contains("外挂")) sc = Math.max(sc, 45);
                        if (sc > bestScore) { bestScore = sc; bestGroup = group; bestIndex = i; }
                    }
                }
            }
            if (bestGroup == null || bestIndex < 0 || bestScore < 15) {
                Log.i(TAG, "forceSelect skip score=" + bestScore + " wantExt=" + wantExternal + " name=" + remembered);
                return false;
            }
            // 已是目标轨则不重复 set
            try {
                if (bestGroup.isTrackSelected(bestIndex)) {
                    Log.i(TAG, "forceSelect already selected");
                    sForceSettled = true;
                    sLastForceOkAt = System.currentTimeMillis();
                    return true;
                }
            } catch (Throwable ignored) {
            }
            androidx.media3.common.TrackSelectionOverride override =
                    new androidx.media3.common.TrackSelectionOverride(bestGroup.getMediaTrackGroup(), bestIndex);
            androidx.media3.common.TrackSelectionParameters params = pl.getTrackSelectionParameters()
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(override)
                    .build();
            pl.setTrackSelectionParameters(params);
            Format chosen = bestGroup.getTrackFormat(bestIndex);
            String label = chosen.label != null ? chosen.label : String.valueOf(chosen.id);
            persistTextTrackSelection(player, !TextUtils.isEmpty(remembered) ? remembered : label, chosen.sampleMimeType);
            Log.i(TAG, "forceSelect OK score=" + bestScore + " label=" + label + " mime=" + chosen.sampleMimeType);
            sForceSettled = true;
            sLastForceOkAt = System.currentTimeMillis();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "forceSelect failed: " + e.getMessage());
            return false;
        }
    }

    /** 名字优先；有外挂记忆时才用 mime 识别外挂轨（有内嵌时也能选中外挂） */
    private static int scoreByRemembered(Format f, String remembered, boolean wantExternal) {
        if (f == null) return -1;
        int score = 0;
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType.toLowerCase(Locale.ROOT);
        String label = f.label == null ? "" : f.label;
        String id = f.id == null ? "" : String.valueOf(f.id);
        String lang = f.language == null ? "" : f.language.toLowerCase(Locale.ROOT);
        boolean isExternalMime = mime.contains("subrip") || mime.contains("ssa") || mime.contains("ass")
                || mime.contains("vtt") || mime.contains("ttml") || mime.startsWith("text/")
                || mime.contains("application/x-subrip");
        // 内嵌常见：application/x-media-player-subtitles / image-based / 无 text
        boolean likelyEmbedded = mime.contains("vobsub") || mime.contains("pgs") || mime.contains("dvb")
                || mime.contains("image") || "application/x-media-player-subtitles".equals(mime);
        if (wantExternal) {
            if (isExternalMime) score += 60;
            if (likelyEmbedded) score -= 40;
            // Media3 外挂常无 language 或 label 带文件名
            if (label.toLowerCase(Locale.ROOT).contains(".srt")
                    || label.toLowerCase(Locale.ROOT).contains(".ass")
                    || label.toLowerCase(Locale.ROOT).contains(".ssa")
                    || label.toLowerCase(Locale.ROOT).contains(".vtt")
                    || label.contains("外挂")) score += 25;
        }
        if (!TextUtils.isEmpty(remembered)) {
            String r = remembered.trim().toLowerCase(Locale.ROOT);
            String l = label.toLowerCase(Locale.ROOT);
            if (l.equals(r) || id.equalsIgnoreCase(remembered.trim())) score += 100;
            else if (l.contains(r) || r.contains(l)) score += 70;
            else {
                // 去掉「，SRT」等后缀再比
                String rBase = r.replaceAll("[，,].*$", "").replaceAll("\\.(srt|ass|ssa|vtt|sub)$", "").trim();
                String lBase = l.replaceAll("[，,].*$", "").replaceAll("\\.(srt|ass|ssa|vtt|sub)$", "").trim();
                if (!rBase.isEmpty() && (lBase.contains(rBase) || rBase.contains(lBase))) score += 55;
            }
        }
        // 有外挂意图时，只要是 text 轨就给保底分，避免阈值匹配失败完全不选
        if (wantExternal && isExternalMime && score < 30) score = 30;
        return score;
    }


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
                    int score = scoreByRemembered(f, display, sPreferExternal || loadCachedSubPayload(sLastHistory, sLastEpisode) != null);
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

    /** 只按「上次记住的名字」打分，不按 SRT/PGS 类型强选 */
    private static int scoreExternalFormat(Format f, String remembered) {
        if (f == null) return -1;
        String id = f.id == null ? "" : f.id;
        String label = f.label == null ? "" : f.label;
        String mime = f.sampleMimeType == null ? "" : f.sampleMimeType;
        if (TextUtils.isEmpty(remembered)) {
            // 无记忆时不抢选
            return -1;
        }
        String r = remembered.trim();
        String rBase = r;
        int c = Math.max(r.indexOf('，'), r.indexOf(','));
        if (c > 0) rBase = r.substring(0, c).trim();
        int s = 0;
        if (label.equals(r) || id.equals(r)) s += 200;
        else if (label.equals(rBase) || id.equals(rBase)) s += 180;
        else if (label.contains(r) || r.contains(label) && !TextUtils.isEmpty(label)) s += 120;
        else if (label.contains(rBase) || rBase.contains(label) && label.length() >= 2) s += 100;
        else if (id.toLowerCase(Locale.ROOT).contains(rBase.toLowerCase(Locale.ROOT))) s += 60;
        else return -1; // 对不上名字就不选这条
        // 轻微参考 mime（仅同分参考，不压倒名字）
        String rl = r.toLowerCase(Locale.ROOT);
        String ml = mime.toLowerCase(Locale.ROOT);
        if (rl.contains("srt") && ml.contains("subrip")) s += 5;
        if (rl.contains("pgs") && ml.contains("pgs")) s += 5;
        if (rl.contains("ass") && (ml.contains("ssa") || ml.contains("ass"))) s += 5;
        return s;
    }



    /** 用户在字幕列表点选某轨时调用，只记名字，下次按名恢复 */
    public static void rememberChosenTrack(PlayerManager player, Track item) {
        try {
            if (item == null || item.getType() != C.TRACK_TYPE_TEXT) return;
            if (item.isDisabled()) {
                sPendingSelectName = "";
                sPendingSelectFormat = "";
                return;
            }
            String name = item.getName();
            if (TextUtils.isEmpty(name)) return;
            sPendingSelectName = name;
            sPendingSelectFormat = item.getFormat() == null ? "" : item.getFormat();
            String fmt = sPendingSelectFormat.toLowerCase(Locale.ROOT);
            String nl = name.toLowerCase(Locale.ROOT);
            sPreferExternal = fmt.contains("subrip") || fmt.contains("vtt") || fmt.contains("ssa")
                    || fmt.contains("ttml") || fmt.contains("text/")
                    || nl.contains("srt") || nl.contains("vtt") || nl.contains("ass")
                    || nl.contains("外挂");
            if (item.isDisabled()) sPreferExternal = false;
            // 写入与 apply 相同的多键缓存：无文件时只记名字，恢复靠 setTrack/Override
            try {
                if (player != null && !TextUtils.isEmpty(player.getKey())) {
                    Track t = new Track(C.TRACK_TYPE_TEXT, name, item.getFormat() == null ? "" : item.getFormat());
                    t.setKey(player.getKey());
                    t.setSelected(true);
                    t.save();
                }
            } catch (Throwable ignored) {
            }
            // 也写入 Prefers 名字，历史重进 attach 后可按名选
            try {
                Prefers.put("ext_sub_name_" + (sLastHistory != null ? Util.md5(sLastHistory.getKey()) : "x"), name);
                if (sLastHistory != null) {
                    for (String key : subCacheKeys(sLastHistory, sLastEpisode)) {
                        // 若已有文件缓存则保留文件；额外记名字键
                        Prefers.put(key + "_name", name);
                    }
                }
            } catch (Throwable ignored) {
            }
            persistChosenNameOnly(name, item.getFormat() == null ? "" : item.getFormat());
            Log.i(TAG, "rememberChosenTrack " + name);
        } catch (Throwable ignored) {
        }
    }

    public static String loadRememberedTrackName(History history, Episode episode) {
        try {
            if (!TextUtils.isEmpty(sPendingSelectName)) return sPendingSelectName;
            if (history != null) {
                for (String key : subCacheKeys(history, episode)) {
                    String n = Prefers.getString(key + "_name");
                    if (!TextUtils.isEmpty(n)) return n;
                }
                String n2 = Prefers.getString("ext_sub_name_" + Util.md5(history.getKey()));
                if (!TextUtils.isEmpty(n2)) return n2;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }


    /** 轨道列表变化时调用（有内嵌+外挂时外挂常晚到，需多次尝试） */
    public static void onTracksReady(PlayerManager player) {
        // 自动恢复已关闭
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


    private static List<String> buildQueriesFromKeyword(String keyword) {
        List<String> qs = new ArrayList<>();
        if (!TextUtils.isEmpty(keyword)) qs.add(keyword.trim());
        String cleaned = keyword == null ? "" : keyword.trim();
        cleaned = cleaned.replaceAll("(?i)[\\s\\-_]*第?[0-9一二三四五六七八九十百]+[集期话].*$", "").trim();
        cleaned = cleaned.replaceAll("(?i)[\\s\\-_]*S\\d{1,2}E\\d{1,3}.*$", "").trim();
        if (!TextUtils.isEmpty(cleaned) && !cleaned.equals(keyword == null ? "" : keyword.trim())) qs.add(cleaned);
        return qs;
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
        String epUrl = "";
        String vodName = "";
        try {
            if (episode != null && episode.getName() != null) epName = episode.getName().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (episode != null && episode.getUrl() != null) epUrl = episode.getUrl().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (history != null && history.getVodRemarks() != null) remarks = history.getVodRemarks().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (history != null && history.getEpisodeUrl() != null && history.getEpisodeUrl().trim().length() > 0)
                epUrl = history.getEpisodeUrl().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (history != null && history.getVodName() != null) vodName = history.getVodName().trim();
        } catch (Throwable ignored) {
        }
        if (!epName.isEmpty()) keys.add(subCacheKey(hk, epName));
        if (!remarks.isEmpty()) keys.add(subCacheKey(hk, remarks));
        if (!epUrl.isEmpty()) keys.add(subCacheKey(hk, epUrl));
        if (!vodName.isEmpty() && !epName.isEmpty()) keys.add(subCacheKey("vod:" + vodName, epName));
        keys.add(subCacheKey(hk, "")); // 仅按片
        keys.add(subCacheKey(history != null ? String.valueOf(history.getKey()) : "", epName));
        return new java.util.ArrayList<>(keys);
    }

    private static void putCommit(String key, String value) {
        try {
            Prefers.getPrefers().edit().putString(key, value == null ? "" : value).commit();
        } catch (Throwable e) {
            Prefers.put(key, value);
        }
    }

    /** 复制到 filesDir/sub_remember，避免清缓存丢外挂文件 */
    private static File durableCopy(File src) {
        try {
            if (src == null || !src.isFile()) return src;
            File dir = new File(com.github.catvod.Init.context().getFilesDir(), "sub_remember");
            if (!dir.exists() && !dir.mkdirs()) return src;
            String dstName = Util.md5(src.getAbsolutePath()) + "_" + src.getName();
            File dst = new File(dir, dstName);
            if (dst.isFile() && dst.length() == src.length()) return dst;
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst.isFile() ? dst : src;
        } catch (Throwable e) {
            return src;
        }
    }

    public static void rememberSub(History history, Episode episode, File file, String name, String lang, String format) {
        try {
            if (file == null || !file.isFile()) return;
            if (history == null) history = sLastHistory;
            if (episode == null) episode = sLastEpisode;
            File durable = durableCopy(file);
            if (durable == null || !durable.isFile()) durable = file;
            String payload = durable.getAbsolutePath() + "\u0001"
                    + (name == null ? "" : name) + "\u0001"
                    + (lang == null ? "" : lang) + "\u0001"
                    + (format == null ? "" : format);
            for (String key : subCacheKeys(history, episode)) {
                putCommit(key, payload);
                if (!TextUtils.isEmpty(name)) putCommit(key + "_name", name);
            }
            if (!TextUtils.isEmpty(name)) {
                sPendingSelectName = name.contains("，") || name.contains(",") ? name : trackLabelFor(name, format, durable.getName());
                sPendingSelectFormat = format;
            }
            sPreferExternal = true;
            try {
                if (history != null) {
                    com.fongmi.android.tv.bean.Sub sub = com.fongmi.android.tv.bean.Sub.create(
                            sPendingSelectName, durable.getAbsolutePath(), lang == null ? "" : lang, format == null ? "" : format);
                }
            } catch (Throwable ignored) {}
            Log.i(TAG, "remember sub keys=" + subCacheKeys(history, episode).size() + " file=" + durable.getAbsolutePath() + " name=" + name);
        } catch (Throwable e) {
            Log.w(TAG, "rememberSub failed: " + e.getMessage());
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
        // 自动恢复已关闭
    }

    public static boolean tryRestoreSub(Activity activity, History history, Episode episode, PlayerProvider playerProvider) {
        return false; // 自动恢复已关闭
    }

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
    /** 仅记名字（用户在轨列表点选外挂时） */
    public static void persistChosenNameOnly(String name, String format) {
        try {
            if (TextUtils.isEmpty(name)) return;
            sPendingSelectName = name;
            sPendingSelectFormat = format == null ? "" : format;
            String fmt = sPendingSelectFormat.toLowerCase(Locale.ROOT);
            String nl = name.toLowerCase(Locale.ROOT);
            sPreferExternal = fmt.contains("subrip") || fmt.contains("vtt") || fmt.contains("ssa")
                    || fmt.contains("ttml") || fmt.contains("text/")
                    || nl.contains("srt") || nl.contains("vtt") || nl.contains("ass")
                    || nl.contains("外挂");
            sForceSettled = false;
            persistTextTrackSelection(null, name, format);
            if (sLastHistory != null) {
                for (String key : subCacheKeys(sLastHistory, sLastEpisode)) {
                    putCommit(key + "_name", name);
                }
            }
            Log.i(TAG, "persistChosenNameOnly " + name);
        } catch (Throwable e) {
            Log.w(TAG, "persistChosenNameOnly: " + e.getMessage());
        }
    }

}
