#!/usr/bin/env python3
"""Patch Exo/MPV subtitle style to use Setting subtitle color/font."""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

EXO_CAPTION_STYLE = """
public static CaptionStyleCompat getCaptionStyle() {
        // 个性设置优先；内封 ASS 文字轨由 setApplyEmbeddedStyles(false) 避免覆盖
        try { com.fongmi.android.tv.subtitle.SubtitleFontManager.prepareAllFonts(); } catch (Throwable ignored) {}
        int fg = Color.WHITE;
        try { fg = Setting.getSubtitleColorArgb(); } catch (Throwable ignored) {}
        android.graphics.Typeface tf = null;
        try { tf = Setting.getSubtitleTypeface(); } catch (Throwable ignored) {}
        if (tf == null) tf = android.graphics.Typeface.SANS_SERIF;
        try {
            /* log silenced */
        } catch (Throwable ignored) {}
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
            String id = null;
            try { id = com.fongmi.android.tv.setting.Setting.getSubtitleFontId(); } catch (Throwable ignored) {}
            boolean ok = false;
            String lastErr = "none";
            for (String mn : new String[]{"setOption", "setProperty", "setOptionString", "setPropertyString", "option"}) {
                try {
                    java.lang.reflect.Method m = null;
                    try { m = getClass().getMethod(mn, String.class, String.class); } catch (Throwable e1) {
                        try { m = getClass().getDeclaredMethod(mn, String.class, String.class); m.setAccessible(true); } catch (Throwable e2) {}
                    }
                    if (m == null) continue;
                    m.invoke(this, "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE);
                    m.invoke(this, "sub-ass-force-style", style);
                    m.invoke(this, "sub-color", color);
                    m.invoke(this, "sub-border-color", border);
                    if (fontsDir != null && !fontsDir.isEmpty()) {
                        m.invoke(this, "sub-fonts-dir", fontsDir);
                        try { m.invoke(this, "osd-fonts-dir", fontsDir); } catch (Throwable ignored) {}
                    }
                    if (font != null) m.invoke(this, "sub-font", font);
                    if (id != null) try { m.invoke(this, "sub-font", id); } catch (Throwable ignored) {}
                    if (font != null) m.invoke(this, "sub-font", font);
                    ok = true;
                    lastErr = "via this." + mn;
                    break;
                } catch (Throwable e) {
                    lastErr = mn + ":" + e.getClass().getSimpleName();
                }
            }
            if (!ok) {
                try {
                    java.lang.reflect.Method cmd = null;
                    try { cmd = getClass().getMethod("command", String[].class); } catch (Throwable e1) {
                        try { cmd = getClass().getDeclaredMethod("command", String[].class); cmd.setAccessible(true); } catch (Throwable e2) {}
                    }
                    if (cmd != null) {
                        cmd.invoke(this, (Object) new String[]{"set", "sub-ass-override", MpvSubtitleStylePolicy.ASS_OVERRIDE});
                        cmd.invoke(this, (Object) new String[]{"set", "sub-ass-force-style", style});
                        cmd.invoke(this, (Object) new String[]{"set", "sub-color", color});
                        if (fontsDir != null && !fontsDir.isEmpty())
                            cmd.invoke(this, (Object) new String[]{"set", "sub-fonts-dir", fontsDir});
                        if (font != null)
                            cmd.invoke(this, (Object) new String[]{"set", "sub-font", font});
                        ok = true;
                        lastErr = "via this.command";
                    }
                } catch (Throwable e) {
                    lastErr = "cmd:" + e.getClass().getSimpleName();
                }
            }
            // 禁止写任何 Map，避免 ClassCastException
            /* log silenced */
        } catch (Throwable e) {
            /* log silenced */}
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
        "view.getSubtitleView().setApplyEmbeddedStyles(false);",
    )
    t = t.replace(
        "view.getSubtitleView().setApplyEmbeddedStyles(PlayerSetting.isCaption());",
        "view.getSubtitleView().setApplyEmbeddedStyles(false);",
    )
    # 顺序：先关 embedded 再 setStyle
    t = t.replace(
        "view.getSubtitleView().setStyle(getCaptionStyle());\n        view.getSubtitleView().setApplyEmbeddedStyles(false);",
        "view.getSubtitleView().setApplyEmbeddedStyles(false);\n        view.getSubtitleView().setStyle(getCaptionStyle());",
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



    # 源码级：在已有 setOption/setProperty("hwdec" 旁注入字体 option（与上游同 API，不用 Map）
    def _inject_after_call(src: str, key: str) -> str:
        idx = 0
        while True:
            pos = src.find(key, idx)
            if pos < 0:
                return src
            # 找到方法名前缀 setOption / setProperty
            line_start = src.rfind("\n", 0, pos) + 1
            semi = src.find(";", pos)
            if semi < 0:
                return src
            line = src[line_start:semi + 1]
            # 解析 receiver.method(
            mcall = re.search(r'((?:this\.)?[A-Za-z_][\w\.]*)\s*\(\s*"hwdec"', line)
            if not mcall:
                idx = semi + 1
                continue
            callee = mcall.group(1)  # e.g. setOption or this.setOption or options.set
            # 若是 Map.put 则跳过
            if ".put" in callee or callee.endswith("put"):
                idx = semi + 1
                continue
            block = (
                "\n        try {"
                "\n            " + callee + "(\"sub-ass-override\", MpvSubtitleStylePolicy.ASS_OVERRIDE);"
                "\n            " + callee + "(\"sub-fonts-dir\", MpvSubtitleStylePolicy.getSubFontsDirProperty());"
                "\n            " + callee + "(\"sub-font\", MpvSubtitleStylePolicy.getSubFontProperty());"
                "\n            " + callee + "(\"sub-ass-force-style\", MpvSubtitleStylePolicy.getAssForceStyle());"
                "\n            " + callee + "(\"sub-color\", MpvSubtitleStylePolicy.getSubColorProperty());"
                "\n        } catch (Throwable ignoredModFont) {}"
            )
            if "ignoredModFont" in src[semi:semi+400]:
                return src
            src = src[: semi + 1] + block + src[semi + 1 :]
            print("[mod] source-inject font options after", key)
            return src

    for key in (
        'setOption("hwdec"',
        'setProperty("hwdec"',
        'setOptionString("hwdec"',
        'option("hwdec"',
        "setOption(\"hwdec\"",
    ):
        t2 = _inject_after_call(t, key)
        if t2 != t:
            t = t2
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




def patch_exo_ass_session(path: Path) -> None:
    """Disable native libass session so SubtitleView + getCaptionStyle can stick.

    Root cause of style flash-then-revert: ExoAssSession becomes ACTIVE and paints
    original ASS on an overlay, covering SubtitleView user styles.
    """
    if not path.exists():
        print("[mod] skip missing", path)
        return
    text = path.read_text(encoding="utf-8")
    orig = text
    if "user style path" in text and "createIfEnabled" in text:
        print("[mod] ExoAssSession already patched")
        return
    new_body = (
        "public static ExoAssSession createIfEnabled(Context context, boolean tunneling) {\n"
        "        // User color/font via SubtitleView; native ASS ACTIVE covers settings after first frames\n"
        "        try {\n"
        "            /* log silenced */\n"
        "        } catch (Throwable ignored) {}\n"
        "        return null;\n"
        "    }"
    )
    pat = re.compile(
        r"public static ExoAssSession createIfEnabled\(Context context, boolean tunneling\)\s*\{[^}]*\}",
        re.S,
    )
    if pat.search(text):
        text = pat.sub(new_body, text, count=1)
        path.write_text(text, encoding="utf-8")
        print("[mod] ExoAssSession.createIfEnabled -> null (user style)")
    else:
        print("[mod] WARN ExoAssSession.createIfEnabled not found")


def patch_ass_fonts_user_dir(path: Path) -> None:
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    if "subtitle_fonts" in text:
        print("[mod] AssFonts already has subtitle_fonts")
        return
    needle = 'for (String path : new String[]{"/system/fonts"'
    if needle not in text:
        print("[mod] WARN AssFonts.prepare dir loop not found")
        return
    insert = (
        "try {\n"
        '            File userFonts = new File(context.getFilesDir(), "subtitle_fonts");\n'
        "            if (userFonts.isDirectory() && userFonts.canRead()) directories.add(userFonts);\n"
        "        } catch (Throwable ignored) {}\n"
        "        " + needle
    )
    text = text.replace(needle, insert, 1)
    path.write_text(text, encoding="utf-8")
    print("[mod] AssFonts.prepare + subtitle_fonts dir")


def patch_exo_reapply_hook(path: Path) -> None:
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    if "reapplySubtitleStyle" in text:
        print("[mod] ExoUtil.reapplySubtitleStyle already")
        return
    if "public static void setPlayerView(PlayerView view)" not in text:
        print("[mod] WARN setPlayerView not found")
        return
    if "sLastPlayerView" not in text:
        text = text.replace(
            "public class ExoUtil {",
            "public class ExoUtil {\n\n"
            "    private static java.lang.ref.WeakReference<PlayerView> sLastPlayerView;\n",
            1,
        )
    text = text.replace(
        "public static void setPlayerView(PlayerView view) {\n"
        "        view.setRender(PlayerSetting.getRender());",
        "public static void setPlayerView(PlayerView view) {\n"
        "        sLastPlayerView = new java.lang.ref.WeakReference<>(view);\n"
        "        view.setRender(PlayerSetting.getRender());",
        1,
    )
    helper = """

    /** Re-apply caption style on current PlayerView after settings change. */
    public static void reapplySubtitleStyle() {
        try {
            PlayerView view = sLastPlayerView == null ? null : sLastPlayerView.get();
            if (view == null || view.getSubtitleView() == null) return;
            view.getSubtitleView().setApplyEmbeddedStyles(false);
            view.getSubtitleView().setStyle(getCaptionStyle());
            /* log silenced */
        } catch (Throwable e) {
            /* log silenced */}
    }
"""
    m = re.search(
        r"(public static void setPlayerView\(PlayerView view\)\s*\{[\s\S]*?\n    \})",
        text,
    )
    if m:
        text = text[: m.end()] + helper + text[m.end() :]
        print("[mod] ExoUtil.reapplySubtitleStyle added")
    else:
        print("[mod] WARN cannot insert reapplySubtitleStyle")
    path.write_text(text, encoding="utf-8")



def main() -> None:
    # EXO focus this iteration
    patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_reapply_hook(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    patch_exo_ass_session(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ass/ExoAssSession.java")
    patch_ass_fonts_user_dir(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ass/AssFonts.java")
    # MPV: keep existing patches only, no new logic
    mpv = ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"
    patch_mpv_player(mpv)
    patch_mpv_options_apply(mpv)
    patch_ass_policy(ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSubtitleStylePolicy.java")
    print("[mod] inject_subtitle_style done")


if __name__ == "__main__":
    main()
