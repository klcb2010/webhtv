#!/usr/bin/env python3
"""手机端首页分类标题：vod_home → 热门影片推荐（改 mobile strings.xml，避免重复资源）"""
from __future__ import annotations
import re
import sys
from pathlib import Path

REPLACEMENTS = {
    "app/src/mobile/res/values-zh-rCN/strings.xml": ("vod_home", "热门影片推荐"),
    "app/src/mobile/res/values-zh-rTW/strings.xml": ("vod_home", "熱門影片推薦"),
    "app/src/mobile/res/values/strings.xml": ("vod_home", "Popular picks"),
}


def patch_file(path: Path, name: str, value: str) -> bool:
    if not path.is_file():
        print(f"[mod] inject_vod_home_label SKIP missing {path}")
        return False
    text = path.read_text(encoding="utf-8")
    pat = re.compile(
        rf'(<string\s+name="{re.escape(name)}"[^>]*>)(.*?)(</string>)',
        re.DOTALL,
    )
    m = pat.search(text)
    if not m:
        print(f"[mod] inject_vod_home_label FAIL no {name} in {path}")
        return False
    if m.group(2) == value:
        print(f"[mod] inject_vod_home_label already {path.name}")
        return True
    new_text, n = pat.subn(rf"\g<1>{value}\g<3>", text, count=1)
    if n != 1:
        print(f"[mod] inject_vod_home_label FAIL replace {path}")
        return False
    path.write_text(new_text, encoding="utf-8")
    print(f"[mod] inject_vod_home_label OK {path} -> {value}")
    return True


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    ok = True
    for rel, (name, value) in REPLACEMENTS.items():
        if not patch_file(root / rel, name, value):
            ok = False
    if not ok:
        sys.exit(1)
    print("[mod] inject_vod_home_label SUCCESS")


if __name__ == "__main__":
    main()
