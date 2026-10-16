#!/usr/bin/env python3
"""Apply KaiTi scale using the same fractional text-size path as SubtitleDialog."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS: list[str] = []


def patch_exo_util(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    m = re.search(r"public static void reapplySubtitleStyle\(\)\s*\{[\s\S]*?\n    \}", t)
    if not m:
        ERRORS.append(f"no reapplySubtitleStyle in {path}")
        print("[mod] FAIL no reapply", path)
        return
    new_method = """public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) return;
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            // KAITI_SCALE_APPLIED — same units as SubtitleDialog / PlayerSetting.subtitle_text_size
            try {
                com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(view.getSubtitleView());
            } catch (Throwable ignored) {}
            try {
                android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok scale="
                        + com.fongmi.android.tv.setting.Setting.getEffectiveSubtitleFontScale()
                        + " kaiti=" + com.fongmi.android.tv.setting.Setting.isKaiTiLikeSubtitleFont());
            } catch (Throwable e) {
                android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok");
            }
        } catch (Throwable e) {
            android.util.Log.w("SubtitleStyle", "reapplySubtitleStyle: " + e.getMessage());
        }
    }"""
    t = t[: m.start()] + new_method + t[m.end() :]
    path.write_text(t, encoding="utf-8")
    if "applySubtitleViewTextSize" not in path.read_text(encoding="utf-8"):
        ERRORS.append(f"verify fail {path}")
        print("[mod] VERIFY FAIL", path)
    else:
        print("[mod] VERIFY OK", path)


def patch_subtitle_dialog(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing SubtitleDialog", path)
        return
    t = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_DIALOG" in t:
        print("[mod] SubtitleDialog already", path)
        return
    # After each size change, re-apply kaiti scale on the same view
    hook = """
        // KAITI_SCALE_DIALOG
        try {
            com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(subtitleView);
        } catch (Throwable ignored) {}
"""
    changed = False
    for anchor in (
        "PlayerSetting.putSubtitleTextSize(subtitleView.getTextSize());",
        "subtitleView.reset();",
    ):
        if anchor in t and "KAITI_SCALE_DIALOG" not in t[t.find(anchor) : t.find(anchor) + 120]:
            t = t.replace(anchor, anchor + hook, 1)
            changed = True
    # onReset puts 0 then reset - also after putSubtitleTextSize(0.0f) for native path
    if "PlayerSetting.putSubtitleTextSize(0.0f);" in t:
        t = t.replace(
            "PlayerSetting.putSubtitleTextSize(0.0f);",
            "PlayerSetting.putSubtitleTextSize(0.0f);"
            + "\n        // KAITI_SCALE_DIALOG_RESET\n"
            + "        try { if (subtitleView != null) com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(subtitleView); } catch (Throwable ignored) {}\n",
            1,
        )
        changed = True
    if changed:
        path.write_text(t, encoding="utf-8")
        print("[mod] SubtitleDialog hooked", path)
    else:
        print("[mod] WARN SubtitleDialog anchors not found", path)


def patch_player_manager(path: Path) -> None:
    """When engine applies stored subtitle size, multiply by KaiTi scale for render."""
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    if "KAITI_SCALE_ENGINE_STYLE" in t:
        print("[mod] PlayerManager style already")
        return
    # Common pattern: setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition())
    old = "engine.setSubtitleStyle(PlayerSetting.getSubtitleTextSize(), PlayerSetting.getSubtitlePosition());"
    new = (
        "{\n"
        "            // KAITI_SCALE_ENGINE_STYLE\n"
        "            float __st = PlayerSetting.getSubtitleTextSize();\n"
        "            if (__st <= 0f) __st = 0.0533f;\n"
        "            try { __st *= com.fongmi.android.tv.setting.Setting.getEffectiveSubtitleFontScale(); } catch (Throwable ignored) {}\n"
        "            engine.setSubtitleStyle(__st, PlayerSetting.getSubtitlePosition());\n"
        "        }"
    )
    if old in t:
        t = t.replace(old, new)
        path.write_text(t, encoding="utf-8")
        print("[mod] PlayerManager setSubtitleStyle scaled x", t.count("KAITI_SCALE_ENGINE_STYLE"))
    else:
        print("[mod] WARN PlayerManager setSubtitleStyle pattern not found")


def main() -> None:
    print("[mod] inject_kaiti_scale ROOT", ROOT)
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ExoUtil.java")
    patch_subtitle_dialog(ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/SubtitleDialog.java")
    patch_player_manager(ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")
    if ERRORS:
        print("[mod] FAILED", ERRORS, file=sys.stderr)
        sys.exit(1)
    print("[mod] inject_kaiti_scale SUCCESS")


if __name__ == "__main__":
    main()
