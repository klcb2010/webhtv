#!/usr/bin/env python3
"""Remove all obsolete mod github-proxy leftovers. Do not implement proxy."""
import pathlib, re, subprocess, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

# 1) delete obsolete files
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

# 2) Setting: remove obsolete mod APIs only
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

# 3) AboutDialog: restore from git if it still references obsolete APIs
about = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
if about.exists():
    t = about.read_text(encoding="utf-8")
    dirty = any(
        x in t
        for x in [
            "getGithubProxyEnabled",
            "putGithubProxyEnabled",
            "setting_github_proxy_short",
            "SettingGithubProxyActivity",
            "binding.accelerate",
        ]
    )
    if dirty:
        r = subprocess.run(
            ["git", "show", "HEAD:app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
        )
        if r.returncode == 0 and r.stdout and "getGithubProxyEnabled" not in r.stdout:
            about.write_text(r.stdout, encoding="utf-8")
            print("[mod] restored AboutDialog.java from git HEAD")
        else:
            # surgical strip: remove accelerate-related blocks
            t2 = t
            # long click / click handlers using putGithubProxyEnabled
            t2 = re.sub(
                r"\n[ \t]*binding\.accelerate[\s\S]*?;\n",
                "\n",
                t2,
            )
            t2 = re.sub(
                r"\n[ \t]*//.*[Aa]ccelerat[\s\S]*?(?=\n[ \t]*[a-zA-Z@/])",
                "\n",
                t2,
                count=3,
            )
            # remove methods refreshAccelerate / setAccelerate if any
            for name in ["refreshAccelerate", "setAccelerate", "updateAccelerate"]:
                t2, _ = re.subn(
                    r"\n[ \t]*private void " + name + r"\([^)]*\) \{[\s\S]*?\n[ \t]*\}\n",
                    "\n",
                    t2,
                )
            # remove putGithubProxyEnabled lines
            t2 = re.sub(r"[^\n]*GithubProxyEnabled[^\n]*\n", "", t2)
            t2 = re.sub(r"[^\n]*setting_github_proxy[^\n]*\n", "", t2)
            about.write_text(t2, encoding="utf-8")
            print("[mod] surgically cleaned AboutDialog.java")

# 4) layout about dialog: if accelerate view only from mod, leave upstream layout via git if needed
for rel in [
    "app/src/main/res/layout/dialog_about.xml",
    "app/src/mobile/res/layout/dialog_about.xml",
    "app/src/leanback/res/layout/dialog_about.xml",
]:
    p = ROOT / rel
    if not p.exists():
        continue
    txt = p.read_text(encoding="utf-8", errors="ignore")
    if 'android:id="@+id/accelerate"' in txt or "id/accelerate" in txt:
        r = subprocess.run(
            ["git", "show", f"HEAD:{rel}"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
        )
        if r.returncode == 0 and r.stdout and "accelerate" not in r.stdout:
            p.write_text(r.stdout, encoding="utf-8")
            print("[mod] restored", rel, "from git")
        elif r.returncode == 0 and r.stdout:
            # upstream also has accelerate - keep git version
            p.write_text(r.stdout, encoding="utf-8")
            print("[mod] restored", rel, "from git (upstream)")

print("[mod] proxy cleanup done")
