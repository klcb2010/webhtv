#!/usr/bin/env python3
"""
KaiTi auto scale — apply on playback setup (setPlayerView) + reapplySubtitleStyle.
Silent: runs when player view is bound / style reapplied, no extra UI.
"""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []

# Shared snippet: compute size and setFractionalTextSize on subtitleView variable
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
                android.util.Log.i("SubtitleStyle", "kaitiApply base=" + __base
                        + " scale=" + __scale + " size=" + __size);
            } catch (Throwable t) {
                android.util.Log.w("SubtitleStyle", "kaitiApply: " + t.getMessage());
            }
'''


def patch_exo_util(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    # 1) setPlayerView: replace stock text-size line(s) with always-scaled apply
    # Upstream:
    #   if (PlayerSetting.getSubtitleTextSize() != 0) view.getSubtitleView().setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
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
    replaced_spv = False
    for pat, repl in patterns:
        if re.search(pat, t):
            t = re.sub(pat, repl, t, count=1)
            replaced_spv = True
            print("[mod] setPlayerView text-size → kaitiApply", path)
            break

    # If no text-size line, append apply at end of setPlayerView before closing brace
    if not replaced_spv and "KAITI_SCALE_INLINE" not in t:
        m = re.search(
            r"(public static void setPlayerView\(\s*PlayerView view\s*\)\s*\{)([\s\S]*?)(\n    \})",
            t,
        )
        if m:
            body = m.group(2)
            if "KAITI_SCALE_INLINE" not in body:
                body = body + INLINE_APPLY.replace("__SV__", "view.getSubtitleView()")
                t = t[: m.start()] + m.group(1) + body + m.group(3) + t[m.end() :]
                replaced_spv = True
                print("[mod] setPlayerView append kaitiApply", path)
        else:
            print("[mod] WARN setPlayerView not found", path)

    # 2) reapplySubtitleStyle full replace
    m = re.search(r"public static void reapplySubtitleStyle\(\)\s*\{[\s\S]*?\n    \}", t)
    if m:
        new_reapply = """public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) {
                android.util.Log.w("SubtitleStyle", "reapplySubtitleStyle: no view");
                return;
            }
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
""" + INLINE_APPLY.replace("__SV__", "view.getSubtitleView()") + """
            android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok");
        } catch (Throwable e) {
            android.util.Log.w("SubtitleStyle", "reapplySubtitleStyle: " + e.getMessage());
        }
    }"""
        t = t[: m.start()] + new_reapply + t[m.end() :]
        print("[mod] reapplySubtitleStyle replaced", path)
    else:
        # Ensure method exists after setPlayerView
        if "reapplySubtitleStyle" not in t:
            print("[mod] WARN no reapplySubtitleStyle", path)

    if t != orig:
        path.write_text(t, encoding="utf-8")

    t2 = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_INLINE" not in t2 or "kaitiApply base=" not in t2:
        ERRORS.append(f"verify fail {path}")
        print("[mod] VERIFY FAIL", path)
    else:
        print("[mod] VERIFY OK", path)


def patch_subtitle_dialog(path: Path) -> None:
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_DIALOG" in t:
        print("[mod] SubtitleDialog already")
        return
    hook = "\n        // KAITI_SCALE_DIALOG\n        try { com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle(); } catch (Throwable ignored) {}\n"
    n = 0
    for a in (
        "PlayerSetting.putSubtitleTextSize(subtitleView.getTextSize());",
        "subtitleView.reset();",
    ):
        if a in t:
            t = t.replace(a, a + hook, 1)
            n += 1
    if n:
        path.write_text(t, encoding="utf-8")
        print("[mod] SubtitleDialog", n)


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
