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
 * 截图入口（mobile / leanback 各一份，实现相同）。
 *
 * - MPV：走 {@link MpvScreenshot} 的 mpv screenshot-to-file video，真原画；失败回退 Surface。
 * - EXO：直接截当前 Surface（PixelCopy），分辨率=屏幕、颜色正常、毫秒级。
 *   V40 起已按用户要求砍掉 EXO 的「独立解码取原画」那套
 *   （HDR/10bit 源取出来是黑帧、4K 还要几十秒，收益为负）。
 * PNG / WebP；长边上限 3840。
 */
public final class PhoneScreenshot {

    private static final int MAX_EDGE = 3840;
    /** 临时诊断，定位完成后删除 */
    private static final String TAG = "PhoneScreenshot";
    /** 诊断日志跟随「播放器临时调试日志」开关（个性设置里可即时开关，不用重新打包）。 */
    private static boolean diag() {
        try {
            return com.fongmi.android.tv.utils.ModDebugLog.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }


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
        Runnable start = () -> {
            boolean mpv = false;
            try {
                mpv = com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                        == com.fongmi.android.tv.setting.PlayerSetting.MPV;
            } catch (Throwable ignored) {}
            if (mpv) {
                int mode;
                try { mode = com.fongmi.android.tv.setting.Setting.getMpvScreenshotMode(); }
                catch (Throwable e) { mode = com.fongmi.android.tv.setting.Setting.SCREENSHOT_PNG; }
                if (mode == com.fongmi.android.tv.setting.Setting.SCREENSHOT_OFF) {
                    fail(main, callback);
                    return;
                }
                // MPV：优先原画 screenshot；失败再 Surface
                Task.execute(() -> {
                    Bitmap frame = null;
                    try {
                        frame = com.fongmi.android.tv.utils.MpvScreenshot.captureVideoFrame(activity, playerView);
                    } catch (Throwable ignored) {}
                    if (frame != null) {
                        Bitmap out = scaleToMax(frame, MAX_EDGE);
                        if (out != frame) {
                            try { frame.recycle(); } catch (Throwable ignored) {}
                        }
                        saveAsync(activity, out, callback);
                        return;
                    }
                    main.post(() -> captureSurface(activity, playerView, main, callback));
                });
                return;
            }
            // EXO：双端都不再支持截图（只保留 MPV 原画截图），直接返回失败提示。
            main.post(() -> {
                if (callback != null) callback.onError("EXO 播放不支持截图（仅 MPV 支持）");
            });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) start.run();
        else main.post(start);
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
        if (diag()) try {
            com.fongmi.android.tv.utils.ModDebugLog.i(TAG, "surface surf=" + surfW + "x" + surfH
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
    
    /** 按个性设置「截图亮度提升比值」对 RGB 做加法提亮，关=0 不处理。 */
    private static Bitmap applyBrightnessBoost(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        int boost = 0;
        try {
            try {
                if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                        == com.fongmi.android.tv.setting.PlayerSetting.MPV) {
                    boost = com.fongmi.android.tv.setting.Setting.getMpvScreenshotBrightnessBoost();
                } else {
                    boost = com.fongmi.android.tv.setting.Setting.getScreenshotBrightnessBoost();
                }
            } catch (Throwable ignored2) {
                boost = com.fongmi.android.tv.setting.Setting.getScreenshotBrightnessBoost();
            }
        } catch (Throwable ignored) {
        }
        if (boost <= 0) return src;
        try {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                    1, 0, 0, 0, boost,
                    0, 1, 0, 0, boost,
                    0, 0, 1, 0, boost,
                    0, 0, 0, 1, 0
            });
            android.graphics.ColorMatrixColorFilter filter = new android.graphics.ColorMatrixColorFilter(cm);
            Bitmap out = src.copy(src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(filter);
            canvas.drawBitmap(src, 0, 0, paint);
            return out;
        } catch (Throwable ignored) {
            return src;
        }
    }

    /** 按个性设置「MPV截图对比度」做对比度拉伸（围绕 128 中点），关=0 不处理。 */
    private static Bitmap applyContrastBoost(Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        int level = 0;
        try {
            level = com.fongmi.android.tv.setting.Setting.getMpvScreenshotContrast();
        } catch (Throwable ignored) {
        }
        if (level <= 0) return src;
        try {
            float c = 1f + (level / 100f);          // +15 → 1.15
            float t = (1f - c) * 128f;              // 保持 128 中点不变
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0
            });
            android.graphics.ColorMatrixColorFilter filter = new android.graphics.ColorMatrixColorFilter(cm);
            Bitmap out = src.copy(src.getConfig() != null ? src.getConfig() : Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            paint.setColorFilter(filter);
            canvas.drawBitmap(src, 0, 0, paint);
            return out;
        } catch (Throwable ignored) {
            return src;
        }
    }

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
                Bitmap bright = applyBrightnessBoost(toSave);
                if (bright != toSave) {
                    try {
                        toSave.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = bright;
                }
                Bitmap contrast = applyContrastBoost(toSave);
                if (contrast != toSave) {
                    try {
                        toSave.recycle();
                    } catch (Throwable ignored) {
                    }
                    toSave = contrast;
                }
                if (diag()) try { com.fongmi.android.tv.utils.ModDebugLog.i(TAG, "savePng bitmap=" + toSave.getWidth() + "x" + toSave.getHeight()); } catch (Throwable ignored) {}
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
            if (com.fongmi.android.tv.setting.PlayerSetting.getPlayer()
                    == com.fongmi.android.tv.setting.PlayerSetting.MPV) {
                mode = com.fongmi.android.tv.setting.Setting.getMpvScreenshotMode();
            }
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

    /** 尽量选软件解码器，降低与播放器占用的硬解冲突（CRASH_NATIVE）。 */
    private static MediaCodec createSafeVideoDecoder(String mime) throws Exception {
        if (mime == null || mime.isEmpty()) throw new IllegalArgumentException("mime");
        try {
            android.media.MediaCodecList list = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            android.media.MediaCodecInfo soft = null;
            android.media.MediaCodecInfo any = null;
            for (android.media.MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                String[] types = info.getSupportedTypes();
                boolean match = false;
                if (types != null) {
                    for (String type : types) {
                        if (mime.equalsIgnoreCase(type)) { match = true; break; }
                    }
                }
                if (!match) continue;
                any = info;
                try {
                    // API 29+: isHardwareAccelerated；更早用名称启发式
                    boolean hw = true;
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        hw = info.isHardwareAccelerated();
                    } else {
                        String n = info.getName().toLowerCase(java.util.Locale.US);
                        hw = !(n.contains("android") || n.contains("google") || n.contains("c2.android"));
                    }
                    if (!hw) {
                        soft = info;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (soft != null) {
                return MediaCodec.createByCodecName(soft.getName());
            }
        } catch (Throwable ignored) {
        }
        return MediaCodec.createDecoderByType(mime);
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
