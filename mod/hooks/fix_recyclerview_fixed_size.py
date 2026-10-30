#!/usr/bin/env python3
"""Lint: InvalidSetHasFixedSize — wrap_content RecyclerView cannot use setHasFixedSize(true)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
path = ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
if not path.exists():
    print("[mod] mobile VideoActivity missing, skip fixed size lint fix")
    sys.exit(0)

text = path.read_text(encoding="utf-8")
# Only the known offenders in error log
patterns = [
    r"mBinding\.flag\.setHasFixedSize\(true\);",
    r"mBinding\.episodeGroup\.setHasFixedSize\(true\);",
    r"mBinding\.quality\.setHasFixedSize\(true\);",
    r"mBinding\.control\.parse\.setHasFixedSize\(true\);",
]
count = 0
for p in patterns:
    text2, n = re.subn(p, lambda m: m.group(0).replace("true", "false"), text)
    if n:
        text = text2
        count += n

# broader safety: any setHasFixedSize(true) on these bindings
text2, n = re.subn(
    r"(mBinding\.(?:flag|episodeGroup|quality|control\.parse)\.setHasFixedSize\()true(\);)",
    r"\1false\2",
    text,
)
if n:
    text = text2
    count = max(count, n)

if count:
    path.write_text(text, encoding="utf-8")
    print(f"[mod] setHasFixedSize true->false x{count}")
else:
    # maybe already false or different formatting
    if "setHasFixedSize(true)" in text:
        # last resort: all true -> false in this file (could be overkill)
        text2, n = re.subn(r"\.setHasFixedSize\(true\)", ".setHasFixedSize(false)", text)
        path.write_text(text2, encoding="utf-8")
        print(f"[mod] setHasFixedSize global true->false x{n}")
    else:
        print("[mod] no setHasFixedSize(true) found")
