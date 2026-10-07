#!/usr/bin/env python3
"""E-AC3 soft decode: prefer FFmpeg audio + optional stereo downmix when Setting.isEac3SoftDecode()."""
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

    # Imports for channel mixing
    if "ChannelMixingAudioProcessor" not in t:
        if "import androidx.media3.common.AudioAttributes;" in t:
            t = t.replace(
                "import androidx.media3.common.AudioAttributes;",
                "import androidx.media3.common.AudioAttributes;\n"
                "import androidx.media3.common.audio.AudioProcessor;\n"
                "import androidx.media3.common.audio.ChannelMixingAudioProcessor;\n"
                "import androidx.media3.common.audio.ChannelMixingMatrix;",
                1,
            )
            print("[mod] ExoUtil channel-mix imports")
        else:
            print("[mod] WARN no AudioAttributes import site")

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
    if "Setting.isEac3SoftDecode()) return true" not in t:
        if old in t:
            t = t.replace(old, new, 1)
            print("[mod] ExoUtil isAudioPrefer eac3")
        else:
            t2, n = re.subn(
                r"private static boolean isAudioPrefer\(int decode\)\s*\{\s*"
                r"return decode != PlayerEngine\.SOFT && PlayerSetting\.isAudioPrefer\(PlayerSetting\.EXO\);\s*\}",
                new.strip(),
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] ExoUtil isAudioPrefer soft")
            else:
                print("[mod] WARN isAudioPrefer not found")
    else:
        print("[mod] ExoUtil isAudioPrefer already")

    # Disable passthrough when soft decode on
    old_p = "boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);"
    new_p = """boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) passthrough = false;
        } catch (Throwable ignored) {
        }"""
    if old_p in t and "isEac3SoftDecode()) passthrough" not in t:
        t = t.replace(old_p, new_p, 1)
        print("[mod] ExoUtil disable passthrough when eac3 soft")

    # Stereo downmix via ChannelMixingAudioProcessor before builder.build()
    # Target: after DefaultAudioSink.Builder setup, before return sink
    if "eac3StereoDownmix" not in t and "ChannelMixingAudioProcessor" in t:
        # Insert before "return ExoDiagnosticAudioOutput.sink(builder.build()"
        marker = "return ExoDiagnosticAudioOutput.sink(builder.build(), diagnostics);"
        downmix = """
        try {
            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) {
                ChannelMixingAudioProcessor mix = new ChannelMixingAudioProcessor();
                // 3~8 声道恒定增益下混到立体声，降低投影机 CPU
                for (int ch = 3; ch <= 8; ch++) {
                    try {
                        mix.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(ch, 2));
                    } catch (Throwable ignored) {
                    }
                }
                builder.setAudioProcessors(new AudioProcessor[]{mix});
            }
        } catch (Throwable ignored) {
        }
        return ExoDiagnosticAudioOutput.sink(builder.build(), diagnostics);"""
        if marker in t:
            t = t.replace(marker, downmix, 1)
            print("[mod] ExoUtil stereo downmix")
        else:
            # alternate return without diagnostics var name
            m = re.search(
                r"return ExoDiagnosticAudioOutput\.sink\(builder\.build\(\),\s*diagnostics\);",
                t,
            )
            if m:
                t = t[: m.start()] + downmix + t[m.end() :]
                print("[mod] ExoUtil stereo downmix regex")
            else:
                print("[mod] WARN sink return not found for downmix")
    elif "eac3StereoDownmix" in t or "createForConstantGain" in t:
        print("[mod] ExoUtil downmix already")

    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] ExoUtil braces", t.count("{") - t.count("}"))
    else:
        print("[mod] ExoUtil unchanged")


patch_exo(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
print("[mod] inject_eac3_soft_audio done")
