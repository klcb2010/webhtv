#!/usr/bin/env python3
"""Patch upstream UpdateDialog to focus confirm/update button when shown."""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

FOCUS_SNIPPET = r"""
    private void modFocusConfirmButton() {
        try {
            android.view.View root = getView();
            if (root == null && getDialog() != null && getDialog().getWindow() != null) {
                root = getDialog().getWindow().getDecorView();
            }
            if (root == null) return;
            android.view.View target = null;
            for (String name : new String[]{"confirm", "update", "positive", "btnUpdate", "btn_update", "download"}) {
                int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
                if (id == 0) continue;
                android.view.View v = root.findViewById(id);
                if (v != null && v.getVisibility() == android.view.View.VISIBLE) { target = v; break; }
            }
            if (target == null) {
                try { target = root.findViewById(com.fongmi.android.tv.R.id.confirm); } catch (Throwable ignored) {}
            }
            if (target != null) {
                target.setFocusable(true);
                target.setFocusableInTouchMode(true);
                target.requestFocus();
            }
        } catch (Throwable ignored) {}
    }
"""


def patch(path: Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "modFocusConfirmButton" not in t:
        idx = t.rfind("\n}")
        if idx > 0:
            t = t[:idx] + "\n" + FOCUS_SNIPPET + t[idx:]
    if "modFocusConfirmButton();" not in t:
        for pat in [
            r"(public void onViewCreated\([^)]*\)\s*\{)",
            r"(public void onStart\(\)\s*\{)",
            r"(public void onResume\(\)\s*\{)",
        ]:
            t2, n = re.subn(
                pat,
                r"\1\n        try { modFocusConfirmButton(); com.fongmi.android.tv.App.post(this::modFocusConfirmButton, 100); com.fongmi.android.tv.App.post(this::modFocusConfirmButton, 300); } catch (Throwable ignored) {}",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] hooked UpdateDialog lifecycle")
                break
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] patched", path)
    else:
        print("[mod] no change", path)


def main() -> None:
    patch(ROOT / "app/src/main/java/com/fongmi/android/tv/ui/dialog/UpdateDialog.java")
    print("[mod] inject_update_dialog_focus done")


if __name__ == "__main__":
    main()
