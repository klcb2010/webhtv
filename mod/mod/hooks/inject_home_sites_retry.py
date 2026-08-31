#!/usr/bin/env python3
import pathlib, re, sys
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
HOOK = """
    private boolean mVodSitesRetryDone = false;

    private void maybeRetryVodConfigIfEmpty() {
        try {
            if (mVodSitesRetryDone) return;
            java.util.List<?> sites = com.fongmi.android.tv.api.config.VodConfig.get().getSites();
            if (sites != null && !sites.isEmpty()) return;
            mVodSitesRetryDone = true;
            com.fongmi.android.tv.App.post(() -> {
                try {
                    com.fongmi.android.tv.api.config.VodConfig.get().load(getCallback());
                } catch (Throwable ignored) {}
            }, 1200);
        } catch (Throwable ignored) {}
    }
"""

def inject(path: pathlib.Path):
    if not path.exists():
        print("[mod] skip", path)
        return
    t = path.read_text(encoding="utf-8")
    if "maybeRetryVodConfigIfEmpty" in t:
        print("[mod] already", path)
        return
    # try insert call into success callback
    t2, n = re.subn(
        r"(public void success\(\) \{)",
        r"\1\n            maybeRetryVodConfigIfEmpty();",
        t,
        count=1,
    )
    if n == 0:
        t2, n = re.subn(
            r"(void setConfig\(Config config\) \{)",
            r"\1\n        maybeRetryVodConfigIfEmpty();",
            t,
            count=1,
        )
    t = t2
    if t.rstrip().endswith("}"):
        t = t.rstrip()[:-1] + HOOK + "\n}\n"
        path.write_text(t, encoding="utf-8")
        print("[mod] home sites retry", path)
    else:
        print("[mod] WARN", path)

for rel in [
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java",
]:
    inject(ROOT / rel)
