package com.fongmi.android.tv.player;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoSizeProbe {

    public interface Callback {
        void onResult(String size);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final Pattern RANGE_TOTAL = Pattern.compile("/(\\d+)\\s*$");

    private VideoSizeProbe() {
    }

    public static void query(Context context, String source, Map<String, String> headers, Callback callback) {
        EXECUTOR.execute(() -> {
            Long bytes = resolve(context, source, headers);
            String result = bytes == null || bytes <= 0 ? null : format(bytes);
            if (callback != null) callback.onResult(result);
        });
    }

    private static Long resolve(Context context, String source, Map<String, String> headers) {
        if (source == null || source.trim().isEmpty()) return null;
        try {
            Uri uri = Uri.parse(source);
            String scheme = uri.getScheme();
            if ("file".equalsIgnoreCase(scheme)) {
                long length = new File(uri.getPath()).length();
                return length > 0 ? length : null;
            }
            if ("content".equalsIgnoreCase(scheme)) {
                try (android.content.res.AssetFileDescriptor descriptor =
                             context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (descriptor != null && descriptor.getLength() > 0) return descriptor.getLength();
                } catch (Exception ignored) {
                }
                return null;
            }
            String path = uri.getPath();
            if (path != null) {
                String lower = path.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".m3u8") || lower.endsWith(".m3u") || lower.endsWith(".mpd")) return null;
            }
            URL url = new URL(source);
            Long size = request(url, headers, "HEAD");
            if (size != null && size > 0) return size;
            return request(url, headers, "GET");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long request(URL url, Map<String, String> headers, String method) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if ("GET".equals(method)) connection.setRequestProperty("Range", "bytes=0-0");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null
                            && !entry.getKey().equalsIgnoreCase("Range")
                            && !entry.getKey().equalsIgnoreCase("Accept-Encoding")) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 400) return null;
            if (isPlaylist(connection.getContentType())) return null;
            String range = connection.getHeaderField("Content-Range");
            if (range != null) {
                Matcher matcher = RANGE_TOTAL.matcher(range);
                if (matcher.find()) return Long.parseLong(matcher.group(1));
            }
            if ("GET".equals(method) && status == HttpURLConnection.HTTP_PARTIAL) return null;
            String contentLength = connection.getHeaderField("Content-Length");
            if (contentLength == null) return null;
            long length = Long.parseLong(contentLength);
            return length > 0 ? length : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isPlaylist(String type) {
        if (type == null) return false;
        String value = type.toLowerCase(java.util.Locale.ROOT);
        return value.contains("mpegurl") || value.contains("dash+xml") || value.startsWith("text/html");
    }

    private static String format(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return new DecimalFormat("0.#").format(value) + " " + units[unit];
    }
}
