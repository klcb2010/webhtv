package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
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
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只截视频 surface，不含状态栏/控制栏。
 * <p>
 * 不用 Window 合成截图。
 * TV：与 20261028-1930 完全相同 —— 长边 ≤1920，失败再 1280。
 * 手机：优先 surface 最高显示分辨率（上限 4096）。
 */
public final class ScreenshotHelper {

    /** TV：与 20261028-1930 版一致 */
    private static final int TV_MAX_EDGE = 1920;
    /** 手机：尽量用 surface 原始显示分辨率 */
    private static final int PHONE_MAX_EDGE = 4096;

    public interface Callback {
        void onSuccess(Uri uri);

        void onError(String message);
    }

    private ScreenshotHelper() {
    }

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        // 未显式指定时：runtime 探测（可能不准）
        capture(activity, playerView, isTelevision(activity), callback);
    }

    /**
     * @param forceTv true=TV 1930 策略（由 leanback VideoActivity 传入）；false=手机最高分辨率
     */
    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, boolean forceTv, @Nullable Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (activity == null || activity.isFinishing() || playerView == null) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }
        View surfaceView = playerView.getVideoSurfaceView();
        if (surfaceView == null) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }
        final boolean tv = forceTv;
        surfaceView.post(() -> captureView(activity, surfaceView, tv, main, callback));
    }

    private static boolean isTelevision(Context context) {
        try {
            UiModeManager ui = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
            if (ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature("android.software.leanback")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void captureView(Activity activity, View view, boolean tv, Handler main, Callback callback) {
        if (view instanceof TextureView textureView) {
            try {
                Bitmap raw = textureView.getBitmap();
                if (raw != null && raw.getWidth() > 0 && !raw.isRecycled()) {
                    Bitmap out = maybeDownscale(raw, tv);
                    if (out != raw) {
                        try {
                            raw.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (isMostlyBlack(out) || isLikelyGarbage(out)) {
                        try {
                            out.recycle();
                        } catch (Throwable ignored) {
                        }
                        fail(main, callback, R.string.screenshot_fail);
                        return;
                    }
                    saveAsync(activity, out, callback);
                    return;
                }
            } catch (Throwable ignored) {
            }
            fail(main, callback, R.string.screenshot_fail);
            return;
        }

        if (!(view instanceof SurfaceView surfaceView) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        SurfaceHolder holder = surfaceView.getHolder();
        Surface surface = holder != null ? holder.getSurface() : null;
        if (surface == null || !surface.isValid()) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        Rect frame = holder.getSurfaceFrame();
        int fw = frame != null ? Math.max(frame.width(), 0) : 0;
        int fh = frame != null ? Math.max(frame.height(), 0) : 0;
        int vw = surfaceView.getWidth();
        int vh = surfaceView.getHeight();
        int srcW = fw > 0 ? fw : vw;
        int srcH = fh > 0 ? fh : vh;
        if (srcW <= 0 || srcH <= 0) {
            fail(main, callback, R.string.screenshot_unavailable);
            return;
        }

        int[][] tries = buildTrySizes(srcW, srcH, tv);
        tryPixelCopyChain(activity, surfaceView, surface, tries, 0, main, callback);
    }

    private static int[][] buildTrySizes(int srcW, int srcH, boolean tv) {
        // TV：完全沿用 1930 —— 长边 ≤1920，失败再 1280
        if (tv) {
            int[] full = fitSize(srcW, srcH, TV_MAX_EDGE);
            int[] half = fitSize(srcW, srcH, Math.min(TV_MAX_EDGE, 1280));
            if (full[0] == half[0] && full[1] == half[1]) {
                return new int[][]{full};
            }
            return new int[][]{full, half};
        }
        // 手机：优先最高显示分辨率
        int[] full = fitSize(srcW, srcH, PHONE_MAX_EDGE);
        int[] mid = fitSize(srcW, srcH, 2560);
        int[] half = fitSize(srcW, srcH, 1920);
        ArrayList<int[]> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int[] s : new int[][]{full, mid, half}) {
            String k = s[0] + "x" + s[1];
            if (seen.add(k)) list.add(s);
        }
        return list.toArray(new int[0][]);
    }

    private static void tryPixelCopyChain(
            Activity activity,
            SurfaceView surfaceView,
            Surface surface,
            int[][] sizes,
            int index,
            Handler main,
            Callback callback) {
        if (index >= sizes.length) {
            fail(main, callback, R.string.screenshot_fail);
            return;
        }
        int dw = sizes[index][0];
        int dh = sizes[index][1];
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        } catch (Throwable e) {
            tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
            return;
        }

        AtomicBoolean done = new AtomicBoolean(false);
        PixelCopy.OnPixelCopyFinishedListener listener = copyResult -> {
            if (!done.compareAndSet(false, true)) return;
            if (copyResult != PixelCopy.SUCCESS
                    || isLikelyGarbage(bitmap)
                    || isMostlyBlack(bitmap)) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
                return;
            }
            saveAsync(activity, bitmap, callback);
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PixelCopy.request(surface, bitmap, listener, main);
            } else {
                PixelCopy.request(surfaceView, bitmap, listener, main);
            }
        } catch (Throwable e) {
            try {
                PixelCopy.request(surfaceView, bitmap, listener, main);
            } catch (Throwable e2) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
                tryPixelCopyChain(activity, surfaceView, surface, sizes, index + 1, main, callback);
            }
        }
    }

    private static int[] fitSize(int w, int h, int maxEdge) {
        if (w <= 0 || h <= 0) return new int[]{1, 1};
        int max = Math.max(w, h);
        if (max <= maxEdge) return new int[]{w, h};
        float scale = maxEdge / (float) max;
        return new int[]{Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
    }

    private static Bitmap maybeDownscale(Bitmap src, boolean tv) {
        int cap = tv ? TV_MAX_EDGE : PHONE_MAX_EDGE;
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= cap) return src;
        int[] s = fitSize(src.getWidth(), src.getHeight(), cap);
        try {
            return Bitmap.createScaledBitmap(src, s[0], s[1], true);
        } catch (Throwable e) {
            return src;
        }
    }

    private static boolean isMostlyBlack(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 4 || h < 4) return true;
            int[] xs = {w / 5, w / 2, 4 * w / 5};
            int[] ys = {h / 5, h / 2, 4 * h / 5};
            int dark = 0, total = 0;
            for (int y : ys) {
                for (int x : xs) {
                    int c = bitmap.getPixel(x, y);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (r < 18 && g < 18 && b < 18) dark++;
                }
            }
            return total > 0 && dark * 3 >= total * 2;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isLikelyGarbage(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        try {
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w < 8 || h < 8) return true;
            int[] xs = {w / 4, w / 2, 3 * w / 4};
            int[] ys = {h / 4, h / 2, 3 * h / 4};
            int greenish = 0, total = 0;
            for (int y : ys) {
                for (int x : xs) {
                    int c = bitmap.getPixel(x, y);
                    int r = (c >> 16) & 0xff;
                    int g = (c >> 8) & 0xff;
                    int b = c & 0xff;
                    total++;
                    if (g > 180 && g > r + 40 && g > b + 40) greenish++;
                }
            }
            return total > 0 && greenish * 2 >= total;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void saveAsync(Activity activity, Bitmap bitmap, @Nullable Callback callback) {
        Task.execute(() -> {
            Uri uri = null;
            String err = null;
            try {
                uri = savePng(activity, bitmap);
            } catch (Throwable e) {
                err = e.getMessage();
            } finally {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
            }
            Uri finalUri = uri;
            String finalErr = err;
            App.post(() -> {
                if (callback == null) return;
                if (finalUri != null) callback.onSuccess(finalUri);
                else callback.onError(finalErr == null || finalErr.isEmpty() ? msg(R.string.screenshot_fail) : finalErr);
            });
        });
    }

    private static Uri savePng(Activity activity, Bitmap bitmap) throws Exception {
        String name = "WEBHTV_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WEBHTV");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("insert failed");
        try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("openOutputStream failed");
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IllegalStateException("compress failed");
            }
            os.flush();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            activity.getContentResolver().update(uri, values, null, null);
        }
        return uri;
    }

    private static void fail(Handler main, Callback callback, int res) {
        main.post(() -> {
            if (callback != null) callback.onError(msg(res));
        });
    }

    private static String msg(int res) {
        try {
            return App.get().getString(res);
        } catch (Throwable e) {
            return "error";
        }
    }
}
