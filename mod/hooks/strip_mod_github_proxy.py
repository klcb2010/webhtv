#!/usr/bin/env python3
"""Remove obsolete mod github-proxy leftovers."""
import pathlib, re, shutil, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
MOD = pathlib.Path(__file__).resolve().parent.parent

for rel in [
    "app/src/main/java/com/fongmi/android/tv/utils/GithubProxy.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/mobile/res/layout/activity_setting_github_proxy.xml",
    "app/src/leanback/res/layout/activity_setting_github_proxy.xml",
]:
    p = ROOT / rel
    if p.exists():
        p.unlink(missing_ok=True)
        print("[mod] removed", rel)

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
    if t != orig:
        setting.write_text(t, encoding="utf-8")
        print("[mod] stripped obsolete Setting github APIs")

about = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
clean = MOD / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
if about.exists():
    t = about.read_text(encoding="utf-8")
    dirty = any(x in t for x in [
        "getGithubProxyEnabled",
        "putGithubProxyEnabled",
        "setting_github_proxy_short",
        "SettingGithubProxyActivity",
        "binding.accelerate",
    ])
    if dirty and clean.exists():
        shutil.copy2(clean, about)
        print("[mod] replaced dirty AboutDialog with clean copy from mod")
    elif dirty:
        # surgical: remove lines with obsolete symbols
        lines = []
        for line in t.splitlines(keepends=True):
            if any(x in line for x in [
                "GithubProxyEnabled",
                "setting_github_proxy",
                "binding.accelerate",
                "SettingGithubProxyActivity",
            ]):
                continue
            lines.append(line)
        about.write_text("".join(lines), encoding="utf-8")
        print("[mod] surgically cleaned AboutDialog lines")
    else:
        print("[mod] AboutDialog already clean")

print("[mod] proxy cleanup done")
