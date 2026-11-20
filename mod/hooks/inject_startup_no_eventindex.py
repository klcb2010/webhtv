#!/usr/bin/env python3
"""Startup: EventBus without generated EventIndex."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = ROOT / "app/src/main/java/com/fongmi/android/tv/Startup.java"
print("[mod] inject_startup_no_eventindex", path)
if not path.is_file():
    print("[mod] WARN missing Startup.java")
    raise SystemExit(0)
t = path.read_text(encoding="utf-8")
orig = t
t = t.replace("import com.fongmi.android.tv.event.EventIndex;\n", "")
t = t.replace("import com.fongmi.android.tv.event.EventIndex;", "")
t = t.replace(
    "EventBus.builder().addIndex(new EventIndex()).installDefaultEventBus();",
    "EventBus.builder().installDefaultEventBus(); // MOD: no EventIndex (APT optional)",
)
if t != orig:
    path.write_text(t, encoding="utf-8")
    print("[mod] Startup: EventBus without EventIndex")
else:
    print("[mod] Startup: already patched or pattern mismatch")
