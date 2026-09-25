#!/usr/bin/env python3
"""TV/Mobile: retry empty sites; TV: preload home site jar before getVideo."""
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

HOME_JAR_HOOK = """
    private void ensureHomeSiteJarReady() {
        try {
            com.fongmi.android.tv.bean.Site home = com.fongmi.android.tv.api.config.VodConfig.get().getHome();
            if (home == null || home.isEmpty()) return;
            String jar = home.getJar();
            if (jar != null && !jar.isEmpty()) {
                com.fongmi.android.tv.api.loader.BaseLoader.get().parseJar(jar, true);
            }
            try { home.recent(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
"""


def inject_retry(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "maybeRetryVodConfigIfEmpty" not in t:
        t2, n = re.subn(
            r"(public void success\(\) \{)",
            r"\1\n                maybeRetryVodConfigIfEmpty();",
            t,
            count=1,
        )
        t = t2
        if "maybeRetryVodConfigIfEmpty" not in t:
            # append method before last class brace
            pass
        else:
            if RETRY_HOOK.strip() not in t:
                t = t.rstrip()
                if t.endswith("}"):
                    t = t[:-1] + RETRY_HOOK + "\n}\n"
        if "maybeRetryVodConfigIfEmpty" in t and RETRY_HOOK.split("maybeRetry")[0] not in t:
            # method body missing - append
            if "void maybeRetryVodConfigIfEmpty" not in t:
                t = t.rstrip()
                if t.endswith("}"):
                    t = t[:-1] + RETRY_HOOK + "\n}\n"
        path.write_text(t, encoding="utf-8")
        print("[mod] retry", path.relative_to(ROOT))
    else:
        print("[mod] retry already", path.relative_to(ROOT))


def inject_home_jar(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "ensureHomeSiteJarReady" not in t:
        # inject call at start of getVideo(boolean
        t2, n = re.subn(
            r"(private void getVideo\(boolean[^{]*\{\s*)",
            r"\1ensureHomeSiteJarReady();\n        ",
            t,
            count=1,
        )
        if n == 0:
            print("[mod] WARN no getVideo(boolean)", path)
            return
        t = t2
        if "void ensureHomeSiteJarReady" not in t:
            t = t.rstrip()
            if t.endswith("}"):
                t = t[:-1] + HOME_JAR_HOOK + "\n}\n"
        path.write_text(t, encoding="utf-8")
        print("[mod] home jar", path.relative_to(ROOT))
    else:
        print("[mod] home jar already", path.relative_to(ROOT))


inject_retry(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
inject_retry(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
inject_home_jar(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
# mobile may not have getVideo(boolean) - optional
inject_home_jar(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java")
print("[mod] home sites + jar done")
