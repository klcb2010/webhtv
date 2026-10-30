#!/usr/bin/env python3
"""Ensure Setting update stubs and Update.fallbackApkUrl after mod overlay."""
import pathlib
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

upd = ROOT / "app/src/main/java/com/fongmi/android/tv/bean/Update.java"
if upd.exists():
    t = upd.read_text(encoding="utf-8")
    if "fallbackApkUrl" not in t:
        if "public String apkUrl;" in t:
            t = t.replace(
                "public String apkUrl;",
                "public String apkUrl;\n    public String fallbackApkUrl;",
                1,
            )
        elif "public String apk;" in t:
            t = t.replace(
                "public String apk;",
                "public String apk;\n    public String fallbackApkUrl;",
                1,
            )
        upd.write_text(t, encoding="utf-8")
        print("[mod] added Update.fallbackApkUrl")
    else:
        print("[mod] Update.fallbackApkUrl ok")

setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
if setting.exists():
    t = setting.read_text(encoding="utf-8")
    if "getUpdateSource" in t and "putUpdateOciMirrorUrl" in t:
        print("[mod] Setting update stubs ok")
    else:
        print("[mod] WARN Setting missing update stubs (should be in mod Setting.java)")
