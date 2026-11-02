package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.lang.reflect.Method;

/** MPV 原画截图：screenshot-to-file video；失败返回 null。 */
public final class MpvScreenshot {
    private MpvScreenshot() {}

    @Nullable
    public static Bitmap captureVideoFrame(@Nullable Activity activity, @Nullable PlayerView playerView) {
        if (activity == null) return null;
        File out = null;
        try {
            out = new File(activity.getCacheDir(), "mpv_shot_" + System.currentTimeMillis() + ".png");
            if (out.exists()) out.delete();
            boolean ok = tryMpvLibCommand(new String[]{"screenshot-to-file", out.getAbsolutePath(), "video"});
            if (!ok) ok = tryMpvLibCommand(new String[]{"screenshot-to-file", out.getAbsolutePath()});
            if (!ok && playerView != null) ok = tryPlayerCommand(playerView, out.getAbsolutePath());
            if (!ok || !out.isFile() || out.length() < 32) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(out.getAbsolutePath(), opts);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (out != null) {
                try { out.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean tryMpvLibCommand(String[] cmd) {
        String[] classes = {
            "dev.jdtech.mpv.MPVLib",
            "is.xyz.mpv.MPVLib",
            "androidx.media3.mpvplayer.MPVLib",
        };
        for (String name : classes) {
            try {
                Class<?> cl = Class.forName(name);
                Method m = cl.getMethod("command", String[].class);
                m.invoke(null, (Object) cmd);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean tryPlayerCommand(PlayerView playerView, String path) {
        try {
            Method getPlayer = PlayerView.class.getMethod("getPlayer");
            Object player = getPlayer.invoke(playerView);
            if (player == null) return false;
            if (!player.getClass().getName().toLowerCase().contains("mpv")) return false;
            for (Method m : player.getClass().getMethods()) {
                if (!"command".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isArray()) {
                    m.invoke(player, (Object) new String[]{"screenshot-to-file", path, "video"});
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
