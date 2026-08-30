package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.setting.Setting;

/**
 * 全部更新/下载资源走 GitHub Releases：
 * https://github.com/klcb2010/webhtv/releases
 *
 * 加速源开关开启时，下载类 URL 自动套上加速前缀；
 * API 类 URL（api.github.com）保持直连（多数加速代理不支持）。
 */
public class Github {

    private static final String OWNER_REPO = "klcb2010/webhtv";
    private static final String GITHUB_LATEST = "https://github.com/" + OWNER_REPO + "/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/" + OWNER_REPO + "/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases/assets";

    /** 下载类 URL：加速源开启时套前缀 */
    private static String accelerate(String url) {
        if (!Setting.getGithubProxyEnabled()) return url;
        String proxy = Setting.getGithubProxyUrl();
        if (TextUtils.isEmpty(proxy)) return url;
        if (!proxy.endsWith("/")) proxy += "/";
        return proxy + url;
    }

    /** 兼容旧调用名：实际也指向 GitHub latest/download */
    public static String getCnbAsset(String name) {
        return getGithubLatestAsset(name);
    }

    public static String getGithubLatestAsset(String name) {
        return accelerate(GITHUB_LATEST + "/" + name);
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return accelerate(GITHUB_RELEASE + "/" + tag + "/" + name);
    }

    public static String getJson(String name) {
        return getGithubLatestAsset(name + ".json");
    }

    public static String getJson(String name, String channel) {
        if ("beta".equals(channel)) return getGithubLatestAsset(name + "-beta.json");
        return getJson(name);
    }

    public static String getApk(String name) {
        return getGithubLatestAsset(name + ".apk");
    }

    public static String getApk(String name, String channel) {
        if ("beta".equals(channel)) return getGithubLatestAsset(name + "-beta.apk");
        return getApk(name);
    }

    public static String getAsset(String name, String channel) {
        return getGithubLatestAsset(name);
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
    }

    public static String getReleasesApi() {
        return GITHUB_RELEASES_API + "?per_page=20";
    }

    public static String getLatestReleaseApi() {
        return GITHUB_RELEASES_API + "/latest";
    }

    public static String getReleaseAssetApi(long id) {
        return GITHUB_RELEASE_ASSETS_API + "/" + id;
    }
}
