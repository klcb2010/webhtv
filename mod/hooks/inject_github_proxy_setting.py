#!/usr/bin/env python3
"""Inject GithubProxy Setting prefs + Updater apply + AboutDialog entry."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

# --- Setting.java ---
setting = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
if setting.exists():
    t = setting.read_text(encoding="utf-8")
    
    # 清理之前可能注入错误的 getPreferences() 方法片段
    t = re.sub(r"\s*private static final String GITHUB_PROXY_ENABLED = \"github_proxy_enabled\";", "", t)
    t = re.sub(r"\s*public static boolean getGithubProxyEnabled\(\)[\s\S]*?\}\n", "", t)
    t = re.sub(r"\s*public static void putGithubProxyEnabled\(boolean value\)[\s\S]*?\}\n", "", t)

    # 重新判断并注入标准的 Prefers 方法
    if "getGithubProxy" not in t:
        block = '''
    public static String getGithubProxy() {
        return com.fongmi.android.tv.utils.Prefers.getString("github_proxy", com.fongmi.android.tv.utils.GithubProxy.defaultSources());
    }

    public static void putGithubProxy(String value) {
        com.fongmi.android.tv.utils.Prefers.put("github_proxy", com.fongmi.android.tv.utils.GithubProxy.normalizeConfig(value));
    }

    public static boolean isGithubProxyEnabled() {
        return com.fongmi.android.tv.utils.Prefers.getBoolean("github_proxy_enabled", true);
    }

    public static boolean getGithubProxyEnabled() {
        return isGithubProxyEnabled();
    }

    public static void putGithubProxyEnabled(boolean enabled) {
        com.fongmi.android.tv.utils.Prefers.put("github_proxy_enabled", enabled);
    }
'''
        t = t.rstrip()
        if t.endswith("}"):
            t = t[:-1] + block + "\n}\n"
            setting.write_text(t, encoding="utf-8")
            print("[mod] Setting github proxy methods added")
    else:
        print("[mod] Setting github proxy already present")

# --- Updater.java ---
updater = ROOT / "app/src/main/java/com/fongmi/android/tv/Updater.java"
if updater.exists():
    t = updater.read_text(encoding="utf-8")
    if "GithubProxy" not in t:
        if "import com.fongmi.android.tv.utils.Github;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.utils.Github;",
                "import com.fongmi.android.tv.utils.Github;\nimport com.fongmi.android.tv.utils.GithubProxy;",
            )
        else:
            t = t.replace(
                "package com.fongmi.android.tv;",
                "package com.fongmi.android.tv;\n\nimport com.fongmi.android.tv.utils.GithubProxy;",
            )
        t = re.sub(
            r"private void startDownload\(String url\) \{\s*download = Download\.create\(url,",
            "private void startDownload(String url) {\n        url = GithubProxy.apply(url);\n        download = Download.create(url,",
            t,
            count=1,
        )
        t = t.replace("OkHttp.string(manifestUrl,", "OkHttp.string(GithubProxy.apply(manifestUrl),")
        t = t.replace(
            "OkHttp.string(Github.getReleaseApi(tag),",
            "OkHttp.string(GithubProxy.apply(Github.getReleaseApi(tag)),",
        )
        t = t.replace(
            "OkHttp.string(Github.getLatestReleaseApi(),",
            "OkHttp.string(GithubProxy.apply(Github.getLatestReleaseApi()),",
        )
        t = t.replace(
            "OkHttp.string(Github.getReleasesApi(),",
            "OkHttp.string(GithubProxy.apply(Github.getReleasesApi()),",
        )
        updater.write_text(t, encoding="utf-8")
        print("[mod] Updater GithubProxy wired")
    else:
        print("[mod] Updater already has GithubProxy")

# --- AboutDialog ---
about = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
if about.exists():
    t = about.read_text(encoding="utf-8")
    if "SettingGithubProxyActivity" in t or "proxyBtn" in t:
        t = re.sub(
            r"\s*binding\.checkUpdate\.setOnLongClickListener\([\s\S]*?return true;\s*\}\);",
            "",
            t,
            count=1,
        )
        t = re.sub(
            r"\s*try \{\s*android\.view\.ViewGroup parent[\s\S]*?catch \(Throwable ignored\) \{\s*\}\s*",
            "",
            t,
            count=1,
        )

    if "SettingGithubProxyActivity" not in t:
        marker = "binding.checkUpdate.setOnClickListener(v -> {\n            dialog.dismiss();\n            if (updateAction != null) updateAction.run();\n        });"
        if marker not in t:
            marker = None
            m = re.search(
                r"binding\.checkUpdate\.setOnClickListener\(v -> \{[\s\S]*?\}\);",
                t,
            )
            if m:
                marker = m.group(0)

        if marker:
            insert = marker + '''
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) binding.checkUpdate.getParent();
            if (parent != null) {
                android.view.ViewGroup grand = parent.getParent() instanceof android.view.ViewGroup
                        ? (android.view.ViewGroup) parent.getParent() : null;
                com.google.android.material.button.MaterialButton proxyBtn =
                        new com.google.android.material.button.MaterialButton(activity);
                proxyBtn.setText(com.fongmi.android.tv.R.string.setting_github_proxy_short);
                try {
                    proxyBtn.setFocusable(true);
                    proxyBtn.setFocusableInTouchMode(false);
                    proxyBtn.setSelected(false);
                    proxyBtn.clearFocus();
                } catch (Throwable ignored) {}
                proxyBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    com.fongmi.android.tv.ui.activity.SettingGithubProxyActivity.start(activity);
                });
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (10 * activity.getResources().getDisplayMetrics().density);
                proxyBtn.setLayoutParams(lp);
                if (grand != null && grand.indexOfChild(parent) >= 0) {
                    grand.addView(proxyBtn, grand.indexOfChild(parent) + 1);
                } else {
                    parent.addView(proxyBtn);
                }
            }
        } catch (Throwable ignored) {}
'''
            t = t.replace(marker, insert, 1)
            about.write_text(t, encoding="utf-8")
            print("[mod] AboutDialog proxy second-row button added")
        else:
            print("[mod] WARN AboutDialog checkUpdate pattern not found")
    else:
        about.write_text(t, encoding="utf-8")
        print("[mod] AboutDialog cleaned previous inject")