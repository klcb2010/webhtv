#!/usr/bin/env python3
import pathlib, sys
ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
if setting.exists():
    t = setting.read_text(encoding="utf-8")
    if "isGithubProxyEnabled" in t and "getGithubProxyEnabled" not in t:
        t = t.replace(
            "public static boolean isGithubProxyEnabled()",
            "public static boolean getGithubProxyEnabled() { return isGithubProxyEnabled(); }\n\n    public static boolean isGithubProxyEnabled()",
        )
        setting.write_text(t, encoding="utf-8")
        print("[mod] getGithubProxyEnabled alias")
for rel, on, off in [
    ("app/src/main/res/values/strings.xml", "On", "Off"),
    ("app/src/main/res/values-zh-rCN/strings.xml", "开", "关"),
]:
    p = ROOT / rel
    if not p.exists():
        continue
    s = p.read_text(encoding="utf-8")
    changed = False
    for name, val in [("setting_github_proxy_on", on), ("setting_github_proxy_off", off)]:
        if f'name="{name}"' not in s:
            s = s.replace("</resources>", f'    <string name="{name}">{val}</string>\n</resources>')
            changed = True
    if changed:
        p.write_text(s, encoding="utf-8")
        print("[mod] proxy on/off strings", rel)
