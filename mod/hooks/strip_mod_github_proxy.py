#!/usr/bin/env python3
"""
加速源不由 mod 维护。
仅做两件事：
1) 删掉历史上 mod 留下的 utils.GithubProxy / SettingGithubProxyActivity
2) 若 mod 覆盖了 Setting.java，把上游自带的 getUpdateGithubProxy* 从 git 补回，避免编译缺符号
"""
import pathlib, re, subprocess, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

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
        print("[mod] removed obsolete", rel)

setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
if not setting.exists():
    sys.exit(0)

t = setting.read_text(encoding="utf-8")

# 删掉旧版 mod 自己的 API（若还在）
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

# 若缺少上游方法，从 git HEAD 原文件提取补回（不是 mod 实现加速源）
if "getUpdateGithubProxy" not in t:
    r = subprocess.run(
        ["git", "show", "HEAD:app/src/main/java/com/fongmi/android/tv/setting/Setting.java"],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
    )
    if r.returncode == 0 and r.stdout:
        up = r.stdout
        m = re.search(
            r"(public static String getUpdateGithubProxy\(\) \{[\s\S]*?public static void putUpdateGithubProxyMode\(String mode\) \{[\s\S]*?\n    \})",
            up,
        )
        if m:
            if "import com.fongmi.android.tv.update.GithubProxy;" not in t:
                t = t.replace(
                    "package com.fongmi.android.tv.setting;",
                    "package com.fongmi.android.tv.setting;\n\nimport com.fongmi.android.tv.update.GithubProxy;",
                    1,
                )
            t = t.rstrip()
            if t.endswith("}"):
                t = t[:-1] + "\n    " + m.group(1) + "\n}\n"
                print("[mod] restored upstream getUpdateGithubProxy* from git (overlay preserve only)")
        else:
            print("[mod] WARN git Setting has no getUpdateGithubProxy block")
    else:
        print("[mod] WARN cannot git show upstream Setting:", (r.stderr or "")[:200])

setting.write_text(t, encoding="utf-8")
print("[mod] proxy strip done (no mod-owned proxy feature)")
