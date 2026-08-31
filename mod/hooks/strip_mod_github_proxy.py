#!/usr/bin/env python3
"""Remove obsolete mod-only github proxy Setting APIs (upstream uses update.GithubProxy)."""
import pathlib, re, sys
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
if setting.exists():
    t = setting.read_text(encoding="utf-8")
    orig = t
    for name in [
        "isGithubProxyEnabled",
        "getGithubProxyEnabled",
        "putGithubProxyEnabled",
        "getGithubProxy",
        "putGithubProxy",
    ]:
        t, _ = re.subn(
            r"\n[ \t]*public static [^{\n]*\b" + name + r"\b[^{]*\{[\s\S]*?\n[ \t]*\}\n",
            "\n",
            t,
        )
    t = re.sub(r"\n[ \t]*private static final String GITHUB_PROXY_ENABLED[^\n]*\n", "\n", t)
    t = re.sub(r"\n[ \t]*private static final String GITHUB_PROXY[^\n]*\n", "\n", t)
    t = t.replace("getPreferences().getBoolean", "Prefers.getBoolean")
    if t != orig:
        setting.write_text(t, encoding="utf-8")
        print("[mod] removed obsolete Setting github proxy APIs")
    else:
        print("[mod] Setting no obsolete github proxy APIs")

for rel in [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/main/java/com/fongmi/android/tv/utils/GithubProxy.java",
]:
    p = ROOT / rel
    if p.exists():
        # only delete utils.GithubProxy (obsolete); upstream is update.GithubProxy
        if "utils/GithubProxy" in rel.replace("\\", "/"):
            p.unlink(missing_ok=True)
            print("[mod] deleted obsolete", rel)
        elif "SettingGithubProxyActivity" in rel:
            p.unlink(missing_ok=True)
            print("[mod] deleted obsolete", rel)
