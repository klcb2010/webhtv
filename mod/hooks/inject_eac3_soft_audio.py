#!/usr/bin/env python3
"""E-AC3 soft decode: prefer FFmpeg audio renderer when Setting.isEac3SoftDecode()."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

def patch_exo(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] ExoUtil missing")
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    # Force FFmpeg audio prefer when E-AC3 soft decode switch is on
    old = """    private static boolean isAudioPrefer(int decode) {
        return decode != PlayerEngine.SOFT && PlayerSetting.isAudioPrefer(PlayerSetting.EXO);
    }"""
    new = """    private static boolean isAudioPrefer(int decode) {
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) return true;
        } catch (Throwable ignored) {
        }
        return decode != PlayerEngine.SOFT && PlayerSetting.isAudioPrefer(PlayerSetting.EXO);
    }"""
    if old in t:
        t = t.replace(old, new, 1)
        print("[mod] ExoUtil isAudioPrefer eac3")
    elif "Setting.isEac3SoftDecode()" in t:
        print("[mod] ExoUtil eac3 already")
    else:
        # soft match
        t2, n = re.subn(
            r"private static boolean isAudioPrefer\(int decode\)\s*\{\s*return decode != PlayerEngine\.SOFT && PlayerSetting\.isAudioPrefer\(PlayerSetting\.EXO\);\s*\}",
            new.strip(),
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] ExoUtil isAudioPrefer soft")
        else:
            print("[mod] WARN isAudioPrefer not found")

    # When switch on, disable compressed passthrough so E-AC3 is decoded to PCM not HDMI-raw
    if "isEac3SoftDecode()" in t and "passthrough" in t:
        old_p = "boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);"
        new_p = """boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) passthrough = false;
        } catch (Throwable ignored) {
        }"""
        if old_p in t and "isEac3SoftDecode()) passthrough" not in t:
            t = t.replace(old_p, new_p, 1)
            print("[mod] ExoUtil disable passthrough when eac3 soft")

    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] ExoUtil braces", t.count("{") - t.count("}"))

patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
print("[mod] inject_eac3_soft_audio done")
