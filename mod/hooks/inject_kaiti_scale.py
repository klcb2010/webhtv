#!/usr/bin/env python3
"""Wire KaiTi auto-scale into ExoUtil.reapplySubtitleStyle. No MPV changes."""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

def patch_exo_util(path: Path) -> None:
    if not path.is_file():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if "applySubtitleViewTextSize" in t:
        print("[mod] ExoUtil kaiti scale already", path)
        return
    old = """            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok");"""
    new = """            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            try {
                com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(view.getSubtitleView());
            } catch (Throwable ignored) {}
            android.util.Log.i("SubtitleStyle", "reapplySubtitleStyle ok scale="
                    + com.fongmi.android.tv.setting.Setting.getEffectiveSubtitleFontScale());"""
    if old in t:
        t = t.replace(old, new, 1)
        print("[mod] patched reapplySubtitleStyle", path)
    elif "reapplySubtitleStyle" in t:
        t = t.replace(
            "view.getSubtitleView().setStyle(getCaptionStyle());",
            "view.getSubtitleView().setStyle(getCaptionStyle());\n"
            "            try { com.fongmi.android.tv.setting.Setting.applySubtitleViewTextSize(view.getSubtitleView()); } catch (Throwable ignored) {}",
            1,
        )
        print("[mod] patched setStyle soft", path)
    else:
        print("[mod] WARN no reapplySubtitleStyle in", path)
        return
    path.write_text(t, encoding="utf-8")

def main():
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/utils/ExoUtil.java")
    print("[mod] inject_kaiti_scale done")

if __name__ == "__main__":
    main()
