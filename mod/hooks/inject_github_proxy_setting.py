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
    if "getGithubProxy" not in t:
        block = '''
    public static String getGithubProxy() {
        return Prefers.getString("github_proxy", com.fongmi.android.tv.utils.GithubProxy.defaultSources());
    }

    public static void putGithubProxy(String value) {
        Prefers.put("github_proxy", com.fongmi.android.tv.utils.GithubProxy.normalizeConfig(value));
    }

    public static boolean isGithubProxyEnabled() {
        return Prefers.getBoolean("github_proxy_enabled", true);
    }

    public static void putGithubProxyEnabled(boolean enabled) {
        Prefers.put("github_proxy_enabled", enabled);
    }
'''
        # insert before last closing brace of class
        t = t.rstrip()
        if t.endswith("}"):
            t = t[:-1] + block + "\n}\n"
            setting.write_text(t, encoding="utf-8")
            print("[mod] Setting github proxy methods added")
        else:
            print("[mod] WARN Setting.java structure unexpected")
    else:
        print("[mod] Setting github proxy already present")

# --- Updater.java: apply GithubProxy on network URLs ---
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
        # wrap startDownload url
        t = re.sub(
            r"private void startDownload\(String url\) \{\s*download = Download\.create\(url,",
            "private void startDownload(String url) {\n        url = GithubProxy.apply(url);\n        download = Download.create(url,",
            t,
            count=1,
        )
        # common OkHttp.string(manifestUrl patterns
        t = t.replace(
            "OkHttp.string(manifestUrl,",
            "OkHttp.string(GithubProxy.apply(manifestUrl),",
        )
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

# --- AboutDialog: add proxy button programmatically ---
about = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
if about.exists():
    t = about.read_text(encoding="utf-8")
    if "SettingGithubProxyActivity" not in t and "githubProxy" not in t:
        # after checkUpdate setOnClickListener block, add long-click on checkUpdate or version to open proxy
        # and a visible path: long press checkUpdate opens proxy; also short add if possible
        if "binding.checkUpdate.setOnClickListener" in t:
            t = t.replace(
                "binding.checkUpdate.setOnClickListener(v -> {\n            dialog.dismiss();\n            if (updateAction != null) updateAction.run();\n        });",
                '''binding.checkUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            if (updateAction != null) updateAction.run();
        });
        binding.checkUpdate.setOnLongClickListener(v -> {
            dialog.dismiss();
            try {
                com.fongmi.android.tv.ui.activity.SettingGithubProxyActivity.start(activity);
            } catch (Throwable ignored) {
            }
            return true;
        });
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) binding.checkUpdate.getParent();
            if (parent != null) {
                com.google.android.material.button.MaterialButton proxyBtn =
                        new com.google.android.material.button.MaterialButton(activity);
                proxyBtn.setText(com.fongmi.android.tv.R.string.setting_github_proxy_short);
                proxyBtn.setOnClickListener(v -> {
                    dialog.dismiss();
                    com.fongmi.android.tv.ui.activity.SettingGithubProxyActivity.start(activity);
                });
                int idx = parent.indexOfChild(binding.checkUpdate);
                parent.addView(proxyBtn, idx + 1);
            }
        } catch (Throwable ignored) {
        }''',
            )
            about.write_text(t, encoding="utf-8")
            print("[mod] AboutDialog proxy entry added")
        else:
            print("[mod] WARN AboutDialog pattern not found")
    else:
        print("[mod] AboutDialog already patched")
else:
    print("[mod] AboutDialog missing")
