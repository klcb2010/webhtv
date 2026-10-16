#!/usr/bin/env python3
"""Fix mixed upstream state: ExoDv5GpuRenderer calls removeDolbyVisionCsd missing on older factory."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
path = ROOT / "app/src/main/java/com/fongmi/android/tv/player/exo/ExoDv5GpuRenderer.java"
if not path.exists():
    print("[mod] ExoDv5GpuRenderer missing, skip")
    sys.exit(0)
text = path.read_text(encoding="utf-8")
if "removeDolbyVisionCsd" not in text:
    print("[mod] no removeDolbyVisionCsd usage, skip")
    sys.exit(0)

# Replace the method call with identity / safe filter inline
old = re.compile(
    r"\.setInitializationData\(\s*DolbyVisionP81ExtractorsFactory\.removeDolbyVisionCsd\(\s*format\.initializationData\)\s*\)",
    re.S,
)
new = ".setInitializationData(format.initializationData)"
text2, n = old.subn(new, text)
if n:
    path.write_text(text2, encoding="utf-8")
    print(f"[mod] patched ExoDv5GpuRenderer asHevc x{n}")
else:
    # broader
    text2 = text.replace(
        "DolbyVisionP81ExtractorsFactory.removeDolbyVisionCsd(\n                                format.initializationData)",
        "format.initializationData",
    )
    text2 = text2.replace(
        "DolbyVisionP81ExtractorsFactory.removeDolbyVisionCsd(format.initializationData)",
        "format.initializationData",
    )
    if text2 != text:
        path.write_text(text2, encoding="utf-8")
        print("[mod] patched ExoDv5GpuRenderer (fallback)")
    else:
        print("[mod] WARN could not patch ExoDv5GpuRenderer")
