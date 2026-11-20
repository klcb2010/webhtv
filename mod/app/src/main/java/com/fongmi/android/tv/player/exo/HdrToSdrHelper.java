package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Effect;
import androidx.media3.effect.ScaleAndRotateTransformation;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.Setting;

import java.util.Collections;
import java.util.List;

/**
 * HDR→SDR（OpenGL tone mapping）辅助。
 * 默认关闭；开启后仅在 EXO 视频图路径上请求 Media3 官方 tone map。
 * 失败全部吞掉，避免影响正常播放。
 */
public final class HdrToSdrHelper {

    private HdrToSdrHelper() {
    }

    public static boolean isEnabled() {
        try {
            return Setting.isHdrToSdrEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 无 LUT 时用于拉起 PlaybackVideoGraph 的近乎恒等变换。 */
    public static List<Effect> identityEffects() {
        try {
            Effect e = new ScaleAndRotateTransformation.Builder().build();
            return Collections.singletonList(e);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    public static List<Effect> mergeEffects(List<Effect> existing) {
        if (!isEnabled()) return existing;
        if (existing != null && !existing.isEmpty()) return existing;
        return identityEffects();
    }

    /** 在 EXO 引擎建好或首帧前尝试启用 effects 路径（从而创建 VideoGraph）。 */
    public static void ensureEffectsPath(PlayerEngine engine) {
        if (!isEnabled() || engine == null) return;
        try {
            if (!engine.supportsVideoEffects()) return;
            engine.setVideoEffects(identityEffects());
        } catch (Throwable ignored) {
        }
    }
}
