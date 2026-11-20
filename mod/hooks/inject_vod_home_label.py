#!/usr/bin/env python3
"""手机端 vod_home 文案：只改写已有 strings.xml，不新增资源文件。"""
from __future__ import annotations
import re
import sys
from pathlib import Path

TARGETS = [
    ("app/src/mobile/res/values-zh-rCN/strings.xml", "热门影片推荐"),
    ("app/src/mobile/res/values-zh-rTW/strings.xml", "熱門影片推薦"),
    ("app/src/mobile/res/values/strings.xml", "Popular picks"),
]


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    # 强制删掉会冲突的独立文件（仓库残留 / 旧 MOD）
    for rel in (
        "app/src/mobile/res/values/strings_vod_home.xml",
        "app/src/mobile/res/values-zh-rCN/strings_vod_home.xml",
        "app/src/mobile/res/values-zh-rTW/strings_vod_home.xml",
    ):
        p = root / rel
        if p.is_file():
            p.unlink()
            print(f"[mod] inject_vod_home_label removed {rel}")

    ok = True
    for rel, value in TARGETS:
        path = root / rel
        if not path.is_file():
            print(f"[mod] inject_vod_home_label FAIL missing {rel}")
            ok = False
            continue
        text = path.read_text(encoding="utf-8")
        pat = re.compile(r'(<string\s+name="vod_home"[^>]*>)(.*?)(</string>)', re.DOTALL)
        m = pat.search(text)
        if not m:
            print(f"[mod] inject_vod_home_label FAIL no vod_home in {rel}")
            ok = False
            continue
        if m.group(2) == value:
            print(f"[mod] inject_vod_home_label already {rel}")
            continue
        new_text, n = pat.subn(rf"\g<1>{value}\g<3>", text, count=1)
        if n != 1:
            print(f"[mod] inject_vod_home_label FAIL replace {rel}")
            ok = False
            continue
        path.write_text(new_text, encoding="utf-8")
        print(f"[mod] inject_vod_home_label OK {rel} -> {value}")
    if not ok:
        sys.exit(1)
    print("[mod] inject_vod_home_label SUCCESS")


if __name__ == "__main__":
    main()
