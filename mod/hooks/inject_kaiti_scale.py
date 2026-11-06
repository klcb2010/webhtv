#!/usr/bin/env python3
"""KaiTi scale on setPlayerView + reapplySubtitleStyle. No logcat spam (use ModDebugLog if enabled)."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []

INLINE_APPLY = r'''
            // KAITI_SCALE_INLINE
            try {
                float __base = 0.0533f;
                float __scale = 1.0f;
                try {
                    float v = com.fongmi.android.tv.setting.PlayerSetting.getSubtitleTextSize();
                    if (v > 0f) __base = v;
                } catch (Throwable ignored) {}
                try {
                    __scale = com.fongmi.android.tv.setting.Setting.getEffectiveSubtitleFontScale();
                } catch (Throwable ignored) {}
                float __size = __base * __scale;
                if (__size < 0.02f) __size = 0.02f;
                if (__size > 0.15f) __size = 0.15f;
                __SV__.setFractionalTextSize(__size);
                try {
                    /* log silenced */} catch (Throwable ignored) {}
            } catch (Throwable ignored) {
            }
'''


def patch_exo_util(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    patterns = [
        (
            r"if\s*\(\s*PlayerSetting\.getSubtitleTextSize\(\)\s*!=\s*0\s*\)\s*"
            r"view\.getSubtitleView\(\)\.setFractionalTextSize\(\s*PlayerSetting\.getSubtitleTextSize\(\)\s*\)\s*;",
            INLINE_APPLY.replace("__SV__", "view.getSubtitleView()"),
        ),
        (
            r"view\.getSubtitleView\(\)\.setFractionalTextSize\(\s*PlayerSetting\.getSubtitleTextSize\(\)\s*\)\s*;",
            INLINE_APPLY.replace("__SV__", "view.getSubtitleView()"),
        ),
    ]
    replaced = False
    for pat, repl in patterns:
        if re.search(pat, t):
            t = re.sub(pat, repl, t, count=1)
            replaced = True
            print("[mod] setPlayerView kaitiApply", path)
            break
    if not replaced and "KAITI_SCALE_INLINE" not in t:
        m = re.search(
            r"(public static void setPlayerView\(\s*PlayerView view\s*\)\s*\{)([\s\S]*?)(\n    \})",
            t,
        )
        if m:
            body = m.group(2) + INLINE_APPLY.replace("__SV__", "view.getSubtitleView()")
            t = t[: m.start()] + m.group(1) + body + m.group(3) + t[m.end() :]
            print("[mod] setPlayerView append", path)

    m = re.search(r"public static void reapplySubtitleStyle\(\)\s*\{[\s\S]*?\n    \}", t)
    if m:
        new_reapply = """public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) return;
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
""" + INLINE_APPLY.replace("__SV__", "view.getSubtitleView()") + """
        } catch (Throwable ignored) {
        }
    }"""
        t = t[: m.start()] + new_reapply + t[m.end() :]
        print("[mod] reapplySubtitleStyle", path)

    path.write_text(t, encoding="utf-8")
    t2 = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_INLINE" not in t2:
        ERRORS.append(str(path))
        print("[mod] VERIFY FAIL", path)
    else:
        print("[mod] VERIFY OK", path)


def main() -> None:
    print("[mod] inject_kaiti_scale ROOT", ROOT)
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ExoUtil.java")
    if ERRORS:
        sys.exit(1)
    print("[mod] inject_kaiti_scale SUCCESS")


if __name__ == "__main__":
    main()
