#!/usr/bin/env python3
"""Patch Exo/MPV subtitle style to use Setting subtitle color/font."""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

EXO_CAPTION_STYLE = """
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

MPV_DEFAULT_STYLE = """
private CaptionStyle defaultCaptionStyle() {
        int fg = Color.YELLOW;
        String font = "sans-serif";
        try {
            fg = Setting.getSubtitleColorArgb();
        } catch (Throwable ignored) {
        }
        try {
            String fam = Setting.getSubtitleFontFamily();
            if (fam != null && !fam.isEmpty()) font = fam;
        } catch (Throwable ignored) {
        }
        try { applyUserAssStyle(); } catch (Throwable ignoredAss) {}
        return new CaptionStyle(font, false, false, fg, Color.BLACK, Color.TRANSPARENT, "outline-and-shadow", 3.0, 0.0);
    }
""".strip()

APPLY_METHOD = r"""
    /** mod: user subtitle color/font -> MPV props (force + force-style + sub-color) */
    private void applyUserAssStyle() {
        try {
            String style = MpvSubtitleStylePolicy.getAssForceStyle();
            String color = MpvSubtitleStylePolicy.getSubColorProperty();
            String border = MpvSubtitleStylePolicy.getSubBorderColorProperty();
            String font = MpvSubtitleStylePolicy.getSubFontProperty();
            boolean ok = false;
            for (String mn : new String[]{"setProperty", "setOption", "option"}) {
                try {
                    java.lang.reflect.Method m = getClass().getMethod(mn, String.class, String.class);
                    m.invoke(this, "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE);
                    m.invoke(this, "sub-ass-force-style", style);
                    m.invoke(this, "sub-color", color);
                    m.invoke(this, "sub-border-color", border);
                    m.invoke(this, "sub-shadow-color", border);
                    if (font != null && !font.isEmpty()) m.invoke(this, "sub-font", font);
                    ok = true;
                    break;
                } catch (Throwable ignoredProp) {
                }
            }
            if (!ok) {
                try {
                    java.lang.reflect.Method cmd = getClass().getMethod("command", String[].class);
                    cmd.invoke(this, (Object) new String[]{"set", "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE});
                    cmd.invoke(this, (Object) new String[]{"set", "sub-ass-force-style", style});
                    cmd.invoke(this, (Object) new String[]{"set", "sub-color", color});
                    ok = true;
                } catch (Throwable ignoredCmd) {
                }
            }
            android.util.Log.i("MpvSubStyle", "applyUserAssStyle ok=" + ok + " color=" + color);
        } catch (Throwable e) {
            android.util.Log.w("MpvSubStyle", "applyUserAssStyle: " + e.getMessage());
        }
    }
"""


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
    pat = re.compile(r"public static CaptionStyleCompat getCaptionStyle\(\)\s*\{[^}]*\}", re.S)
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

    if "import com.fongmi.android.tv.setting.Setting;" not in t:
        if "import com.fongmi.android.tv.setting.PlayerSetting;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.setting.PlayerSetting;",
                "import com.fongmi.android.tv.setting.PlayerSetting;\nimport com.fongmi.android.tv.setting.Setting;",
            )
        elif "package androidx.media3.mpvplayer;" in t:
            t = t.replace(
                "package androidx.media3.mpvplayer;",
                "package androidx.media3.mpvplayer;\n\nimport com.fongmi.android.tv.setting.Setting;",
                1,
            )
    if "import com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy;" not in t:
        if "import com.fongmi.android.tv.setting.Setting;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.setting.Setting;",
                "import com.fongmi.android.tv.setting.Setting;\nimport com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy;",
                1,
            )
        else:
            t = t.replace(
                "package androidx.media3.mpvplayer;",
                "package androidx.media3.mpvplayer;\n\nimport com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy;",
                1,
            )

    pat = re.compile(r"private CaptionStyle defaultCaptionStyle\(\)\s*\{[\s\S]*?\n    \}", re.M)
    if pat.search(t):
        t = pat.sub(MPV_DEFAULT_STYLE, t, count=1)
    else:
        print("[mod] WARN: defaultCaptionStyle not found")

    if "applyUserAssStyle" not in t:
        idx = t.rfind("\n}")
        if idx > 0:
            t = t[:idx] + "\n" + APPLY_METHOD + t[idx:]
            print("[mod] inserted applyUserAssStyle")

    # 仅在方法入口 / defaultCaptionStyle 体内调用，不改 return 语句
    if "applyUserAssStyle();" not in t:
        hooked = False
        for pat, name in [
            (r"(CaptionStyle\s+captionStyle\s*\([^)]*\)\s*\{)", "captionStyle"),
            (r"(private\s+CaptionStyle\s+captionStyle\s*\([^)]*\)\s*\{)", "captionStyle-priv"),
            (r"(public\s+CaptionStyle\s+captionStyle\s*\([^)]*\)\s*\{)", "captionStyle-pub"),
        ]:
            t3, n3 = re.subn(
                pat,
                r"\1\n        try { applyUserAssStyle(); } catch (Throwable ignoredAss) {}",
                t,
                count=1,
            )
            if n3:
                t = t3
                print("[mod] hooked", name, "entry")
                hooked = True
                break
        if not hooked:
            for marker in ["void prepare(", "void setMediaItem("]:
                pos = t.find(marker)
                if pos < 0:
                    continue
                brace = t.find("{", pos)
                if brace < 0:
                    continue
                t = (
                    t[: brace + 1]
                    + "\n        try { applyUserAssStyle(); } catch (Throwable ignoredAss) {}"
                    + t[brace + 1 :]
                )
                print("[mod] entry-hook", marker)
                hooked = True
                break
        if not hooked:
            print("[mod] WARN: no applyUserAssStyle call site")

    t = t.replace('"sub-ass-override", "scale"', '"sub-ass-override", "force"')
    t = t.replace('"ass-style-override", "scale"', '"ass-style-override", "force"')

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


def patch_mpv_options_apply(path: Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    for a, b in [
        ('"sub-ass-override", "scale"', '"sub-ass-override", "force"'),
        ("'sub-ass-override', 'scale'", "'sub-ass-override', 'force'"),
        ('"sub-ass-override", "yes"', '"sub-ass-override", "force"'),
    ]:
        t = t.replace(a, b)
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched mpv option strings", path)
    else:
        print("[mod] no sub-ass-override string in", path.name)


def main() -> None:
    patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    mpv = ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"
    patch_mpv_player(mpv)
    patch_mpv_options_apply(mpv)
    patch_ass_policy(ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java")
    print("[mod] inject_subtitle_style done")


if __name__ == "__main__":
    main()
