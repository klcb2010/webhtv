#!/usr/bin/env python3
"""E-AC3 soft decode: only E-AC3/AC3 skip MediaCodec and use FFmpeg (same pattern as ALAC).

When Setting.isEac3SoftDecode():
  - ExoAudioCodecSelector.requiresFfmpeg(eac3/ac3/eac3-joc) → empty MediaCodec list
  - CompatFfmpegAudioRenderer handles those formats
  - Other audio (AAC etc.) unchanged
  - Disable compressed passthrough so E-AC3 is decoded to PCM
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def patch_selector(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] ExoAudioCodecSelector missing")
        return
    t = path.read_text(encoding="utf-8")
    if "isEac3SoftDecode" in t and "AUDIO_E_AC3" in t:
        print("[mod] ExoAudioCodecSelector already patched")
        return
    old = """    static boolean requiresFfmpeg(String mimeType) {
        // The target vendor ALAC codec can report READY without emitting PCM. Keep ALAC on the
        // bundled FFmpeg renderer so decoder progress and AudioTrack initialization are coupled.
        return MimeTypes.AUDIO_ALAC.equals(mimeType);
    }"""
    new = """    static boolean requiresFfmpeg(String mimeType) {
        // The target vendor ALAC codec can report READY without emitting PCM. Keep ALAC on the
        // bundled FFmpeg renderer so decoder progress and AudioTrack initialization are coupled.
        if (MimeTypes.AUDIO_ALAC.equals(mimeType)) return true;
        // Optional: E-AC3/AC3 soft path (Setting "E-AC3音频软解") — skip MediaCodec, use FFmpeg.
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) {
                if (MimeTypes.AUDIO_E_AC3.equals(mimeType)
                        || MimeTypes.AUDIO_AC3.equals(mimeType)
                        || MimeTypes.AUDIO_E_AC3_JOC.equals(mimeType)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }"""
    if old in t:
        t = t.replace(old, new, 1)
        path.write_text(t, encoding="utf-8")
        print("[mod] ExoAudioCodecSelector requiresFfmpeg eac3")
    else:
        # soft match
        t2, n = re.subn(
            r"static boolean requiresFfmpeg\(String mimeType\)\s*\{[^}]*return MimeTypes\.AUDIO_ALAC\.equals\(mimeType\);\s*\}",
            new.strip(),
            t,
            count=1,
            flags=re.S,
        )
        if n:
            path.write_text(t2, encoding="utf-8")
            print("[mod] ExoAudioCodecSelector soft patch")
        else:
            print("[mod] WARN requiresFfmpeg not found")


def patch_exo(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] ExoUtil missing")
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    # Remove global isAudioPrefer force if we added it before (breaks non-EAC3)
    bad = """    private static boolean isAudioPrefer(int decode) {
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) return true;
        } catch (Throwable ignored) {
        }
        return decode != PlayerEngine.SOFT && PlayerSetting.isAudioPrefer(PlayerSetting.EXO);
    }"""
    good = """    private static boolean isAudioPrefer(int decode) {
        return decode != PlayerEngine.SOFT && PlayerSetting.isAudioPrefer(PlayerSetting.EXO);
    }"""
    if bad in t:
        t = t.replace(bad, good, 1)
        print("[mod] ExoUtil restored isAudioPrefer (no global FFmpeg prefer)")

    # Strip channel mix leftovers
    for imp in (
        "import androidx.media3.common.audio.AudioProcessor;\n",
        "import androidx.media3.common.audio.ChannelMixingAudioProcessor;\n",
        "import androidx.media3.common.audio.ChannelMixingMatrix;\n",
    ):
        t = t.replace(imp, "")
    t2, n = re.subn(
        r"\n\s*try \{\s*"
        r"if \(com\.fongmi\.android\.tv\.setting\.Setting\.isEac3SoftDecode\(\)\) \{\s*"
        r"ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor\(\);[\s\S]*?"
        r"builder\.setAudioProcessors\(new AudioProcessor\[\]\{mix\}\);\s*"
        r"\}\s*"
        r"\} catch \(Throwable ignored\) \{\s*\}\s*"
        r"return ExoDiagnosticAudioOutput\.sink\(builder\.build\(\), diagnostics\);",
        "\n        return ExoDiagnosticAudioOutput.sink(builder.build(), diagnostics);",
        t,
        count=1,
    )
    if n:
        t = t2
        print("[mod] ExoUtil removed downmix")

    # Passthrough off when switch on
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
    else:
        print("[mod] ExoUtil ok")


patch_selector(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoAudioCodecSelector.java")
patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
print("[mod] inject_eac3_soft_audio done")
