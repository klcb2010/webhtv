#!/usr/bin/env python3
"""Fix DefaultTrackSelector Context warning (Media3).

Root cause analysis:
- Warning only logs when DefaultTrackSelector is constructed with context == null
  (or via the deprecated Parameters+Factory constructor that forces context=null).
- Upstream/app already call new DefaultTrackSelector(App.get()).
- FongMi Media3 alpha forks have been known to route the 1-arg Context constructor
  incorrectly onto the deprecated no-Context path; using the explicit
  (Context, Parameters) constructor avoids that.
- When constraints cannot apply (context null), they are already a no-op; we only
  disable constrainAudioChannelCountToDeviceCapabilities in that null case so
  behavior matches reality and the warning stops. We do NOT force stereo.

SpeakerLayoutUtil "defaulting to stereo" is a separate Media3 device-capability
fallback when getSpeakerLayoutChannelMask is unusable — not modified here.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
EXO = ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoUtil.java"

# Match upstream one-liner construction inside buildTrackSelector
OLD_LINE = "DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());"

NEW_BLOCK = """android.content.Context __tsCtx = App.get();
        if (__tsCtx != null) {
            try { __tsCtx = __tsCtx.getApplicationContext(); } catch (Throwable ignored) {}
        }
        // Prefer (Context, Parameters) so Media3 keeps a non-null context even if the
        // 1-arg constructor is broken in a forked Media3 build.
        DefaultTrackSelector trackSelector;
        if (__tsCtx != null) {
            trackSelector = new DefaultTrackSelector(
                    __tsCtx, DefaultTrackSelector.Parameters.getDefaults(__tsCtx));
        } else {
            trackSelector = new DefaultTrackSelector(App.get());
        }"""


def main() -> None:
    if not EXO.exists():
        print("[mod] skip missing ExoUtil")
        return
    t = EXO.read_text(encoding="utf-8")
    if "Parameters.getDefaults(__tsCtx)" in t or "Parameters.getDefaults(__tsCtx)" in t:
        print("[mod] DefaultTrackSelector already uses Parameters.getDefaults")
        # still ensure null-context constrain off
    elif "Parameters.getDefaults" in t and "buildTrackSelector" in t:
        print("[mod] Parameters.getDefaults already present in ExoUtil")
    elif OLD_LINE in t:
        t = t.replace(OLD_LINE, NEW_BLOCK, 1)
        print("[mod] DefaultTrackSelector -> Context + Parameters.getDefaults")
    else:
        # previous soft harden from 0151
        soft = re.compile(
            r"android\.content\.Context __tsCtx = App\.get\(\);\s*"
            r"if \(__tsCtx != null\) \{\s*"
            r"try \{ __tsCtx = __tsCtx\.getApplicationContext\(\); \} catch \(Throwable ignored\) \{\}\s*"
            r"\}\s*"
            r"DefaultTrackSelector trackSelector = __tsCtx != null\s*"
            r"\? new DefaultTrackSelector\(__tsCtx\)\s*"
            r": new DefaultTrackSelector\(App\.get\(\)\);",
            re.S,
        )
        if soft.search(t):
            t = soft.sub(NEW_BLOCK, t, count=1)
            print("[mod] upgraded previous Context harden to Parameters.getDefaults")
        else:
            t2, n = re.subn(
                r"DefaultTrackSelector trackSelector = new DefaultTrackSelector\(App\.get\(\)\);",
                NEW_BLOCK,
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] DefaultTrackSelector soft replace")
            else:
                print("[mod] WARN buildTrackSelector constructor not found")
                return

    # After builder is created, if context was null, constraints cannot apply — disable to silence warn
    # without changing behavior (they were already ineffective).
    guard = (
        "if (__tsCtx == null) {\n"
        "            try { builder.setConstrainAudioChannelCountToDeviceCapabilities(false); } catch (Throwable ignored) {}\n"
        "        }\n"
    )
    if "setConstrainAudioChannelCountToDeviceCapabilities(false)" not in t:
        # insert right after builder is obtained in buildTrackSelector
        needle = "DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();"
        if needle in t:
            t = t.replace(
                needle,
                needle + "\n        " + guard.rstrip(),
                1,
            )
            print("[mod] null-context: constrainAudioChannelCount disabled (already no-op)")
        else:
            print("[mod] WARN builder line not found for constrain guard")

    EXO.write_text(t, encoding="utf-8")
    print("[mod] inject_track_selector_context done")


if __name__ == "__main__":
    main()
