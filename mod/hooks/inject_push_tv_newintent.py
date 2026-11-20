#!/usr/bin/env python3
"""TV leanback: reset detailRequested on onNewIntent so push:// can switch to PUSH site.

Root cause: leanback VideoActivity.checkId() returns early when detailRequested is true.
Push extractor starts VideoActivity (singleTop) -> onNewIntent -> checkId skipped ->
Push returns empty url -> permanent 地址加载失败.
Mobile checkId has no such guard, so it recovers after a brief flash.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
PATH = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"


def main() -> None:
    print("[mod] inject_push_tv_newintent ROOT", ROOT)
    if not PATH.is_file():
        print("[mod] WARN missing", PATH)
        return
    t = PATH.read_text(encoding="utf-8")
    if "PUSH_TV_NEWINTENT_FIX" in t:
        print("[mod] push tv newintent already")
        return

    # Prefer patching onNewIntent body
    old = """        saveHistory();
        getIntent().putExtras(intent);
        checkId();
"""
    new = """        saveHistory();
        getIntent().putExtras(intent);
        // PUSH_TV_NEWINTENT_FIX: 允许 push 二次进详情（否则 checkId 直接 return）
        detailRequested = false;
        try {
            if (service() != null) {
                player().reset();
                player().stop();
            }
        } catch (Throwable ignored) {
        }
        checkId();
"""
    if old in t:
        t = t.replace(old, new, 1)
        PATH.write_text(t, encoding="utf-8")
        print("[mod] leanback onNewIntent push fix OK")
        return

    # Fallback: inside checkId, allow re-entry when key/id is PUSH or push://
    m = re.search(
        r"(private void checkId\(\) \{\n)(\s*if \(detailRequested\) return;\n)",
        t,
    )
    if m:
        insert = (
            m.group(1)
            + "        // PUSH_TV_NEWINTENT_FIX\n"
            + "        if (detailRequested) {\n"
            + "            try {\n"
            + "                String id = getId();\n"
            + "                if (id != null && (id.startsWith(\"push://\") "
            + "|| SiteApi.PUSH.equals(getKey()))) detailRequested = false;\n"
            + "            } catch (Throwable ignored) {}\n"
            + "        }\n"
            + m.group(2)
        )
        t = t[: m.start()] + insert + t[m.end() :]
        PATH.write_text(t, encoding="utf-8")
        print("[mod] leanback checkId push reentry OK")
        return

    print("[mod] ERROR cannot patch leanback VideoActivity for push")
    sys.exit(1)


if __name__ == "__main__":
    main()
