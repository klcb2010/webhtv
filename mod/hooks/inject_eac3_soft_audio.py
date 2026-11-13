#!/usr/bin/env python3
"""E-AC3 soft decode: only E-AC3/AC3 skip MediaCodec and use FFmpeg (same pattern as ALAC).

When Setting.isEac3SoftDecode():
  - ExoAudioCodecSelector.requiresFfmpeg(eac3/ac3/eac3-joc) → empty MediaCodec list
  - CompatFfmpegAudioRenderer handles those formats (FfmpegLibrary supports eac3)
  - Other audio (AAC etc.) unchanged
  - Disable compressed passthrough so E-AC3 is decoded to PCM

Also:
  - Do NOT cache decoder lists when requiresFfmpeg depends on runtime Setting
    (old cache made toggle appear to "not work" until process kill)
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

    # Full reliable body for requiresFfmpeg
    new_req = """    static boolean requiresFfmpeg(String mimeType) {
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

    if "isEac3SoftDecode" in t and "AUDIO_E_AC3" in t:
        print("[mod] ExoAudioCodecSelector requiresFfmpeg already")
    else:
        old = """    static boolean requiresFfmpeg(String mimeType) {
        // The target vendor ALAC codec can report READY without emitting PCM. Keep ALAC on the
        // bundled FFmpeg renderer so decoder progress and AudioTrack initialization are coupled.
        return MimeTypes.AUDIO_ALAC.equals(mimeType);
    }"""
        if old in t:
            t = t.replace(old, new_req, 1)
            print("[mod] ExoAudioCodecSelector requiresFfmpeg eac3")
        else:
            t2, n = re.subn(
                r"static boolean requiresFfmpeg\(String mimeType\)\s*\{.*?\n    \}",
                new_req.rstrip(),
                t,
                count=1,
                flags=re.S,
            )
            if not n:
                print("[mod] FAIL requiresFfmpeg pattern")
                path.write_text(t, encoding="utf-8")
                return
            t = t2
            print("[mod] ExoAudioCodecSelector requiresFfmpeg regex")

    # Cache fix: never return cached list for formats that may switch via Setting
    # Replace getDecoderInfos body cache logic to check requiresFfmpeg BEFORE cache
    old_get = """        Query query = new Query(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        synchronized (cache) {
            List<MediaCodecInfo> cached = cache.get(query);
            if (cached != null) return cached;
            List<MediaCodecInfo> ordered = requiresFfmpeg(mimeType)
                    ? List.of()
                    : orderHardwareFirst(delegate.getDecoderInfos(
                            mimeType,
                            requiresSecureDecoder,
                            requiresTunnelingDecoder));
            cache.put(query, ordered);
            return ordered;
        }"""
    new_get = """        // Setting-dependent soft path: never serve a stale MediaCodec list for AC3 family.
        if (requiresFfmpeg(mimeType)) {
            return List.of();
        }
        Query query = new Query(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        synchronized (cache) {
            List<MediaCodecInfo> cached = cache.get(query);
            if (cached != null) return cached;
            List<MediaCodecInfo> ordered = orderHardwareFirst(delegate.getDecoderInfos(
                            mimeType,
                            requiresSecureDecoder,
                            requiresTunnelingDecoder));
            cache.put(query, ordered);
            return ordered;
        }"""
    if "Setting-dependent soft path" in t:
        print("[mod] ExoAudioCodecSelector cache already fixed")
    elif old_get in t:
        t = t.replace(old_get, new_get, 1)
        print("[mod] ExoAudioCodecSelector cache bypass for requiresFfmpeg")
    else:
        # softer: if requiresFfmpeg already first inside sync, still ok; try insert before Query
        if "if (requiresFfmpeg(mimeType))" not in t.split("getDecoderInfos")[1][:500]:
            t2, n = re.subn(
                r"(public List<MediaCodecInfo> getDecoderInfos\([\s\S]*?\)\s*throws[^{]+\{\s*"
                r"if \(!isAudio\(mimeType\)\) \{\s*"
                r"return delegate\.getDecoderInfos\([\s\S]*?\);\s*"
                r"\}\s*)",
                r"""\1
        if (requiresFfmpeg(mimeType)) {
            return List.of();
        }
""",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] ExoAudioCodecSelector early requiresFfmpeg return")
            else:
                print("[mod] WARN cache pattern not matched; requiresFfmpeg still applied on miss")

    path.write_text(t, encoding="utf-8")


def patch_exo_util(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] ExoUtil missing")
        return
    t = path.read_text(encoding="utf-8")

    # Remove wrong global isAudioPrefer force if present
    bad = """        if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) return true;
"""
    if bad in t:
        t = t.replace(bad, "", 1)
        print("[mod] removed global isAudioPrefer force")

    # Disable passthrough when eac3 soft (decode to PCM)
    if "isEac3SoftDecode()) passthrough" in t or "isEac3SoftDecode() && passthrough" in t:
        print("[mod] ExoUtil passthrough already")
    else:
        # common pattern
        patterns = [
            (
                "        boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);\n",
                "        boolean passthrough = PlayerSetting.isAudioPassThrough(PlayerSetting.EXO);\n"
                "        try {\n"
                "            if (com.fongmi.android.tv.setting.Setting.isEac3SoftDecode()) passthrough = false;\n"
                "        } catch (Throwable ignored) {\n"
                "        }\n",
            ),
        ]
        done = False
        for old_p, new_p in patterns:
            if old_p in t:
                t = t.replace(old_p, new_p, 1)
                print("[mod] ExoUtil disable passthrough when eac3 soft")
                done = True
                break
        if not done:
            print("[mod] WARN passthrough inject skip")

    path.write_text(t, encoding="utf-8")


def main() -> None:
    print("[mod] inject_eac3_soft_audio ROOT=%s" % ROOT)
    patch_selector(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoAudioCodecSelector.java")
    patch_exo_util(ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java")
    # Verify
    sel = ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoAudioCodecSelector.java"
    if sel.exists():
        t = sel.read_text(encoding="utf-8")
        ok = "isEac3SoftDecode" in t and "AUDIO_E_AC3" in t
        print("[mod] verify selector eac3:", "OK" if ok else "FAIL")
        if not ok:
            sys.exit(1)
    print("[mod] inject_eac3_soft_audio done")


if __name__ == "__main__":
    main()
