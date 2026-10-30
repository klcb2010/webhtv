package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅 mobile。截图时优先按当前进度「单独软解导出一帧」（MediaMetadataRetriever），
 * 不改动正在播放的解码器；失败再回退 Surface PixelCopy。
 * PNG；长边上限 3840。
 */
public final class PhoneScreenshot {

    private static final int MAX_EDGE = 3840;
    /** 临时诊断，定位完成后删除 */
    private static final String TAG = "PhoneScreenshot";
    private static final boolean DIAG = false;

    public interface Callback {
        void onSuccess(Uri uri);

        void onError(String message);
    }

    private PhoneScreenshot() {
    }

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (activity == null || activity.isFinishing() || playerView == null) {
            fail(main, callback);
            return;
        }
        Runnable startDecode = () -> {
            final Snapshot snap = snapshotPlayer(playerView);
            if (DIAG) try {
                Log.i(TAG, "snapshot uri=" + (snap == null ? "null" : String.valueOf(snap.uri))
                        + " posMs=" + (snap == null ? -1 : snap.posMs)
                        + " video=" + (snap == null ? "?" : (snap.videoW + "x" + snap.videoH)));
            } catch (Throwable ignored) {}
            Task.execute(() -> {
                Bitmap frame = null;
                try {
                    if (snap != null && snap.uri != null) {
                        frame = decodeFromSnapshot(snap);
                    }
                } catch (Throwable e) {
                    if (DIAG) try { Log.i(TAG, "decode err=" + e); } catch (Throwable ignored) {}
                }
                if (frame != null) {
                    if (DIAG) try { Log.i(TAG, "path=DECODE frame=" + frame.getWidth() + "x" + frame.getHeight()); } catch (Throwable ignored) {}
                    Bitmap out = scaleToMax(frame, MAX_EDGE);
                    if (out != frame) {
                        if (DIAG) try { Log.i(TAG, "after scaleToMax=" + out.getWidth() + "x" + out.getHeight()); } catch (Throwable ignored) {}
                        try { frame.recycle(); } catch (Throwable ignored) {}
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
                if (DIAG) try { Log.i(TAG, "path=SURFACE_FALLBACK decodeFrame=null"); } catch (Throwable ignored) {}
                main.post(() -> captureSurface(activity, playerView, main, callback));
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) startDecode.run();
        else main.post(startDecode);
    }

    private static final class Snapshot {
        final Uri uri;
        final long posMs;
        final Map<String, String> headers;
        final int videoW;
        final int videoH;

        Snapshot(Uri uri, long posMs, Map<String, String> headers, int videoW, int videoH) {
            this.uri = uri;
            this.posMs = posMs;
            this.headers = headers != null ? headers : Collections.emptyMap();
            this.videoW = videoW;
            this.videoH = videoH;
        }
    }

    /** 主线程调用：只在这里读 Player */
    @Nullable
    private static Snapshot snapshotPlayer(PlayerView playerView) {
        try {
            Player player = playerView.getPlayer();
            if (player == null) return null;
            MediaItem item = player.getCurrentMediaItem();
            if (item == null || item.localConfiguration == null) return null;
            Uri uri = item.localConfiguration.uri;
            long posMs = player.getCurrentPosition();
            Map<String, String> headers = extractHeaders(item);
            int vw = 0, vh = 0;
            try {
                VideoSize vs = player.getVideoSize();
                if (vs != null) {
                    vw = vs.width;
                    vh = vs.height;
                }
            } catch (Throwable ignored) {
            }
            return new Snapshot(uri, posMs, headers, vw, vh);
        } catch (Throwable e) {
            if (DIAG) try { Log.i(TAG, "snapshotPlayer err=" + e); } catch (Throwable ignored) {}
            return null;
        }
    }

    @Nullable
    private static Bitmap decodeFromSnapshot(Snapshot snap) {
        long posUs = Math.max(0L, snap.posMs) * 1000L;
        Bitmap b = tryRetrieverUri(snap.uri, snap.headers, posUs, snap.videoW, snap.videoH);
        if (b != null) return b;
        if (DIAG) try { Log.i(TAG, "retriever failed, trying MediaCodec uri=" + snap.uri); } catch (Throwable ignored) {}
        return tryMediaCodecFrame(snap.uri, snap.headers, posUs, snap.videoW, snap.videoH);
    }

    @Nullable
    private static Bitmap tryRetrieverUri(Uri uri, Map<String, String> headers, long posUs, int playW, int playH) {
        if (uri == null) return null;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if (DIAG) try { Log.i(TAG, "retriever scheme=" + scheme + " posUs=" + posUs); } catch (Throwable ignored) {}

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("file".equals(scheme) || "content".equals(scheme) || "android.resource".equals(scheme)) {
                retriever.setDataSource(App.get(), uri);
            } else if ("http".equals(scheme) || "https".equals(scheme)) {
                Map<String, String> hdr = headers == null ? Collections.emptyMap() : headers;
                retriever.setDataSource(uri.toString(), hdr);
            } else {
                if (DIAG) try { Log.i(TAG, "retriever skip scheme=" + scheme); } catch (Throwable ignored) {}
                return null;
            }

            int metaW = 0, metaH = 0;
            try {
                String ws = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (ws != null && hs != null) {
                    metaW = Integer.parseInt(ws);
                    metaH = Integer.parseInt(hs);
                }
            } catch (Throwable ignored) {
            }
            int dstW = 0, dstH = 0;
            if (metaW > 0 && metaH > 0 && playW > 0 && playH > 0) {
                if ((long) metaW * metaH >= (long) playW * playH) {
                    dstW = metaW;
                    dstH = metaH;
                } else {
                    dstW = playW;
                    dstH = playH;
                }
            } else if (metaW > 0 && metaH > 0) {
                dstW = metaW;
                dstH = metaH;
            } else if (playW > 0 && playH > 0) {
                dstW = playW;
                dstH = playH;
            }
            if (dstW > 0 && dstH > 0) {
                int[] fitted = fit(dstW, dstH, MAX_EDGE);
                dstW = fitted[0];
                dstH = fitted[1];
            }
            if (DIAG) try {
                Log.i(TAG, "meta=" + metaW + "x" + metaH + " playVideoSize=" + playW + "x" + playH + " dst=" + dstW + "x" + dstH);
            } catch (Throwable ignored) {}

            Bitmap bitmap = null;
            if (dstW > 0 && dstH > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    bitmap = retriever.getScaledFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH);
                    if (DIAG) try { Log.i(TAG, "getScaledFrameAtTime CLOSEST " + (bitmap == null ? "null" : (bitmap.getWidth() + "x" + bitmap.getHeight()))); } catch (Throwable ignored) {}
                } catch (Throwable e) {
                    if (DIAG) try { Log.i(TAG, "getScaledFrameAtTime CLOSEST err=" + e); } catch (Throwable ignored) {}
                }
                if (bitmap == null) {
                    try {
                        bitmap = retriever.getScaledFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH);
                        if (DIAG) try { Log.i(TAG, "getScaledFrameAtTime CLOSEST_SYNC " + (bitmap == null ? "null" : (bitmap.getWidth() + "x" + bitmap.getHeight()))); } catch (Throwable ignored) {}
                    } catch (Throwable e) {
                        if (DIAG) try { Log.i(TAG, "getScaledFrameAtTime CLOSEST_SYNC err=" + e); } catch (Throwable ignored) {}
                    }
                }
            }
            if (bitmap == null) {
                try {
                    bitmap = retriever.getFrameAtTime(posUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (DIAG) try { Log.i(TAG, "getFrameAtTime " + (bitmap == null ? "null" : (bitmap.getWidth() + "x" + bitmap.getHeight()))); } catch (Throwable ignored) {}
                } catch (Throwable e) {
                    if (DIAG) try { Log.i(TAG, "getFrameAtTime err=" + e); } catch (Throwable ignored) {}
                }
            }
            return bitmap;
        } catch (Throwable e) {
            if (DIAG) try { Log.i(TAG, "retriever exception: " + e); } catch (Throwable ignored) {}
            return null;
        } finally {
            try { retriever.release(); } catch (Throwable ignored) {}
        }
    }

