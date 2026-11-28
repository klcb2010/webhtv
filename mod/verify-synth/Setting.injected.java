package com.fongmi.android.tv.setting;
public class Setting {
    public static void putSubtitleFontIndex(int index) {
        // MPV_FONT_APPLY_AFTER_PUT_V11
        Prefers.put("subtitle_font_index", SubtitleFontManager.normalizeIndex(index));
        try {
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.ensureFontsReady();
            com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive();
        } catch (Throwable ignored) {}
    }
}
class Prefers { static void put(String k, int v) {} }
class SubtitleFontManager { static int normalizeIndex(int i) { return i; } }
