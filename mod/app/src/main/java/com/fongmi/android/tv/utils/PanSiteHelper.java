package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.Locale;

/** Heuristic: treat site/result as cloud-drive (网盘) for sort / direct-play prefer. */
public final class PanSiteHelper {

    private static final String[] KEYWORDS = {
            "网盘", "云盘", "阿里", "夸克", "百度", "迅雷", "uc盘", "uc网盘",
            "天翼", "115", "123盘", "123云", "pikpak", "alist", "openlist",
            "pan", "alipan", "aliyun", "quark", "baidu", "xunlei", "thunder"
    };

    private PanSiteHelper() {}

    public static boolean isPanSite(Site site) {
        if (site == null) return false;
        return isPanText(site.getName()) || isPanText(site.getKey()) || isPanText(site.getApi());
    }

    public static boolean isPanVod(Vod vod) {
        if (vod == null) return false;
        if (isPanSite(vod.getSite())) return true;
        return isPanText(vod.getSiteName()) || isPanText(vod.getSiteKey());
    }

    public static boolean isPanCollect(Collect collect) {
        if (collect == null || collect.getSite() == null) return false;
        return isPanSite(collect.getSite());
    }

    public static boolean isPanText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String k : KEYWORDS) {
            if (t.contains(k.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
