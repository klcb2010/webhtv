#!/usr/bin/env python3
"""Patch Exo/MPV subtitle style to use Setting subtitle color/font."""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

EXO_CAPTION_STYLE = r"""
public static CaptionStyleCompat getCaptionStyle() {
        if (PlayerSetting.isCaption()) {
            return CaptionStyleCompat.createFromCaptionStyle(((CaptioningManager) App.get().getSystemService(Context.CAPTIONING_SERVICE)).getUserStyle());
        }
        int fg = Setting.getSubtitleColorArgb();
        android.graphics.Typeface tf = null;
        try {
            tf = Setting.getSubtitleTypeface();
        } catch (Throwable ignored) {
        }
        return new CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, tf);
    }
""".strip()

MPV_DEFAULT_STYLE = r"""
private CaptionStyle defaultCaptionStyle() {
        int fg = Color.YELLOW;
        String font = "cursive";
        try {
            fg = Setting.getSubtitleColorArgb();
            font = Setting.getSubtitleFontFamily();
        } catch (Throwable ignored) {
        }
        return new CaptionStyle(font, false, false, fg, Color.BLACK, Color.TRANSPARENT, "outline-and-shadow", 3.0, 0.0);
    }
""".strip()


def patch_exo(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "com.fongmi.android.tv.setting.Setting" not in t:
        t = t.replace(
            "import com.fongmi.android.tv.setting.PlayerSetting;",
            "import com.fongmi.android.tv.setting.PlayerSetting;\nimport com.fongmi.android.tv.setting.Setting;",
        )
    t = t.replace(
        "view.getSubtitleView().setApplyEmbeddedStyles(true);",
        "view.getSubtitleView().setApplyEmbeddedStyles(PlayerSetting.isCaption());",
    )
    pat = re.compile(
        r"public static CaptionStyleCompat getCaptionStyle\(\)\s*\{[^}]*\}",
        re.S,
    )
    if pat.search(t):
        t = pat.sub(EXO_CAPTION_STYLE, t, count=1)
    else:
        print("[mod] WARN: getCaptionStyle not found")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def patch_mpv_player(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "com.fongmi.android.tv.setting.Setting" not in t:
        if "import com.fongmi.android.tv.setting.PlayerSetting;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.setting.PlayerSetting;",
                "import com.fongmi.android.tv.setting.PlayerSetting;\nimport com.fongmi.android.tv.setting.Setting;",
            )
        else:
            t = t.replace(
                "package androidx.media3.mpvplayer;",
                "package androidx.media3.mpvplayer;\n\nimport com.fongmi.android.tv.setting.Setting;",
            )
    pat = re.compile(
        r"private CaptionStyle defaultCaptionStyle\(\)\s*\{\s*return new CaptionStyle\([^;]+;\s*\}",
        re.S,
    )
    if pat.search(t):
        t = pat.sub(MPV_DEFAULT_STYLE, t, count=1)
    else:
        print("[mod] WARN: defaultCaptionStyle not found")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def patch_ass_policy(path: Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if 'ASS_OVERRIDE = "scale"' in t:
        t = t.replace('ASS_OVERRIDE = "scale"', 'ASS_OVERRIDE = "force"')
        path.write_text(t, encoding="utf-8")
        print("[mod] ASS_OVERRIDE -> force", path)


def main() -> None:
    patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_mpv_player(ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java")
    patch_ass_policy(ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java")
    print("[mod] inject_subtitle_style done")


if __name__ == "__main__":
    main()
