#!/usr/bin/env python3
"""Route Result.getMsg() toast sites to ResultMsgUi.show (no-op). No rule files."""
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

IMPORT = "import com.fongmi.android.tv.utils.ResultMsgUi;"


def ensure_import(text: str) -> str:
    if IMPORT in text:
        return text
    if "import com.fongmi.android.tv.utils.Notify;" in text:
        return text.replace(
            "import com.fongmi.android.tv.utils.Notify;",
            "import com.fongmi.android.tv.utils.Notify;\n" + IMPORT,
            1,
        )
    m = re.search(r"(package [\w.]+;\s*\n)", text)
    if m:
        return text[: m.end()] + "\n" + IMPORT + "\n" + text[m.end() :]
    return text


def patch_file(path: pathlib.Path) -> bool:
    if not path.exists():
        return False
    t = path.read_text(encoding="utf-8")
    orig = t
    t = ensure_import(t)

    # remove old guard name if previous build left it
    t = t.replace("SpiderToastGuard.showMsg", "ResultMsgUi.show")
    t = t.replace("import com.fongmi.android.tv.utils.SpiderToastGuard;", IMPORT)

    t = t.replace(
        "result -> Notify.show(result.getMsg())",
        "result -> ResultMsgUi.show(result.getMsg())",
    )
    t = re.sub(
        r"Notify\.show\(\s*result\.getMsg\(\)\s*\)\s*;",
        "ResultMsgUi.show(result.getMsg());",
        t,
    )
    # spider msg branch: still no-op ui; keep real error string path
    t = re.sub(
        r"Notify\.show\(\s*result\s*!=\s*null\s*&&\s*result\.hasMsg\(\)\s*\?\s*result\.getMsg\(\)\s*:\s*([^)]+)\)\s*;",
        r"if (result != null && result.hasMsg()) ResultMsgUi.show(result.getMsg()); else Notify.show(\1);",
        t,
    )

    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] result msg ui patched", path.relative_to(ROOT))
        return True
    print("[mod] result msg ui unchanged", path.relative_to(ROOT))
    return False


def main() -> None:
    # drop leftover asset from older approach if any
    for p in ROOT.rglob("spider_toast_block.dat"):
        p.unlink(missing_ok=True)
        print("[mod] removed", p)
    n = sum(1 for rel in TARGETS if patch_file(ROOT / rel))
    print("[mod] result msg ui done, changed:", n)


if __name__ == "__main__":
    main()
