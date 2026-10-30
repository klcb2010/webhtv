#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
src = Path(__file__).resolve().parent.parent / "app/src/main/java/com/fongmi/android/tv/player/extractor/Push.java"
dst = ROOT / "app/src/main/java/com/fongmi/android/tv/player/extractor/Push.java"
print("[mod] inject_push_fast_handoff ROOT", ROOT)
if src.is_file():
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
    print("[mod] Push.java", "markHandoff" in dst.read_text(encoding="utf-8"))
else:
    print("[mod] WARN missing Push.java")