    private static void captureSurface(Activity activity, PlayerView playerView, Handler main, Callback callback) {
        View surfaceView = playerView.getVideoSurfaceView();
        if (surfaceView == null) {
            fail(main, callback);
            return;
        }
        surfaceView.post(() -> doCaptureSurface(activity, playerView, surfaceView, main, callback));
    }

    private static void doCaptureSurface(Activity activity, PlayerView playerView, View view, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0) {
                    Bitmap out = scaleDownOnly(raw, raw.getWidth(), raw.getHeight(), resolveVideoSize(playerView));
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
            } catch (Throwable ignored) {
            }
            fail(main, callback);
            return;
        }

        if (!(view instanceof SurfaceView surfaceView) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            fail(main, callback);
            return;
        }

        SurfaceHolder holder = surfaceView.getHolder();
        Surface surface = holder != null ? holder.getSurface() : null;
        if (surface == null || !surface.isValid()) {
            fail(main, callback);
            return;
        }

        Rect frame = holder.getSurfaceFrame();
        int fw = frame != null ? Math.max(frame.width(), 0) : 0;
        int fh = frame != null ? Math.max(frame.height(), 0) : 0;
        int vw = Math.max(surfaceView.getWidth(), 1);
        int vh = Math.max(surfaceView.getHeight(), 1);
        int surfW = fw > 0 ? fw : vw;
        int surfH = fh > 0 ? fh : vh;

        int[] video = resolveVideoSize(playerView);
        int[] target = pickTargetSize(surfW, surfH, video);
        int srcW = target[0];
        int srcH = target[1];

        // 固定最高可用分辨率，不因失败降档重试
        int[] full = fit(srcW, srcH, MAX_EDGE);
        if (DIAG) try {
            Log.i(TAG, "surface surf=" + surfW + "x" + surfH
                    + " video=" + (video != null ? video[0] + "x" + video[1] : "null")
                    + " target=" + srcW + "x" + srcH
                    + " pixelCopy=" + full[0] + "x" + full[1]);
        } catch (Throwable ignored) {}
        tryCopyOnce(activity, surfaceView, surface, full[0], full[1], main, callback);
    }

    private static void tryCopyOnce(
            Activity activity,
            SurfaceView surfaceView,
            Surface surface,
            int dw,
            int dh,
            Handler main,
            Callback callback) {
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            fail(main, callback);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        PixelCopy.OnPixelCopyFinishedListener listener = result -> {
            if (!done.compareAndSet(false, true)) return;
            if (result != PixelCopy.SUCCESS) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                fail(main, callback);
                return;
            }
            // 不做黑屏/绿屏检测，避免正常画面被误判
            saveAsync(activity, bitmap, callback);
        };

        try {
            PixelCopy.request(surface, bitmap, listener, main);
        } catch (Throwable e) {
            try {
                PixelCopy.request(surfaceView, bitmap, listener, main);
            } catch (Throwable e2) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                fail(main, callback);
            }
        }
    }

    private static int[] pickTargetSize(int surfW, int surfH, int[] video) {
        int sw = Math.max(1, surfW);
        int sh = Math.max(1, surfH);
        if (video != null && video.length >= 2 && video[0] > 0 && video[1] > 0) {
            if (video[0] <= sw && video[1] <= sh) {
                return fit(video[0], video[1], MAX_EDGE);
            }
        }
        return fit(sw, sh, MAX_EDGE);
    }

    private static Bitmap scaleDownOnly(Bitmap raw, int surfW, int surfH, int[] video) {
        int[] target = pickTargetSize(Math.max(surfW, raw.getWidth()), Math.max(surfH, raw.getHeight()), video);
        int tw = target[0];
        int th = target[1];
        if (raw.getWidth() <= tw && raw.getHeight() <= th) {
            return scaleToMax(raw, MAX_EDGE);
        }
        try {
            return Bitmap.createScaledBitmap(raw, tw, th, true);
        } catch (Throwable e) {
            return scaleToMax(raw, MAX_EDGE);
        }
    }

    private static int[] resolveVideoSize(PlayerView playerView) {
        try {
            if (playerView == null) return new int[]{0, 0};
            Player player = playerView.getPlayer();
            if (player == null) return new int[]{0, 0};
            VideoSize vs = player.getVideoSize();
            if (vs != null && vs.width > 0 && vs.height > 0) {
                return new int[]{vs.width, vs.height};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{0, 0};
    }

    private static int[] fit(int w, int h, int maxEdge) {
        int max = Math.max(w, h);
        if (max <= maxEdge) return new int[]{Math.max(1, w), Math.max(1, h)};
        float s = maxEdge / (float) max;
        return new int[]{Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))};
    }

    private static Bitmap scaleToMax(Bitmap src, int maxEdge) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= maxEdge) return src;
        int[] s = fit(src.getWidth(), src.getHeight(), maxEdge);
        try {
            return Bitmap.createScaledBitmap(src, s[0], s[1], true);
        } catch (Throwable e) {
            return src;
        }
    }


    /**
     * 视频帧常见 limited range (16–235) 被当 full range 会发灰。
     * 检测后拉伸到 0–255，并略提对比，仅用于截图落盘。
     */

    /**
     * 轻度反锐化蒙版：中心略抬，邻域微减，再与原图混合，避免过锐。
     */
    private static Bitmap mildSharpen(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 3 || h < 3) return src;
            Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] srcPx = new int[w * h];
            int[] dstPx = new int[w * h];
            src.getPixels(srcPx, 0, w, 0, 0, w, h);
            System.arraycopy(srcPx, 0, dstPx, 0, srcPx.length);
            for (int y = 1; y < h - 1; y++) {
                int row = y * w;
                for (int x = 1; x < w - 1; x++) {
                    int i = row + x;
                    int c0 = srcPx[i];
                    int a = (c0 >>> 24) & 0xff;
                    int r = 0, g = 0, b = 0;
                    int[] offs = {i - w, i - 1, i, i + 1, i + w};
                    int[] coef = {-1, -1, 5, -1, -1};
                    for (int k = 0; k < 5; k++) {
                        int c = srcPx[offs[k]];
                        int m = coef[k];
                        r += ((c >> 16) & 0xff) * m;
                        g += ((c >> 8) & 0xff) * m;
                        b += (c & 0xff) * m;
                    }
                    int or = (c0 >> 16) & 0xff;
                    int og = (c0 >> 8) & 0xff;
                    int ob = c0 & 0xff;
                    r = clamp((r * 35 + or * 65) / 100);
                    g = clamp((g * 35 + og * 65) / 100);
                    b = clamp((b * 35 + ob * 65) / 100);
                    dstPx[i] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            out.setPixels(dstPx, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static Bitmap enhanceVideoLevels(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            if (w < 2 || h < 2) return src;
            int minL = 255, maxL = 0, samples = 0;
            long sum = 0;
            for (int yi = 1; yi <= 6; yi++) {
                for (int xi = 1; xi <= 6; xi++) {
                    int c = src.getPixel(w * xi / 7, h * yi / 7);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    int y = (r * 3 + g * 6 + b) / 10;
                    if (y < minL) minL = y;
                    if (y > maxL) maxL = y;
                    sum += y;
                    samples++;
                }
            }
            if (samples == 0) return src;
            int avg = (int) (sum / samples);
            boolean limitedLike = (minL >= 8 && minL <= 40 && maxL <= 245 && maxL >= 180)
                    || (minL >= 12 && maxL - minL < 200 && avg >= 80 && avg <= 180);
            if (!limitedLike && maxL - minL > 40) {
                return applyContrast(src, 1.06f, -6);
            }

            Bitmap out = src.isMutable()
                    ? src
                    : src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int[] pixels = new int[w * h];
            out.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = (c >>> 24) & 0xff;
                int r = contrast1(expandSample((c >> 16) & 0xff));
                int g = contrast1(expandSample((c >> 8) & 0xff));
                int b = contrast1(expandSample(c & 0xff));
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static int expandSample(int v) {
        int x = (v - 16) * 255 / 219;
        if (x < 0) return 0;
        if (x > 255) return 255;
        return x;
    }

    private static int contrast1(int v) {
        int x = (int) ((v - 128) * 1.08f + 128);
        if (x < 0) return 0;
        if (x > 255) return 255;
        return x;
    }

    private static Bitmap applyContrast(Bitmap src, float factor, int brightness) {
        try {
            Bitmap out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            int w = out.getWidth();
            int h = out.getHeight();
            int[] pixels = new int[w * h];
            out.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int i = 0; i < pixels.length; i++) {
                int c = pixels[i];
                int a = (c >>> 24) & 0xff;
                int r = clamp((int) (((c >> 16) & 0xff) * factor + brightness));
                int g = clamp((int) (((c >> 8) & 0xff) * factor + brightness));
                int b = clamp((int) ((c & 0xff) * factor + brightness));
                pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable e) {
            return src;
        }
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }


    private static void saveAsync(Activity activity, Bitmap bitmap, @Nullable Callback callback) {
        Task.execute(() -> {
            Uri uri = null;
            String err = null;
            Bitmap toSave = bitmap;
            try {
                // Surface 回退路径也可能是 limited range，统一拉一遍电平
                Bitmap enhanced = enhanceVideoLevels(bitmap);
                if (enhanced != bitmap) {
                    try {
                        bitmap.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = enhanced;
                }
                Bitmap sharp = mildSharpen(toSave);
                if (sharp != toSave) {
                    try {
                        toSave.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = sharp;
                }
                if (DIAG) try { Log.i(TAG, "savePng bitmap=" + toSave.getWidth() + "x" + toSave.getHeight()); } catch (Throwable ignored) {}
                uri = savePng(activity, toSave);
            } catch (Throwable e) {
                err = e.getMessage();
            } finally {
                try {
                    toSave.recycle();
                } catch (Throwable ignored) {
                }
            }
            Uri finalUri = uri;
            String finalErr = err;
            App.post(() -> {
                if (callback == null) return;
                if (finalUri != null) callback.onSuccess(finalUri);
                else callback.onError(finalErr == null || finalErr.isEmpty() ? msgFail() : finalErr);
            });
        });
    }

    private static Uri savePng(Activity activity, Bitmap bitmap) throws Exception {
        // 名称保留；实际格式由设置决定
        return saveImage(activity, bitmap);
    }

    private static Uri saveImage(Activity activity, Bitmap bitmap) throws Exception {
        int mode = com.fongmi.android.tv.setting.Setting.SCREENSHOT_PNG;
        try {
            mode = com.fongmi.android.tv.setting.Setting.getPlaybackScreenshotMode();
        } catch (Throwable ignored) {
        }
        boolean webp = mode == com.fongmi.android.tv.setting.Setting.SCREENSHOT_WEBP
                || mode == com.fongmi.android.tv.setting.Setting.SCREENSHOT_WEBP98;
        boolean webp98 = mode == com.fongmi.android.tv.setting.Setting.SCREENSHOT_WEBP98;
        String ext = webp ? ".webp" : ".png";
        String mime = webp ? "image/webp" : "image/png";
        String name = "WEBHTV_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ext;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WEBHTV");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("insert failed");
        try {
            try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("openOutputStream failed");
                boolean ok;
                if (webp98) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 98, os);
                    } else {
                        ok = bitmap.compress(Bitmap.CompressFormat.WEBP, 98, os);
                    }
                } else if (webp) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ok = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, os);
                    } else {
                        ok = bitmap.compress(Bitmap.CompressFormat.WEBP, 100, os);
                    }
                } else {
                    ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                if (!ok) throw new IllegalStateException("compress failed");
                os.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(uri, done, null, null);
            }
            return uri;
        } catch (Throwable e) {
            try {
                activity.getContentResolver().delete(uri, null, null);
            } catch (Throwable ignored) {
            }
            if (e instanceof Exception) throw (Exception) e;
            throw new Exception(e);
        }
    }

    private static void fail(Handler main, Callback callback) {
        main.post(() -> {
            if (callback != null) callback.onError(msgFail());
        });
    }

    private static String msgFail() {
        try {
            return App.get().getString(R.string.screenshot_fail);
        } catch (Throwable e) {
            return "error";
        }
    }

    private static Map<String, String> extractHeaders(MediaItem item) {
        Map<String, String> headers = new HashMap<>();
        try {
            Object conf = item.localConfiguration;
            for (String fieldName : new String[]{"httpRequestHeaders", "requestHeaders", "headers"}) {
                try {
                    java.lang.reflect.Field f = conf.getClass().getField(fieldName);
                    Object v = f.get(conf);
                    if (v instanceof Map) {
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                            }
                        }
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return headers;
    }

    @Nullable
    private static Bitmap tryMediaCodecFrame(Uri uri, Map<String, String> headers, long posUs, int playW, int playH) {
        if (uri == null) return null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        ImageReader reader = null;
        try {
            extractor = new MediaExtractor();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            Map<String, String> hdr = headers == null ? Collections.emptyMap() : headers;
            if ("file".equals(scheme) || "content".equals(scheme)) {
                extractor.setDataSource(App.get(), uri, hdr.isEmpty() ? null : hdr);
            } else if ("http".equals(scheme) || "https".equals(scheme)) {
                extractor.setDataSource(uri.toString(), hdr);
            } else {
                if (DIAG) try { Log.i(TAG, "codec skip scheme=" + scheme); } catch (Throwable ignored) {}
                return null;
            }

            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = f;
                    break;
                }
            }
            if (videoTrack < 0 || format == null) {
                if (DIAG) try { Log.i(TAG, "codec no video track"); } catch (Throwable ignored) {}
                return null;
            }
            extractor.selectTrack(videoTrack);
            try {
                extractor.seekTo(posUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } catch (Throwable ignored) {
            }

            int width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            int height = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            if (width <= 0 || height <= 0) {
                width = playW;
                height = playH;
            }
            if (width <= 0 || height <= 0) {
                if (DIAG) try { Log.i(TAG, "codec unknown size"); } catch (Throwable ignored) {}
                return null;
            }
            int[] fitted = fit(width, height, MAX_EDGE);
            int outW = fitted[0];
            int outH = fitted[1];
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) return null;
            if (DIAG) try {
                Log.i(TAG, "codec track=" + width + "x" + height + " out=" + outW + "x" + outH + " mime=" + mime);
            } catch (Throwable ignored) {}

            reader = ImageReader.newInstance(outW, outH, ImageFormat.YUV_420_888, 2);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, reader.getSurface(), null, 0);
            codec.start();

            boolean inputDone = false;
            boolean gotFrame = false;
            long deadline = System.nanoTime() + 8_000_000_000L;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Bitmap result = null;
            int framesPastTarget = 0;

            while (!gotFrame && System.nanoTime() < deadline) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        if (inBuf == null) break;
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long sampleTime = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0);
                            extractor.advance();
                            if (sampleTime >= posUs) {
                                framesPastTarget++;
                                if (framesPastTarget > 12) {
                                    // 再喂几帧后结束输入，促使输出
                                    continue;
                                }
                            }
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    boolean render = info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    // 只在达到目标时间附近才取图
                    boolean near = info.presentationTimeUs + 500000 >= posUs || posUs < 1000000;
                    codec.releaseOutputBuffer(outIndex, render && near);
                    if (render && near) {
                        Image image = null;
                        try {
                            for (int w = 0; w < 50 && image == null; w++) {
                                image = reader.acquireLatestImage();
                                if (image == null) {
                                    try { Thread.sleep(15); } catch (InterruptedException ignored) {}
                                }
                            }
                            if (image != null) {
                                result = yuv420ToBitmap(image);
                                if (DIAG) try {
                                    Log.i(TAG, "codec frame=" + (result == null ? "null" : (result.getWidth() + "x" + result.getHeight())));
                                } catch (Throwable ignored) {}
                                if (result != null) gotFrame = true;
                            }
                        } finally {
                            if (image != null) {
                                try { image.close(); } catch (Throwable ignored) {}
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            return result;
        } catch (Throwable e) {
            if (DIAG) try { Log.i(TAG, "codec error: " + e); } catch (Throwable ignored) {}
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Throwable ignored) {}
            try { if (extractor != null) extractor.release(); } catch (Throwable ignored) {}
            try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        }
    }

    private static Bitmap yuv420ToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return null;
        java.nio.ByteBuffer yBuf = planes[0].getBuffer();
        java.nio.ByteBuffer uBuf = planes[1].getBuffer();
        java.nio.ByteBuffer vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride();
        int yPix = planes[0].getPixelStride();
        int uRow = planes[1].getRowStride();
        int uPix = planes[1].getPixelStride();
        int vRow = planes[2].getRowStride();
        int vPix = planes[2].getPixelStride();
        int[] argb = new int[width * height];
        byte[] yBytes = new byte[yBuf.remaining()];
        byte[] uBytes = new byte[uBuf.remaining()];
        byte[] vBytes = new byte[vBuf.remaining()];
        yBuf.get(yBytes);
        uBuf.get(uBytes);
        vBuf.get(vBytes);
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int yIndex = j * yRow + i * yPix;
                int uIndex = (j >> 1) * uRow + (i >> 1) * uPix;
                int vIndex = (j >> 1) * vRow + (i >> 1) * vPix;
                int y = (yIndex < yBytes.length ? yBytes[yIndex] : 0) & 0xff;
                int u = (uIndex < uBytes.length ? uBytes[uIndex] : 128) & 0xff;
                int v = (vIndex < vBytes.length ? vBytes[vIndex] : 128) & 0xff;
                y = y - 16;
                if (y < 0) y = 0;
                int c = 298 * y;
                int d = u - 128;
                int e = v - 128;
                int r = (c + 409 * e + 128) >> 8;
                int g = (c - 100 * d - 208 * e + 128) >> 8;
                int b = (c + 516 * d + 128) >> 8;
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;
                argb[j * width + i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.setPixels(argb, 0, width, 0, 0, width, height);
        return bmp;
    }


}
