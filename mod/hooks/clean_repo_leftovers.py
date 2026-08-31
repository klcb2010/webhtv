#!/usr/bin/env python3
"""Delete legacy proxy leftovers from the repo workspace. Not a proxy feature."""
import pathlib, re, sys
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
for rel in [
    "app/src/main/java/com/fongmi/android/tv/utils/GithubProxy.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingGithubProxyActivity.java",
    "app/src/mobile/res/layout/activity_setting_github_proxy.xml",
    "app/src/leanback/res/layout/activity_setting_github_proxy.xml",
    "app/src/main/res/layout/activity_setting_github_proxy.xml",
]:
    p = ROOT / rel
    if p.exists():
        p.unlink()
        print("[mod] deleted leftover", rel)
for p in ROOT.rglob("dialog_about.xml"):
    t = p.read_text(encoding="utf-8")
    if "accelerate" not in t and "setting_github_proxy" not in t:
        continue
    t2 = re.sub(
        r'\n[ \t]*<(?P<tag>\w+(?:\.\w+)*)\b[^>]*android:id="@\+id/accelerate"[\s\S]*?(?:/>|</(?P=tag)>)',
        "",
        t,
        count=5,
    )
    t2 = t2.replace("@id/accelerate", "@id/confirm")
    if t2 != t:
        p.write_text(t2, encoding="utf-8")
        print("[mod] cleaned", p.relative_to(ROOT))
for manifest in ROOT.rglob("AndroidManifest.xml"):
    if "app/src" not in str(manifest).replace("\\", "/"):
        continue
    t = manifest.read_text(encoding="utf-8")
    if "SettingGithubProxyActivity" not in t:
        continue
    t2 = re.sub(r"\n[ \t]*<activity\b[^>]*SettingGithubProxyActivity[^>]*/>", "", t)
    t2 = re.sub(r"\n[ \t]*<activity\b[^>]*SettingGithubProxyActivity[\s\S]*?</activity>", "", t2)
    if t2 != t:
        manifest.write_text(t2, encoding="utf-8")
        print("[mod] cleaned manifest", manifest.relative_to(ROOT))
