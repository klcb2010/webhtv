#!/usr/bin/env python3
"""SHELL_JAR_SETTINGS_REDO v3
1) Host Init.activityForDialog for jar dialogs
2) App lifecycle keeps Activity in Init
3) Intercept detailContent + action for shell panels
4) Shell UI reads/writes playLineOrder Prefers key used by jar
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "SHELL_JAR_SETTINGS_REDO"


def ensure_init() -> None:
    path = ROOT / "catvod/src/main/java/com/github/catvod/Init.java"
    if not path.is_file():
        print("[mod] ERROR no Init.java")
        sys.exit(1)
    t = path.read_text(encoding="utf-8")
    if "activityForDialog" in t:
        print("[mod] Init already has activityForDialog")
        return
    # full replace with known good
    src = Path(__file__).resolve().parent.parent / "catvod/src/main/java/com/github/catvod/Init.java"
    if src.is_file():
        path.write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
        print("[mod] Init replaced from mod copy")
        return
    print("[mod] ERROR mod Init missing")
    sys.exit(1)


def patch_app() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/App.java"
    if not path.is_file():
        print("[mod] WARN no App.java")
        return
    t = path.read_text(encoding="utf-8")
    if "Init.setActivity" in t:
        print("[mod] App already setActivity")
        return
    old = """    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }"""
    new = """    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        // SHELL_JAR_SETTINGS_REDO
        try {
            com.github.catvod.Init.setActivity(activity);
            com.github.catvod.Init.set(activity);
        } catch (Throwable ignored) {
        }
    }"""
    if old in t:
        t = t.replace(old, new, 1)
    else:
        t2, n = re.subn(
            r"public void onActivityResumed\(@NonNull Activity activity\) \{\s*"
            r"if \(activity != activity\(\)\) this\.activity = activity;\s*\}",
            new.strip(),
            t,
            count=1,
            flags=re.S,
        )
        if not n:
            print("[mod] WARN App resumed not patched")
        else:
            t = t2
    old_p = """    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }"""
    new_p = """    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
        // SHELL_JAR_SETTINGS_REDO: keep last activity for dialogs until another resumes
    }"""
    if old_p in t:
        t = t.replace(old_p, new_p, 1)
    path.write_text(t, encoding="utf-8")
    print("[mod] App lifecycle OK")


def patch_site_api() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/api/SiteApi.java"
    if not path.is_file():
        print("[mod] ERROR no SiteApi")
        sys.exit(1)
    t = path.read_text(encoding="utf-8")
    if MARKER in t and "openPost" in t and "action" in t[t.find(MARKER):t.find(MARKER)+800]:
        # may only have detail - still ensure action
        pass
    insert = """
        // SHELL_JAR_SETTINGS_REDO
        try {
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(id)) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.openPost(key, id);
                return com.fongmi.android.tv.bean.Result.empty();
            }
        } catch (Throwable ignored) {
        }
"""
    if "isShellPanel(id)" not in t:
        m = re.search(
            r"public static Result detailContent\(@NonNull String key, @NonNull String id\) throws Exception \{",
            t,
        )
        if not m:
            print("[mod] ERROR detailContent")
            sys.exit(1)
        t = t[: m.end()] + insert + t[m.end() :]
        print("[mod] SiteApi detailContent OK")
    else:
        print("[mod] SiteApi detail already")

    # action intercept
    if "isShellPanel(action)" not in t and "isShellPanel(act" not in t:
        m = re.search(
            r"public static Result action\(@NonNull String key, @NonNull String action\) throws Exception \{",
            t,
        )
        if not m:
            m = re.search(r"public static Result action\(String key, String action\) throws Exception \{", t)
        if m:
            ins2 = """
        // SHELL_JAR_SETTINGS_REDO
        try {
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(action)) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.openPost(key, action);
                return com.fongmi.android.tv.bean.Result.empty();
            }
        } catch (Throwable ignored) {
        }
"""
            t = t[: m.end()] + ins2 + t[m.end() :]
            print("[mod] SiteApi action OK")
        else:
            print("[mod] WARN action method not found")
    path.write_text(t, encoding="utf-8")


def patch_video() -> None:
    snip = """
        // SHELL_JAR_SETTINGS_REDO: TV 线路排序/下载线程（账号登录不劫持）
        try {
            String _id = getId();
            String _name = null;
            try { _name = getName(); } catch (Throwable ignored) {}
            if (com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.isShellPanel(_id, _name)) {
                com.fongmi.android.tv.ui.dialog.JarPanSettingsShell.open(this, getKey(), _id, _name);
                return;
            }
        } catch (Throwable ignored) {
        }
"""
    for flavor in ("leanback", "mobile"):
        path = ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        if "账号登录不劫持" in t:
            print("[mod] VideoActivity already v20", flavor)
            continue
        # drop old SHELL_JAR_SETTINGS_REDO inserts
        import re as _re
        t = _re.sub(
            r"\n[ \t]*// SHELL_JAR_SETTINGS_REDO[\s\S]*?catch \(Throwable ignored\) \{\s*\}",
            "",
            t,
            count=8,
        )
        m = _re.search(r"(private void checkId\(\)\s*\{)", t)
        if not m:
            print("[mod] WARN no checkId", flavor)
            continue
        t = t[: m.end()] + snip + t[m.end() :]
        path.write_text(t, encoding="utf-8")
        print("[mod] VideoActivity", flavor)


def cleanup_login_host() -> None:
    """回退账号登录劫持：删除宿主 Activity 与 manifest 注册。"""
    host = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/JarLoginHostActivity.java"
    if host.is_file():
        host.unlink()
        print("[mod] removed JarLoginHostActivity.java")
    # also leanback/mobile copies if any
    for p in ROOT.glob("app/src/**/JarLoginHostActivity.java"):
        p.unlink()
        print("[mod] removed", p)
    import re
    for rel in (
        "app/src/main/AndroidManifest.xml",
        "app/src/leanback/AndroidManifest.xml",
        "app/src/mobile/AndroidManifest.xml",
    ):
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        if "JarLoginHostActivity" not in text:
            continue
        text2 = re.sub(
            r"\s*<activity\b[^>]*JarLoginHostActivity[\s\S]*?</activity>\s*",
            "\n",
            text,
            count=5,
        )
        # self-closing form
        text2 = re.sub(
            r"\s*<activity\b[^>]*JarLoginHostActivity[^>]*/>\s*",
            "\n",
            text2,
            count=5,
        )
        path.write_text(text2, encoding="utf-8")
        print("[mod] stripped JarLoginHostActivity from", rel)



def main() -> None:
    print("[mod] inject_jar_settings_shell v3 ROOT", ROOT)
    cleanup_login_host()
    ensure_init()
    patch_app()
    shell = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/JarPanSettingsShell.java"
    if not shell.is_file():
        print("[mod] ERROR missing JarPanSettingsShell")
        sys.exit(1)
    patch_site_api()
    patch_video()
    print("[mod] inject_jar_settings_shell done")


if __name__ == "__main__":
    main()
