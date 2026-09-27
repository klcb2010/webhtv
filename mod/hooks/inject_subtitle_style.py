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
            // 优先文件主名 yahei，再中文名
            String fam = MpvSubtitleStylePolicy.getSubFontProperty();
            if (fam == null || fam.isEmpty()) fam = Setting.getSubtitleFontId();
            if (fam == null || fam.isEmpty()) fam = Setting.getSubtitleFontFamily();
            if (fam != null && !fam.isEmpty()) font = fam;
        } catch (Throwable ignored) {
        }
        try { applyUserAssStyle(); } catch (Throwable ignoredAss) {}
        return new CaptionStyle(font, false, false, fg, Color.BLACK, Color.TRANSPARENT, "outline-and-shadow", 3.0, 0.0);
    }
""".strip()

APPLY_METHOD = r"""
    private void applyUserAssStyle() {
        try {
            com.fongmi.android.tv.subtitle.SubtitleFontManager.prepareAllFonts();
            String style = MpvSubtitleStylePolicy.getAssForceStyle();
            String color = MpvSubtitleStylePolicy.getSubColorProperty();
            String border = MpvSubtitleStylePolicy.getSubBorderColorProperty();
            String font = MpvSubtitleStylePolicy.getSubFontProperty();
            String fontsDir = MpvSubtitleStylePolicy.getSubFontsDirProperty();
            boolean ok = false;
            String lastErr = "";
            // 候选：本对象、字段嵌套、常见 MPVLib 类
            java.util.ArrayList<Object> targets = new java.util.ArrayList<>();
            targets.add(this);
            try {
                for (java.lang.reflect.Field f : getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(this);
                        if (v != null) targets.add(v);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            for (String cn : new String[]{
                    "is.xyz.mpv.MPVLib",
                    "dev.jdtech.mpv.MPVLib",
                    "androidx.media3.mpvplayer.MpvLib",
                    "androidx.media3.mpvplayer.MPV",
                    "com.fongmi.android.tv.player.mpv.MpvLib"
            }) {
                try {
                    Class<?> c = Class.forName(cn);
                    targets.add(c); // static methods
                } catch (Throwable ignored) {}
            }
            // 写属性
            for (Object tgt : targets) {
                if (tgt == null) continue;
                Class<?> cls = (tgt instanceof Class) ? (Class<?>) tgt : tgt.getClass();
                Object inv = (tgt instanceof Class) ? null : tgt;
                // setOptionString / setPropertyString 等
                for (String mn : new String[]{"setOptionString", "setPropertyString", "setOption", "setProperty", "option", "setConfig"}) {
                    try {
                        java.lang.reflect.Method m = null;
                        try { m = cls.getMethod(mn, String.class, String.class); }
                        catch (Throwable e1) {
                            try { m = cls.getDeclaredMethod(mn, String.class, String.class); m.setAccessible(true); } catch (Throwable e2) {}
                        }
                        if (m == null) continue;
                        // static?
                        Object receiver = java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? null : inv;
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && receiver == null) continue;
                        m.invoke(receiver, "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE);
                        m.invoke(receiver, "sub-ass-force-style", style);
                        m.invoke(receiver, "sub-color", color);
                        m.invoke(receiver, "sub-border-color", border);
                        if (font != null) m.invoke(receiver, "sub-font", font);
                        if (fontsDir != null && !fontsDir.isEmpty()) {
                            m.invoke(receiver, "sub-fonts-dir", fontsDir);
                            try { m.invoke(receiver, "osd-fonts-dir", fontsDir); } catch (Throwable ignored) {}
                        }
                        ok = true;
                        lastErr = "via " + cls.getName() + "." + mn;
                        break;
                    } catch (Throwable e) {
                        lastErr = mn + ":" + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage());
                    }
                }
                if (ok) break;
                // command(String[])
                try {
                    java.lang.reflect.Method cmd = null;
                    try { cmd = cls.getMethod("command", String[].class); }
                    catch (Throwable e1) {
                        try { cmd = cls.getDeclaredMethod("command", String[].class); cmd.setAccessible(true); } catch (Throwable e2) {}
                    }
                    if (cmd != null) {
                        Object receiver = java.lang.reflect.Modifier.isStatic(cmd.getModifiers()) ? null : inv;
                        if (java.lang.reflect.Modifier.isStatic(cmd.getModifiers()) || receiver != null) {
                            cmd.invoke(receiver, (Object) new String[]{"set", "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE});
                            cmd.invoke(receiver, (Object) new String[]{"set", "sub-ass-force-style", style});
                            cmd.invoke(receiver, (Object) new String[]{"set", "sub-color", color});
                            if (font != null) cmd.invoke(receiver, (Object) new String[]{"set", "sub-font", font});
                            if (fontsDir != null && !fontsDir.isEmpty())
                                cmd.invoke(receiver, (Object) new String[]{"set", "sub-fonts-dir", fontsDir});
                            ok = true;
                            lastErr = "via command " + cls.getName();
                            break;
                        }
                    }
                } catch (Throwable e) {
                    lastErr = "cmd:" + e.getMessage();
                }
                // 禁止往内部 Map 写 String（会 ClassCastException）
            }
            android.util.Log.i("MpvSubStyle", "applyUserAssStyle ok=" + ok + " font=" + font + " dir=" + fontsDir + " how=" + lastErr);
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

    # --- imports ---
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

    # --- 1) 先插入方法定义（在类最后的 } 前）---
    if "private void applyUserAssStyle" not in t:
        idx = t.rfind("\n}")
        if idx > 0:
            t = t[:idx] + "\n" + APPLY_METHOD + t[idx:]
            print("[mod] inserted applyUserAssStyle method")
        else:
            print("[mod] WARN cannot find class end for applyUserAssStyle")

    # --- 2) 替换 defaultCaptionStyle 体（内含调用）---
    pat = re.compile(r"private CaptionStyle defaultCaptionStyle\(\)\s*\{[\s\S]*?\n    \}", re.M)
    if pat.search(t):
        t = pat.sub(MPV_DEFAULT_STYLE, t, count=1)
        print("[mod] replaced defaultCaptionStyle")
    else:
        print("[mod] WARN: defaultCaptionStyle not found")

    # --- 3) 若方法体里还没有调用，在 captionStyle 入口补一次 ---
    if "try { applyUserAssStyle(); }" not in t:
        hooked = False
        for pat2, name in [
            (r"(CaptionStyle\s+captionStyle\s*\([^)]*\)\s*\{)", "captionStyle"),
            (r"(private\s+CaptionStyle\s+captionStyle\s*\([^)]*\)\s*\{)", "captionStyle-priv"),
        ]:
            t3, n3 = re.subn(
                pat2,
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


    # optional: after hwdec option write, re-apply style (safe plain-string search)
    for key in ('setOption("hwdec"', 'setProperty("hwdec"', 'option("hwdec"', 'setOptionString("hwdec"'):
        idx = t.find(key)
        if idx < 0:
            continue
        semi = t.find(';', idx)
        if semi < 0:
            continue
        insert = "\n        try { applyUserAssStyle(); } catch (Throwable ignoredAssOpt) {}"
        t = t[: semi + 1] + insert + t[semi + 1 :]
        print("[mod] hooked after hwdec option")
        break


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
