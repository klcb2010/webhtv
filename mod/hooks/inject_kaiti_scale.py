#!/usr/bin/env python3
"""Ensure KaiTi scale is applied on every ExoUtil.reapplySubtitleStyle call."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
ERRORS = []

def patch_file(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    # Idempotent marker
    if "KAITI_SCALE_APPLIED" in t and "applySubtitleViewTextSize" in t:
        print("[mod] already scaled", path)
        return

    # Replace entire reapplySubtitleStyle method body if found
    m = re.search(
        r"public static void reapplySubtitleStyle\(\)\s*\{[\s\S]*?\n    \}",
        t,
    )
    if m:
        new_method = '''public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) return;
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            // KAITI_SCALE_APPLIED
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
    }'''
        t = t[: m.start()] + new_method + t[m.end() :]
        print("[mod] replaced reapplySubtitleStyle method", path)
    else:
        # Soft: after every setStyle(getCaptionStyle())
        if "setStyle(getCaptionStyle())" in t and "applySubtitleViewTextSize" not in t:
            t = t.replace(
                "setStyle(getCaptionStyle());",
                "setStyle(getCaptionStyle());\n"
                "            /* KAITI_SCALE_APPLIED */\n"
                "            try { com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize("
                "view.getSubtitleView() != null ? view.getSubtitleView() : null); } catch (Throwable ignored) {}",
            )
            # fix if view not in scope - too risky
            print("[mod] WARN no reapply method; soft patch may be incomplete", path)
            ERRORS.append(f"no reapplySubtitleStyle in {path}")
        else:
            ERRORS.append(f"cannot patch {path}")
            print("[mod] FAIL cannot patch", path)
            return

    if t != orig:
        path.write_text(t, encoding="utf-8")
    # verify
    t2 = path.read_text(encoding="utf-8")
    if "applySubtitleViewTextSize" not in t2:
        ERRORS.append(f"verify fail {path}")
        print("[mod] VERIFY FAIL", path)
    else:
        print("[mod] VERIFY OK", path)

def main():
    print("[mod] inject_kaiti_scale ROOT", ROOT)
    for rel in (
        "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java",
        "app/src/main/java/com/fongmi/android/tv/utils/ExoUtil.java",
    ):
        patch_file(ROOT / rel)
    if ERRORS:
        print("[mod] FAILED", ERRORS, file=sys.stderr)
        sys.exit(1)
    print("[mod] inject_kaiti_scale SUCCESS")

if __name__ == "__main__":
    main()
