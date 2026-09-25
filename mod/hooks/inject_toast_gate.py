#!/usr/bin/env python3
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
app = ROOT / "app/src/main/java/com/fongmi/android/tv/App.java"
if not app.exists():
    print("[mod] App.java missing")
    sys.exit(0)
t = app.read_text(encoding="utf-8")
orig = t
if "ToastGate" not in t:
    if "import com.fongmi.android.tv.utils.Notify;" in t:
        t = t.replace(
            "import com.fongmi.android.tv.utils.Notify;",
            "import com.fongmi.android.tv.utils.Notify;\nimport com.fongmi.android.tv.utils.ToastGate;",
        )
    else:
        t = t.replace(
            "package com.fongmi.android.tv;",
            "package com.fongmi.android.tv;\n\nimport com.fongmi.android.tv.utils.ToastGate;",
        )
    # after Notify.createChannel();
    if "Notify.createChannel();" in t:
        t = t.replace(
            "Notify.createChannel();",
            "Notify.createChannel();\n        try { ToastGate.install(); } catch (Throwable ignored) {}",
        )
    else:
        t = t.replace(
            "super.onCreate();",
            "super.onCreate();\n        try { ToastGate.install(); } catch (Throwable ignored) {}",
            1,
        )
if t != orig:
    app.write_text(t, encoding="utf-8")
    print("[mod] App ToastGate installed")
else:
    print("[mod] App already has ToastGate or no change")
