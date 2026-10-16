#!/usr/bin/env python3
"""
KaiTi scale: inline into ExoUtil.reapplySubtitleStyle (do not rely solely on Setting.apply*).
Uses PlayerSetting.subtitle_text_size × kaiti scale → setFractionalTextSize (same as SubtitleDialog).
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []

REAPPLY_METHOD = r'''public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) {
                android.util.Log.w("SubtitleStyle", "reapplySubtitleStyle: no PlayerView/SubtitleView");
                return;
            }
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            // KAITI_SCALE_INLINE — same unit as SubtitleDialog / PlayerSetting.subtitle_text_size
            float __base = 0.0533f;
            float __scale = 1.0f;
            try {
                float v = com.fongmi.android.tv.setting.PlayerSetting.getSubtitleTextSize();
                if (v > 0f) __base = v;
            } catch (Throwable t) {
                android.util.Log.w("SubtitleStyle", "kaiti read base: " + t.getMessage());
            }
            try {
                __scale = com.fongmi.android.tv.setting.Setting.getEffectiveSubtitleFontScale();
            } catch (Throwable t) {
                android.util.Log.w("SubtitleStyle", "kaiti read scale: " + t.getMessage());
            }
            float __size = __base * __scale;
            if (__size < 0.02f) __size = 0.02f;
            if (__size > 0.15f) __size = 0.15f;
            try {
                view.getSubtitleView().setFractionalTextSize(__size);
            } catch (Throwable t) {
                android.util.Log.w("SubtitleStyle", "kaiti setFractionalTextSize: " + t.getMessage());
            }
            try {
                com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(view.getSubtitleView());
            } catch (Throwable t) {
                android.util.Log.w("SubtitleStyle", "kaiti Setting.apply: " + t.getMessage());
            }
            android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok base=" + __base
                    + " scale=" + __scale + " size=" + __size
                    + " kaiti=" + (__scale != 1.0f));
        } catch (Throwable e) {
            android.util.Log.w("SubtitleStyle", "reapplySubtitleStyle: " + e.getMessage());
        }
    }'''


def patch_exo_util(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    m = re.search(r"public static void reapplySubtitleStyle\(\)\s*\{[\s\S]*?\n    \}", t)
    if not m:
        ERRORS.append(f"no reapplySubtitleStyle in {path}")
        print("[mod] FAIL no reapplySubtitleStyle", path)
        return
    t = t[: m.start()] + REAPPLY_METHOD + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    t2 = path.read_text(encoding="utf-8")
    for needle in ("KAITI_SCALE_INLINE", "setFractionalTextSize(__size)", "reapplySubtitleStyle ok base="):
        if needle not in t2:
            ERRORS.append(f"missing {needle} in {path}")
            print("[mod] VERIFY FAIL", needle, path)
            return
    print("[mod] VERIFY OK", path)


def patch_subtitle_dialog(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip SubtitleDialog")
        return
    t = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_DIALOG" in t:
        print("[mod] SubtitleDialog already")
        return
    hook = """
        // KAITI_SCALE_DIALOG
        try { com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle(); } catch (Throwable ignored) {}
"""
    n = 0
    for anchor in (
        "PlayerSetting.putSubtitleTextSize(subtitleView.getTextSize());",
        "subtitleView.reset();",
    ):
        if anchor in t:
            t = t.replace(anchor, anchor + hook, 1)
            n += 1
    if n:
        path.write_text(t, encoding="utf-8")
        print("[mod] SubtitleDialog hooked", n, path)
    else:
        print("[mod] WARN SubtitleDialog anchors missing")


def main() -> None:
    print("[mod] inject_kaiti_scale ROOT", ROOT)
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ExoUtil.java")
    patch_subtitle_dialog(ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/SubtitleDialog.java")
    if ERRORS:
        print("[mod] FAILED", ERRORS, file=sys.stderr)
        sys.exit(1)
    print("[mod] inject_kaiti_scale SUCCESS")


if __name__ == "__main__":
    main()
