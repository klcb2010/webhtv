#!/usr/bin/env python3
"""TV/Mobile: retry empty Vod sites after config load. (home jar preload removed — CSP warmup is the real path)"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

RETRY_HOOK = """
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


def inject_retry(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    # Strip obsolete ensureHomeSiteJarReady if a previous build left it in tree
    if "ensureHomeSiteJarReady" in t:
        t = re.sub(
            r"\s*ensureHomeSiteJarReady\(\);\s*\n",
            "\n",
            t,
        )
        t = re.sub(
            r"\n\s*private void ensureHomeSiteJarReady\(\) \{.*?\n\s*\}\n",
            "\n",
            t,
            count=1,
            flags=re.DOTALL,
        )
        path.write_text(t, encoding="utf-8")
        print("[mod] removed obsolete ensureHomeSiteJarReady", path.relative_to(ROOT))
        t = path.read_text(encoding="utf-8")

    if "maybeRetryVodConfigIfEmpty" not in t:
        t2, n = re.subn(
            r"(public void success\(\) \{)",
            r"\1\n                maybeRetryVodConfigIfEmpty();",
            t,
            count=1,
        )
        t = t2
        if "void maybeRetryVodConfigIfEmpty" not in t:
            t = t.rstrip()
            if t.endswith("}"):
                t = t[:-1] + RETRY_HOOK + "\n}\n"
        path.write_text(t, encoding="utf-8")
        print("[mod] retry", path.relative_to(ROOT))
    else:
        if "void maybeRetryVodConfigIfEmpty" not in t:
            t = t.rstrip()
            if t.endswith("}"):
                t = t[:-1] + RETRY_HOOK + "\n}\n"
            path.write_text(t, encoding="utf-8")
            print("[mod] retry method body", path.relative_to(ROOT))
        else:
            print("[mod] retry already", path.relative_to(ROOT))


inject_retry(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
inject_retry(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
print("[mod] home sites retry done (no jar preload)")
