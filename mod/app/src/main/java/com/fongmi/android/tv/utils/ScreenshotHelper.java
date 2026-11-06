package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 只截 PlayerView 视频 surface（SurfaceView / TextureView），不含控制栏 UI。
 * 不改动渲染模式或解码链路。
 */
public final class ScreenshotHelper {

    public interface Callback {
        void onSuccess(Uri uri);
        void onError(String message);
    }

    private ScreenshotHelper() {}

    public static void capture(@Nullable Activity activity, @Nullable PlayerView playerView, @Nullable Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        if (activity == null || activity.isFinishing() || playerView == null) {
            main.post(() -> {
                if (callback != null) callback.onError(msg(R.string.screenshot_unavailable));
            });
            return;
        }
        View surface = playerView.getVideoSurfaceView();
        if (surface instanceof TextureView textureView) {
            Bitmap bitmap = textureView.getBitmap();
            if (bitmap == null) {
                main.post(() -> {
                    if (callback != null) callback.onError(msg(R.string.screenshot_unavailable));
                });
                return;
            }
            saveAsync(activity, bitmap, callback);
            return;
        }
        if (surface instanceof SurfaceView surfaceView) {
            int w = surfaceView.getWidth();
            int h = surfaceView.getHeight();
            if (w <= 0 || h <= 0) {
                main.post(() -> {
                    if (callback != null) callback.onError(msg(R.string.screenshot_unavailable));
                });
                return;
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(surfaceView, bitmap, copyResult -> {
                    if (copyResult != PixelCopy.SUCCESS) {
                        bitmap.recycle();
                        main.post(() -> {
                            if (callback != null) callback.onError(msg(R.string.screenshot_fail));
                        });
                        return;
                    }
                    saveAsync(activity, bitmap, callback);
                }, main);
            } catch (Throwable e) {
                try { bitmap.recycle(); } catch (Throwable ignored) {}
                main.post(() -> {
                    if (callback != null) callback.onError(msg(R.string.screenshot_fail));
                });
            }
            return;
        }
        main.post(() -> {
            if (callback != null) callback.onError(msg(R.string.screenshot_unavailable));
        });
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
                try { bitmap.recycle(); } catch (Throwable ignored) {}
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
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = activity.getContentResolver().insert(collection, values);
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

    private static String msg(int res) {
        try {
            return App.get().getString(res);
        } catch (Throwable e) {
            return "error";
        }
    }
}
