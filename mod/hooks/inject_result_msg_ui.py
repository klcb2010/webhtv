#!/usr/bin/env python3
"""Remove toast-blocking remnants; restore Result.msg → Notify.show."""
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


def restore_msg(path: pathlib.Path) -> bool:
    if not path.exists():
        return False
    t = path.read_text(encoding="utf-8")
    orig = t
    t = t.replace("result -> UiSurface.show(result.getMsg())", "result -> Notify.show(result.getMsg())")
    t = t.replace("UiSurface.show(result.getMsg());", "Notify.show(result.getMsg());")
    t = t.replace("ResultMsgUi.show(result.getMsg());", "Notify.show(result.getMsg());")
    t = t.replace("SpiderToastGuard.showMsg(result.getMsg());", "Notify.show(result.getMsg());")
    # ternary restore if we rewrote it
    t = re.sub(
        r"if \(result != null && result\.hasMsg\(\)\) UiSurface\.show\(result\.getMsg\(\)\); else Notify\.show\(([^)]+)\);",
        r"Notify.show(result != null && result.hasMsg() ? result.getMsg() : \1);",
        t,
    )
    t = t.replace("\nimport com.fongmi.android.tv.utils.UiSurface;\n", "\n")
    t = t.replace("import com.fongmi.android.tv.utils.UiSurface;\n", "")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] restored msg toast", path.relative_to(ROOT))
        return True
    return False


def strip_app_install() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/App.java"
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    t = t.replace("\n        UiSurface.install();", "")
    t = t.replace("UiSurface.install();\n", "")
    t = t.replace("\nimport com.fongmi.android.tv.utils.UiSurface;\n", "\n")
    t = t.replace("import com.fongmi.android.tv.utils.UiSurface;\n", "")
    if t != orig:
        path.write_text(t, encoding="utf-8")
        print("[mod] removed UiSurface.install from App")


def main() -> None:
    # drop assets/intoast if any
    for p in ROOT.rglob("intoast"):
        if p.is_file():
            p.unlink(missing_ok=True)
            print("[mod] removed", p)
    ui = ROOT / "app/src/main/java/com/fongmi/android/tv/utils/UiSurface.java"
    if ui.exists():
        ui.unlink()
        print("[mod] removed UiSurface.java")
    n = sum(1 for rel in TARGETS if restore_msg(ROOT / rel))
    strip_app_install()
    print("[mod] toast block fully disabled, restored files:", n)


if __name__ == "__main__":
    main()
