#!/usr/bin/env python3
"""JAR_DIALOG_FOCUS_FIX (rollback: delete this hook + rebuild)

TV remote focus for spider jar AlertDialogs.

Root cause (shell side):
  Init.set() was only called with Application context in attachBaseContext.
  Jar PanSettings dialogs (线路排序 ListView / 下载线程 ScrollView) need an
  Activity context so the dialog window can take focus and DPAD/OK work.

This patch:
  1) App.onActivityResumed  -> Init.set(activity)
  2) App.onActivityPaused   -> Init.set(applicationContext) when clearing
  3) Leanback BaseActivity: do not swallow keys when a dialog window is focused
     (conservative; only skips custom handling if present)

If this breaks anything: remove hooks/inject_jar_dialog_focus.py from apply.sh
or delete the file and rebuild — no other files permanently rewritten in repo.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "JAR_DIALOG_FOCUS_FIX"


def patch_app(path: Path) -> None:
    if not path.is_file():
        print("[mod] WARN missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] App already", MARKER)
        return

    old_resumed = """    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }"""
    new_resumed = """    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
        // JAR_DIALOG_FOCUS_FIX: spider jar dialogs need Activity context on TV
        try {
            com.github.catvod.Init.set(activity);
        } catch (Throwable ignored) {
        }
    }"""
    if old_resumed not in t:
        # soft
        t2, n = re.subn(
            r"public void onActivityResumed\(@NonNull Activity activity\) \{\s*"
            r"if \(activity != activity\(\)\) this\.activity = activity;\s*\}",
            new_resumed.strip(),
            t,
            count=1,
            flags=re.S,
        )
        if not n:
            print("[mod] ERROR App onActivityResumed pattern not found")
            sys.exit(1)
        t = t2
    else:
        t = t.replace(old_resumed, new_resumed, 1)

    old_paused = """    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }"""
    new_paused = """    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
        // JAR_DIALOG_FOCUS_FIX: fall back to application context after pause
        try {
            com.github.catvod.Init.set(getApplicationContext());
        } catch (Throwable ignored) {
        }
    }"""
    if old_paused not in t:
        t2, n = re.subn(
            r"public void onActivityPaused\(@NonNull Activity activity\) \{\s*"
            r"if \(activity == activity\(\)\) this\.activity = null;\s*\}",
            new_paused.strip(),
            t,
            count=1,
            flags=re.S,
        )
        if not n:
            print("[mod] WARN App onActivityPaused pattern not found (skip)")
        else:
            t = t2
    else:
        t = t.replace(old_paused, new_paused, 1)

    path.write_text(t, encoding="utf-8")
    print("[mod] App", MARKER, "OK")


def patch_leanback_base(path: Path) -> None:
    """Conservative: ensure window can take focus; no aggressive key rewrite."""
    if not path.is_file():
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t:
        print("[mod] BaseActivity already", MARKER)
        return

    # Add dispatchKeyEvent only if missing — pass-through when dialog has focus
    if "dispatchKeyEvent" in t:
        print("[mod] BaseActivity has dispatchKeyEvent, skip inject")
        return

    insert = """
    // JAR_DIALOG_FOCUS_FIX: leave DPAD/OK to dialog window when activity lost window focus
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        try {
            android.view.Window w = getWindow();
            if (w != null && !w.getDecorView().hasWindowFocus()) {
                return super.dispatchKeyEvent(event);
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchKeyEvent(event);
    }
"""
    # before last closing brace of class
    last = t.rfind("\n}")
    if last < 0:
        print("[mod] WARN BaseActivity structure")
        return
    t = t[:last] + insert + t[last:]
    path.write_text(t, encoding="utf-8")
    print("[mod] BaseActivity", MARKER, "OK")


def main() -> None:
    print("[mod] inject_jar_dialog_focus ROOT", ROOT)
    patch_app(ROOT / "app/src/main/java/com/fongmi/android/tv/App.java")
    patch_leanback_base(
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/base/BaseActivity.java"
    )
    print("[mod] inject_jar_dialog_focus done")


if __name__ == "__main__":
    main()
