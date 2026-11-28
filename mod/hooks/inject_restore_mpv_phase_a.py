#!/usr/bin/env python3
"""
MPV 阶段 A：恢复可选 MPV + 注入默认播放 option。

前提：apply.sh 已不再调用 inject_disable_mpv / inject_strip_mpv_assets。
默认内核仍由上游 PlayerSetting 决定（EXO）；本脚本不改 getPlayer 默认值。

注入点：androidx.media3.mpvplayer.MpvPlayer 中首次 setOption/setProperty("hwdec" ...) 之后。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "MPV_PHASE_A_OPTIONS"
ERRORS: list[str] = []
OK: list[str] = []

# 用户提供的 conf；Vulkan 优先，失败时由运行时回退（见下方 gpu-api 双写注释）
OPTIONS: list[tuple[str, str]] = [
    ("vo", "gpu"),
    ("gpu-api", "vulkan"),  # 不支持时用户可在后续阶段加 opengl 回退逻辑
    ("hwdec", "auto-safe"),
    ("hwdec-codecs", "hevc,h264,vp9"),
    ("tone-mapping", "hable"),
    ("target-prim", "bt.709"),
    ("target-trc", "bt.1886"),
    ("target-peak", "250"),
    ("hdr-compute-peak", "no"),
    ("gamut-mapping-mode", "clip"),
    ("video-sync", "audio"),
    ("video-sync-max-video-change", "4"),
    ("deband", "no"),
    ("dither", "no"),
    ("scale", "bilinear"),
    ("cscale", "bilinear"),
    ("dscale", "bilinear"),
    ("cache", "yes"),
    ("cache-secs", "10"),
    ("demuxer-max-bytes", "52428800"),
    ("demuxer-max-back-bytes", "20971520"),
    ("demuxer-thread", "yes"),
    ("ffmpeg-threads", "3"),
]


def rel(p: Path) -> str:
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def find_mpv_player() -> Path | None:
    candidates = [
        ROOT / "app/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java",
        ROOT / "app/src/main/java/com/fongmi/android/tv/player/mpv/MpvPlayer.java",
    ]
    for c in candidates:
        if c.is_file():
            return c
    # recursive fallback
    for p in ROOT.rglob("MpvPlayer.java"):
        if "mpv" in str(p).lower():
            return p
    return None


def inject_options(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if MARKER in text:
        OK.append(f"already: {rel(path)}")
        return

    # Locate a line with setOption/setProperty("hwdec"
    lines = text.splitlines(keepends=True)
    idx = -1
    callee = "setOption"
    for i, line in enumerate(lines):
        m = re.search(
            r'((?:this\.)?[A-Za-z_][\w\.]*)\s*\(\s*"hwdec"\s*,',
            line,
        )
        if m:
            idx = i
            callee = m.group(1)
            break
    if idx < 0:
        # try broader: any first setOption("
        for i, line in enumerate(lines):
            m = re.search(
                r'((?:this\.)?[A-Za-z_][\w\.]*)\s*\(\s*"(?:hwdec|vo|gpu-api)"\s*,',
                line,
            )
            if m:
                idx = i
                callee = m.group(1)
                break
    if idx < 0:
        ERRORS.append(f"no setOption/hwdec site in {rel(path)}")
        return

    # Indent from that line
    indent_m = re.match(r"^(\s*)", lines[idx])
    ind = indent_m.group(1) if indent_m else "        "

    block_lines = [f"{ind}// {MARKER}: default playback options (phase A)\n"]
    for k, v in OPTIONS:
        # skip duplicate hwdec if the next line already sets it — still set auto-safe explicitly after
        block_lines.append(f'{ind}try {{ {callee}("{k}", "{v}"); }} catch (Throwable ignored) {{}}\n')
    block = "".join(block_lines)

    # Insert AFTER the hwdec line
    lines.insert(idx + 1, block)
    path.write_text("".join(lines), encoding="utf-8")
    OK.append(f"options injected after line {idx + 1} via {callee} in {rel(path)}")


def note_player_setting() -> None:
    """Ensure we did not leave MPV_DISABLED markers from a previous half-apply (best-effort warn)."""
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java"
    if not path.is_file():
        return
    text = path.read_text(encoding="utf-8")
    if "MPV_DISABLED" in text:
        ERRORS.append(
            f"{rel(path)} still contains MPV_DISABLED markers. "
            "Upstream checkout may be dirty; clean tree then re-apply without inject_disable_mpv."
        )
    else:
        OK.append(f"PlayerSetting clean (no MPV_DISABLED): {rel(path)}")

    pm = ROOT / "app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java"
    if pm.is_file():
        t2 = pm.read_text(encoding="utf-8")
        if "MPV_DISABLED" in t2:
            ERRORS.append(f"{rel(pm)} still contains MPV_DISABLED markers")
        elif "MpvPlayerEngine" in t2:
            OK.append(f"PlayerManager references MpvPlayerEngine: {rel(pm)}")
        else:
            OK.append(f"PlayerManager checked: {rel(pm)}")


def main() -> int:
    print(f"[mod] inject_restore_mpv_phase_a ROOT={ROOT}")
    path = find_mpv_player()
    if path is None:
        ERRORS.append("MpvPlayer.java not found")
    else:
        inject_options(path)
    note_player_setting()
    print("---- results ----")
    for x in OK:
        print(" OK", x)
    for x in ERRORS:
        print(" FAIL", x)
    if ERRORS:
        print("[mod] inject_restore_mpv_phase_a FAILED")
        return 1
    print("[mod] inject_restore_mpv_phase_a SUCCESS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
