package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

/**
 * 版本比较：支持 5.6.0-202608300937 这种「版本号-时间戳」标签，
 * 避免仅靠字符串相等导致新构建被当成「已是最新」。
 */
public final class AppVersion {

    private AppVersion() {
    }

    public static String fullName() {
        String tag = BuildConfig.BUILD_TAG == null ? "" : BuildConfig.BUILD_TAG.trim();
        if (tag.isEmpty()) tag = BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILD_TIME;
        return stripPrefix(tag);
    }

    public static boolean isCurrent(String name) {
        return stripPrefix(name).equalsIgnoreCase(stripPrefix(fullName()));
    }

    /** remote 是否比 local 新 */
    public static boolean isNewer(String remote, String local) {
        return compare(remote, local) > 0;
    }

    public static boolean isNewerThanCurrent(String remote) {
        return isNewer(remote, fullName());
    }

    /**
     * 分段比较：数字段按数值，其它按忽略大小写字符串。
     * 例：5.6.0-202608300937 > 5.6.0-202608300531
     */
    public static int compare(String left, String right) {
        String a = stripPrefix(left);
        String b = stripPrefix(right);
        if (a.equalsIgnoreCase(b)) return 0;
        String[] as = a.split("[^0-9A-Za-z]+");
        String[] bs = b.split("[^0-9A-Za-z]+");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String x = i < as.length ? as[i] : "";
            String y = i < bs.length ? bs[i] : "";
            if (x.isEmpty() && y.isEmpty()) continue;
            boolean nx = x.matches("\\d+");
            boolean ny = y.matches("\\d+");
            if (nx && ny) {
                try {
                    int c = Long.compare(Long.parseLong(x), Long.parseLong(y));
                    if (c != 0) return c;
                } catch (NumberFormatException e) {
                    int c = x.compareToIgnoreCase(y);
                    if (c != 0) return c;
                }
            } else {
                int c = x.compareToIgnoreCase(y);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    public static String stripPrefix(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") && value.length() > 1 && Character.isDigit(value.charAt(1))
                ? value.substring(1) : value;
    }
}
