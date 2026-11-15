#!/usr/bin/env python3
"""Use MPV's GPU video output for SMB items so rotation metadata is applied."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
PLAYER_MANAGER = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"


def replace_once(text, old, new, label):
    count = text.count(old)
    if count == 0:
        raise SystemExit("[mod] ERROR: SMB MPV GPU anchor missing: " + label)
    if count > 1:
        raise SystemExit("[mod] ERROR: SMB MPV GPU anchor is ambiguous: " + label)
    return text.replace(old, new, 1)


def patch_player_manager():
    if not PLAYER_MANAGER.is_file():
        raise SystemExit("[mod] ERROR: PlayerManager.java not found")
    text = PLAYER_MANAGER.read_text(encoding="utf-8")
    if "MOD_SMB_MPV_GPU_OUTPUT" in text:
        print("[mod] SMB MPV GPU output already installed")
        return

    text = replace_once(
        text,
        "    private boolean mpvAutoGpuPinnedForSession;\n",
        "    private boolean mpvAutoGpuPinnedForSession;\n"
        "    // MOD_SMB_MPV_GPU_OUTPUT: one-shot request, scoped to SMB playback.\n"
        "    private boolean mpvForceGpuOutputForNextItem;\n"
        "    private boolean mpvForceGpuOutputForCurrentItem;\n",
        "PlayerManager fields",
    )

    method_anchor = """    public boolean isMpvSurfaceDirect() {
        return engine instanceof MpvPlayerEngine mpv && mpv.isSurfaceDirect();
    }"""
    method_addition = method_anchor + """

    // MOD_SMB_MPV_GPU_OUTPUT: avoid mediacodec_embed, which loses portrait rotation on some TVs.
    public void forceMpvGpuOutputForNextItem() {
        mpvForceGpuOutputForNextItem = true;
    }"""
    text = replace_once(text, method_anchor, method_addition, "one-shot public API")

    prepare_anchor = """        if (!(engine instanceof MpvPlayerEngine mpv)) return;
        mpv.prepareSubtitleForNewItem(persistedSubtitle);"""
    prepare_replacement = """        if (!(engine instanceof MpvPlayerEngine mpv)) return;
        mpvForceGpuOutputForCurrentItem = mpvForceGpuOutputForNextItem;
        mpvForceGpuOutputForNextItem = false;
        mpv.prepareSubtitleForNewItem(persistedSubtitle);"""
    text = replace_once(text, prepare_anchor, prepare_replacement, "new item scope")

    prepare_start = text.index("    private void prepareMpvOutputForNewItem()")
    prepare_end = text.index("    private void resetMpvOutputRuntime()", prepare_start)
    block = text[prepare_start:prepare_end]
    block = replace_once(
        block,
        "        mpv.setSurfaceDirectOverride(null);",
        "        mpv.setSurfaceDirectOverride(mpvForceGpuOutputForCurrentItem ? false : null);",
        "disable direct output",
    )
    block = replace_once(
        block,
        "        boolean shouldStartDirect = MpvPerformanceSetting.shouldUseSurfaceDirect(",
        "        boolean shouldStartDirect = !mpvForceGpuOutputForCurrentItem\n"
        "                && MpvPerformanceSetting.shouldUseSurfaceDirect(",
        "preserve normal auto-output policy",
    )
    text = text[:prepare_start] + block + text[prepare_end:]

    reset_anchor = """        mpvAutoGpuPinnedForSession = false;
        mpvAutoVulkanPinnedForItem = false;"""
    reset_replacement = """        mpvAutoGpuPinnedForSession = false;
        mpvForceGpuOutputForNextItem = false;
        mpvForceGpuOutputForCurrentItem = false;
        mpvAutoVulkanPinnedForItem = false;"""
    text = replace_once(text, reset_anchor, reset_replacement, "runtime reset")

    rebuild_anchor = "        Boolean effectiveSurfaceDirectOverride = surfaceDirectDirectOverride;"
    if rebuild_anchor in text:
        raise SystemExit("[mod] ERROR: unexpected surface override identifier")
    rebuild_anchor = "        Boolean effectiveSurfaceDirectOverride = surfaceDirectOverride;"
    rebuild_replacement = (
        "        Boolean effectiveSurfaceDirectOverride = mpvForceGpuOutputForCurrentItem\n"
        "                ? Boolean.FALSE : surfaceDirectOverride;"
    )
    text = replace_once(text, rebuild_anchor, rebuild_replacement, "MPV retry rebuild")

    fel_anchor = """        if (mpv.isDv7FelOutputEnabled()) {
            // Already selected by native before decoder initialization. A
            // second App-level transition would reopen the same media.
            mpvAutoOutputEvaluated = true;
            return true;
        }
        Tracks tracks = engine.getCurrentTracks();"""
    fel_replacement = """        if (mpv.isDv7FelOutputEnabled()) {
            // Already selected by native before decoder initialization. A
            // second App-level transition would reopen the same media.
            mpvAutoOutputEvaluated = true;
            return true;
        }
        if (mpvForceGpuOutputForCurrentItem) {
            mpvAutoOutputEvaluated = true;
            return true;
        }
        Tracks tracks = engine.getCurrentTracks();"""
    text = replace_once(text, fel_anchor, fel_replacement, "skip automatic direct-output probe")

    PLAYER_MANAGER.write_text(text, encoding="utf-8")
    print("[mod] SMB MPV portrait-safe GPU output injected")


def main():
    patch_player_manager()


if __name__ == "__main__":
    main()
