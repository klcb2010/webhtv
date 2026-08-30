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

    public static boolean getGithubProxyEnabled() {
        return isGithubProxyEnabled();
    }

    public static void putGithubProxyEnabled(boolean enabled) {
        Prefers.put("github_proxy_enabled", enabled);
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

# --- AboutDialog: 第一行 检查更新+我已悉知；第二行 加速源全宽；不抢焦点 ---
about = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/AboutDialog.java"
if about.exists():
    t = about.read_text(encoding="utf-8")
    # Remove previous broken inject if present
    if "SettingGithubProxyActivity" in t or "proxyBtn" in t:
        # strip previous long-click / proxyBtn blocks - re-apply clean version
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
            # looser match
            marker = None
            m = re.search(
                r"binding\.checkUpdate\.setOnClickListener\(v -> \{[\s\S]*?\}\);",
                t,
            )
            if m:
                marker = m.group(0)

        if marker:
            insert = marker + '''
        // MOD: 加速源独立第二行全宽，不抢默认焦点
        try {
            android.view.ViewGroup parent = (android.view.ViewGroup) binding.checkUpdate.getParent();
            if (parent != null) {
                android.view.ViewGroup grand = parent.getParent() instanceof android.view.ViewGroup
                        ? (android.view.ViewGroup) parent.getParent() : null;
                com.google.android.material.button.MaterialButton proxyBtn =
                        new com.google.android.material.button.MaterialButton(activity);
                proxyBtn.setText(com.fongmi.android.tv.R.string.setting_github_proxy_short);
                try {
                    // 与「检查更新」「我已悉知」同风格，不默认 selected/focus
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
                // 插到 checkUpdate 所在行的下一行（grand 为纵向容器时）
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
        # already has activity ref - ensure we still rewrote
        about.write_text(t, encoding="utf-8")
        print("[mod] AboutDialog cleaned previous inject")
else:
    print("[mod] AboutDialog missing")