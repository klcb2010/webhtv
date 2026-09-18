package com.fongmi.android.tv.player.exo.ass;

import android.view.Surface;

/** Loaded only by the ASS worker after eligible, selected subtitle data arrives. */
final class AssNative {
    private static boolean loaded;

    static synchronized void ensureLoaded() {
        if (loaded) return;
        System.loadLibrary("exo_ass");
        loaded = true;
    }

    static native long create(String fontConfig, String[] fontNames, byte[][] fonts);
    static native boolean load(long handle, byte[] utf8);
    static native boolean loadHeader(long handle, byte[] utf8);
    static native boolean chunk(long handle, byte[] utf8, long startMs, long durationMs);
    static native boolean setSurface(long handle, Surface surface);
    static native int render(long handle, long timeMs, int width, int height,
                             int storageWidth, int storageHeight, double pixelAspect,
                             int colorSpace, int colorRange, boolean force, long[] stats);
    static native void destroy(long handle);
    static native boolean testSurface(long handle, int width, int height);
    static native long createTestFonts(String[] names, byte[][] fonts);
    static native byte[] readPixels(long handle);

    private AssNative() { }
}
