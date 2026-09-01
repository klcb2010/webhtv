#!/usr/bin/env python3
"""Result.msg → UiSurface.show; install toast gate; write assets/intoast from CI secret."""
import base64
import os
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


def write_intoast() -> None:
    """Decode secret → assets/intoast (opaque name). Never log rule contents."""
    assets = ROOT / "app/src/main/assets"
    assets.mkdir(parents=True, exist_ok=True)
    out = assets / "intoast"
    b64 = (os.environ.get("INTOAST_B64") or os.environ.get("SPIDER_TOAST_BLOCK_B64") or "").strip()
    if not b64:
        out.write_text("", encoding="utf-8")
        print("[mod] intoast: empty (set secret INTOAST_B64)")
        return
    try:
        raw = base64.b64decode(b64)
        text = raw.decode("utf-8", errors="replace")
        out.write_bytes(text.encode("utf-8"))
        n = sum(1 for ln in text.splitlines() if ln.strip() and not ln.strip().startswith("#"))
        print("[mod] intoast: loaded", n, "entries")
    except Exception as e:
        out.write_text("", encoding="utf-8")
        print("[mod] intoast: decode failed", type(e).__name__)


def patch_msg(path: pathlib.Path) -> bool:
    if not path.exists():
        return False
    t = path.read_text(encoding="utf-8")
    orig = t
    t = ensure_import(t, IMPORT)
    t = t.replace("SpiderToastGuard.showMsg", "UiSurface.show")
    t = t.replace("ResultMsgUi.show", "UiSurface.show")
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
        print("[mod] msg", path.relative_to(ROOT))
        return True
    return False


def patch_app() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/App.java"
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "UiSurface.install" in t:
        return
    t = ensure_import(t, IMPORT)
    if "Notify.createChannel();" in t:
        t = t.replace("Notify.createChannel();", "Notify.createChannel();\n        UiSurface.install();", 1)
    else:
        t = re.sub(
            r"(void onCreate\(\) \{\s*super\.onCreate\(\);)",
            r"\1\n        UiSurface.install();",
            t,
            count=1,
        )
    path.write_text(t, encoding="utf-8")
    print("[mod] App UiSurface.install")


def main() -> None:
    write_intoast()
    n = sum(1 for rel in TARGETS if patch_msg(ROOT / rel))
    patch_app()
    print("[mod] intoast pipeline done, msg files:", n)


if __name__ == "__main__":
    main()
