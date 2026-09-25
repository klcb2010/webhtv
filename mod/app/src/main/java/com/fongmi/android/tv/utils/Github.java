package com.fongmi.android.tv.utils;

/**
 * 全部更新/下载资源走 GitHub Releases：
 * https://github.com/klcb2010/webhtv/releases
 */
public class Github {

    private static final String OWNER_REPO = "klcb2010/webhtv";
    private static final String GITHUB_LATEST = "https://github.com/" + OWNER_REPO + "/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/" + OWNER_REPO + "/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/" + OWNER_REPO + "/releases/assets";

    /** 兼容旧调用名：实际也指向 GitHub latest/download */
    public static String getCnbAsset(String name) {
        return getGithubLatestAsset(name);
    }

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + tag + "/" + name;
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
