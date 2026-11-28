#!/usr/bin/env python3
"""HDR→SDR OpenGL tone mapping (default OFF). Safe no-op when disabled.

Rollback: remove this script call from apply.sh and delete:
  - app/.../HdrToSdrHelper.java
  - mod copy of ExoRuntimeAwareVideoRenderer.java (or restore upstream)
  - Setting isHdrToSdr* / UI rows
"""
from __future__ import annotations

import sys
from pathlib import Path

MARKER = "HDR_TO_SDR_MOD"


def patch_engine(root: Path) -> None:
    path = root / "app/src/main/java/com/fongmi/android/tv/player/engine/ExoPlayerEngine.java"
    if not path.is_file():
        print("[mod] inject_hdr_to_sdr SKIP no ExoPlayerEngine")
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] inject_hdr_to_sdr ExoPlayerEngine already")
        return

    # Merge effects when setting on
    old = """    public void setVideoEffects(List<Effect> effects) {
        if (SpiderDebug.isEnabled()) {
            Format format = getVideoFormat();
            SpiderDebug.log("lut-exo", "set effects=%d state=%d position=%d video=%s",
                    effects == null ? 0 : effects.size(),
                    player.getPlaybackState(),
                    player.getCurrentPosition(),
                    format == null ? "unknown" : format.width + "x" + format.height + "/" + format.sampleMimeType);
        }
        player.setVideoEffects(effects);
        com.fongmi.android.tv.player.exo.ExoDiagnosticCollector.effects(player, effects);
    }"""
    new = """    public void setVideoEffects(List<Effect> effects) {
        // HDR_TO_SDR_MOD: 开启时保证 VideoGraph 路径存在，便于 requestOpenGlToneMapping 生效
        try {
            effects = com.fongmi.android.tv.player.exo.HdrToSdrHelper.mergeEffects(effects);
        } catch (Throwable ignored) {
        }
        if (SpiderDebug.isEnabled()) {
            Format format = getVideoFormat();
            SpiderDebug.log("lut-exo", "set effects=%d state=%d position=%d video=%s",
                    effects == null ? 0 : effects.size(),
                    player.getPlaybackState(),
                    player.getCurrentPosition(),
                    format == null ? "unknown" : format.width + "x" + format.height + "/" + format.sampleMimeType);
        }
        player.setVideoEffects(effects);
        com.fongmi.android.tv.player.exo.ExoDiagnosticCollector.effects(player, effects);
    }"""
    if old not in t:
        print("[mod] inject_hdr_to_sdr WARN setVideoEffects pattern mismatch, try loose")
        if "player.setVideoEffects(effects);" in t and MARKER not in t:
            t = t.replace(
                "player.setVideoEffects(effects);",
                "try { effects = com.fongmi.android.tv.player.exo.HdrToSdrHelper.mergeEffects(effects); } catch (Throwable ignored) {}\n"
                "        // HDR_TO_SDR_MOD\n"
                "        player.setVideoEffects(effects);",
                1,
            )
        else:
            print("[mod] inject_hdr_to_sdr FAIL setVideoEffects")
            sys.exit(1)
    else:
        t = t.replace(old, new, 1)

    # After first buildPlayer assignment, ensure effects path once
    needle = "this.player = ExoUtil.buildPlayer("
    if needle in t and "HdrToSdrHelper.ensureEffectsPath" not in t:
        # find semicolon end of statement - multi-line call
        idx = t.find(needle)
        # find ");" after idx that closes buildPlayer - rough: next ");\n" at indent
        sub = t[idx:]
        # find end of statement
        depth = 0
        end = None
        for i, ch in enumerate(sub):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    # expect ;
                    j = i + 1
                    while j < len(sub) and sub[j] in " \t":
                        j += 1
                    if j < len(sub) and sub[j] == ";":
                        end = idx + j + 1
                        break
        if end:
            insert = (
                "\n            try { // HDR_TO_SDR_MOD\n"
                "                com.fongmi.android.tv.player.exo.HdrToSdrHelper.ensureEffectsPath(this);\n"
                "            } catch (Throwable ignored) {\n"
                "            }"
            )
            t = t[:end] + insert + t[end:]
            print("[mod] inject_hdr_to_sdr ensureEffectsPath after first buildPlayer")
        else:
            print("[mod] inject_hdr_to_sdr WARN cannot find buildPlayer end")

    path.write_text(t, encoding="utf-8")
    print("[mod] inject_hdr_to_sdr ExoPlayerEngine OK")


def verify_helper(root: Path) -> None:
    p = root / "app/src/main/java/com/fongmi/android/tv/player/exo/HdrToSdrHelper.java"
    if not p.is_file():
        print("[mod] inject_hdr_to_sdr FAIL missing HdrToSdrHelper")
        sys.exit(1)
    r = root / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoRuntimeAwareVideoRenderer.java"
    if r.is_file() and "setRequestOpenGlToneMapping" not in r.read_text(encoding="utf-8"):
        print("[mod] inject_hdr_to_sdr FAIL renderer missing tone map hook")
        sys.exit(1)


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    print(f"[mod] inject_hdr_to_sdr ROOT={root}")
    verify_helper(root)
    patch_engine(root)
    print("[mod] inject_hdr_to_sdr SUCCESS")


if __name__ == "__main__":
    main()
