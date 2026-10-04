#!/usr/bin/env python3
"""LiveActivity: if Setting.isLiveDefaultFullscreen(), hide channel list UI on enter."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

PATCH_SNIPPET = """
        try {
            if (com.fongmi.android.tv.setting.Setting.isLiveDefaultFullscreen()) {
                App.post(this::hideUI, 300);
            }
        } catch (Throwable ignored) {
        }
"""

def patch_file(path: pathlib.Path) -> bool:
    if not path.exists():
        return False
    t = path.read_text(encoding="utf-8")
    if "isLiveDefaultFullscreen" in t:
        print(f"[mod] live fullscreen already {path}")
        return True
    # After checkLive(); in initView is ideal
    if "checkLive();" in t:
        t2 = t.replace("checkLive();", "checkLive();" + PATCH_SNIPPET, 1)
        if t2 != t:
            path.write_text(t2, encoding="utf-8")
            print(f"[mod] live fullscreen patched after checkLive {path}")
            return True
    # Fallback: end of initView before closing
    m = re.search(r"(protected void initView\(Bundle[^{]*\{)([\s\S]*?)(\n    @Override|\n    private |\n    public )", t)
    if m:
        body = m.group(2)
        if "checkLive" in body or "setVideoView" in body:
            insert_at = m.start(3)
            t2 = t[:insert_at] + PATCH_SNIPPET + t[insert_at:]
            path.write_text(t2, encoding="utf-8")
            print(f"[mod] live fullscreen patched initView end {path}")
            return True
    print(f"[mod] WARN live fullscreen no insert point {path}")
    return False

ok = False
for rel in [
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
]:
    if patch_file(ROOT / rel):
        ok = True
if not ok:
    print("[mod] WARN LiveActivity not found (upstream only) — hook no-op this build")
