package androidx.media3.mpvplayer;
public class MpvPlayer {
    private static final Object NATIVE_CONTEXT_LOCK = new Object();
    private boolean initialized;
    private boolean released;
    private Object nativeContextOwner;
    private void ensureInitialized() {
        mpvSetOptionString("hwdec", "auto");
        // MPV_PHASE_A_OPTIONS
        try { setOption("vo", "gpu"); } catch (Throwable ignored) {}
        // MPV_PHASE_C_INIT_OPTIONS_V8
        try { modSetInitOption("slang", "zh-Hans,zh-CN,chs,sc,zh,zh-Hant,zh-TW,cht"); } catch (Throwable ignored) {}
        mpvInit();
        initialized = true;
        applyPostInitOptions();
        // MPV_PHASE_C_POST_INIT_STYLE_V8
        try {
            modRegisterActive(this);
            modApplyUserSubtitleStyleFromPolicy();
        } catch (Throwable ignored) {}

    }
    private void mpvInit() {}
    private void applyPostInitOptions() {}
    private void setOption(String a, String b) {}
    private int mpvSetOptionString(String a, String b) { return 0; }
    private int mpvSetPropertyString(String a, String b) { return 0; }
    protected Object handleRelease() {
        // MPV_MOD_HANDLE_RELEASE_V8: unregister at handleRelease entry (compareAndSet); before native teardown
        try { modUnregisterActive(this); } catch (Throwable ignored) {}

        released = true;
        nativeContextOwner = null;
                try { // MPV_MOD_OWNER_CLEAR_V8
                    modUnregisterActive(this);
                } catch (Throwable ignored) {}
        return null;
    }

    // MPV_MOD_PUBLIC_BRIDGE_V8_BEGIN
    private static final java.util.concurrent.atomic.AtomicReference<MpvPlayer> sModActive =
            new java.util.concurrent.atomic.AtomicReference<>();

    public static MpvPlayer modGetActivePlayer() {
        return sModActive.get();
    }

    public static void modRegisterActive(MpvPlayer player) {
        if (player != null) sModActive.set(player);
    }

    /** 仅当引用仍是该实例时清空，避免误清新实例。 */
    public static void modUnregisterActive(MpvPlayer player) {
        if (player == null) return;
        sModActive.compareAndSet(player, null);
    }

    public boolean modIsNativeReady() {
        try {
            if (!initialized) return false;
            if (released) return false;
            if (nativeContextOwner == null) return false;
            if (nativeContextOwner != this) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 初始化选项：仅 mpvSetOptionString，检查返回码。
     * 负数失败；非负成功。无 setOption 假成功回退。
     */
    public boolean modSetInitOption(String name, String value) {
        if (name == null || value == null) return false;
        try {
            int code = mpvSetOptionString(name, value);
            return code >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 运行时属性：在 NATIVE_CONTEXT_LOCK 内二次确认状态后再写。
     */
    public boolean modSetRuntimeProperty(String name, String value) {
        if (name == null || value == null) return false;
        try {
            synchronized (NATIVE_CONTEXT_LOCK) {
                if (!initialized) return false;
                if (released) return false;
                if (nativeContextOwner == null) return false;
                if (nativeContextOwner != this) return false;
                int code = mpvSetPropertyString(name, value);
                return code >= 0;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean modApplySubtitleStyleRuntime(String fontsDir, String font, String forceStyle, String color) {
        if (!modIsNativeReady()) return false;
        boolean ok = true;
        if (fontsDir != null && !fontsDir.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-fonts-dir", fontsDir);
        }
        if (font != null && !font.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-font", font);
        }
        if (forceStyle != null && !forceStyle.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-ass-force-style", forceStyle);
        }
        if (color != null && !color.isEmpty()) {
            ok &= modSetRuntimeProperty("sub-color", color);
        }
        ok &= modSetRuntimeProperty("sub-ass-override", "force");
        return ok;
    }

    public void modNotifySurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        modSetRuntimeProperty("android-surface-size", width + "x" + height);
    }

    public boolean modApplyUserSubtitleStyleFromPolicy() {
        try {
            Class<?> pol = Class.forName("com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy");
            pol.getMethod("ensureFontsReady").invoke(null);
            Object dir = pol.getMethod("getSubFontsDirProperty").invoke(null);
            Object font = pol.getMethod("getSubFontProperty").invoke(null);
            Object style = pol.getMethod("getAssForceStyle").invoke(null);
            Object color = pol.getMethod("getSubColorProperty").invoke(null);
            return modApplySubtitleStyleRuntime(
                    dir instanceof String ? (String) dir : null,
                    font instanceof String ? (String) font : null,
                    style instanceof String ? (String) style : null,
                    color instanceof String ? (String) color : null);
        } catch (Throwable t) {
            return false;
        }
    }
    // MPV_MOD_PUBLIC_BRIDGE_V8_END

}
