#!/usr/bin/env python3
"""Suppress Result.msg toasts + install process toast gate in App.onCreate."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

TARGETS = [
    "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/TypeFragment.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/fragment/TypeFragment.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]

IMPORT = "import com.fongmi.android.tv.utils.UiSurface;"


def ensure_import(text: str, imp: str) -> str:
    if imp in text:
        return text
    if "import com.fongmi.android.tv.utils.Notify;" in text:
        return text.replace(
            "import com.fongmi.android.tv.utils.Notify;",
            "import com.fongmi.android.tv.utils.Notify;\n" + imp,
            1,
        )
    m = re.search(r"(package [\w.]+;\s*\n)", text)
    if m:
        return text[: m.end()] + "\n" + imp + "\n" + text[m.end() :]
    return text


def patch_msg_sites(path: pathlib.Path) -> bool:
    if not path.exists():
        return False
    t = path.read_text(encoding="utf-8")
    orig = t
    t = ensure_import(t, IMPORT)
    t = t.replace("SpiderToastGuard.showMsg", "UiSurface.show")
    t = t.replace("ResultMsgUi.show", "UiSurface.show")
    t = t.replace("import com.fongmi.android.tv.utils.SpiderToastGuard;", IMPORT)
    t = t.replace("import com.fongmi.android.tv.utils.ResultMsgUi;", IMPORT)
    t = t.replace(
        "result -> Notify.show(result.getMsg())",
        "result -> UiSurface.show(result.getMsg())",
    )
    t = re.sub(
        r"Notify\.show\(\s*result\.getMsg\(\)\s*\)\s*;",
        "UiSurface.show(result.getMsg());",
        t,
    )
    t = re.sub(
        r"Notify\.show\(\s*result\s*!=\s*null\s*&&\s*result\.hasMsg\(\)\s*\?\s*result\.getMsg\(\)\s*:\s*([^)]+)\)\s*;",
        r"if (result != null && result.hasMsg()) UiSurface.show(result.getMsg()); else Notify.show(\1);",
        t,
    )
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] msg sites", path.relative_to(ROOT))
        return True
    return False


def patch_app() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/App.java"
    if not path.exists():
        print("[mod] App.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "UiSurface.install" in t:
        print("[mod] App already gated")
        return
    t = ensure_import(t, IMPORT)
    # after Notify.createChannel() or start of onCreate body
    if "Notify.createChannel();" in t:
        t = t.replace(
            "Notify.createChannel();",
            "Notify.createChannel();\n        UiSurface.install();",
            1,
        )
    elif "protected void onCreate()" in t or "public void onCreate()" in t:
        t = re.sub(
            r"(void onCreate\(\) \{\s*super\.onCreate\(\);)",
            r"\1\n        UiSurface.install();",
            t,
            count=1,
        )
    else:
        print("[mod] WARN cannot find App.onCreate insert point")
        return
    path.write_text(t, encoding="utf-8")
    print("[mod] App.onCreate UiSurface.install")


def main() -> None:
    for p in ROOT.rglob("spider_toast_block.dat"):
        p.unlink(missing_ok=True)
    n = sum(1 for rel in TARGETS if patch_msg_sites(ROOT / rel))
    patch_app()
    print("[mod] toast gate done, msg files:", n)


if __name__ == "__main__":
    main()
