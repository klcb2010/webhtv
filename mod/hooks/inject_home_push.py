#!/usr/bin/env python3
"""Mobile VodFragment: respect Setting.isHomePush() for link FAB."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
path = ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java"
if not path.exists():
    print("[mod] VodFragment missing")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
if "isHomePush" in t and "applyHomePushFab" in t:
    print("[mod] home push already")
    sys.exit(0)

# import Setting if needed
if "import com.fongmi.android.tv.setting.Setting;" not in t:
    t = t.replace(
        "package com.fongmi.android.tv.ui.fragment;",
        "package com.fongmi.android.tv.ui.fragment;\n\nimport com.fongmi.android.tv.setting.Setting;",
        1,
    )

HELPER = """
    private void applyHomePushFab(boolean preferShow) {
        try {
            if (!Setting.isHomePush()) {
                mBinding.link.setVisibility(View.GONE);
                return;
            }
        } catch (Throwable ignored) {
        }
        if (preferShow) mBinding.link.show();
        else mBinding.link.setVisibility(View.VISIBLE);
    }
"""

# replace setFabVisible body link lines
# After any link.setVisibility(VISIBLE) or link.show() that is not GONE path, wrap

def patch_set_fab(text: str) -> str:
    # replace mBinding.link.show(); with applyHomePushFab(true);
    text = text.replace("mBinding.link.show();", "applyHomePushFab(true);")
    # mBinding.link.setVisibility(View.VISIBLE) -> applyHomePushFab
    text = re.sub(
        r"mBinding\.link\.setVisibility\(View\.VISIBLE\);",
        "applyHomePushFab(false);",
        text,
    )
    return text

t2 = patch_set_fab(t)
if HELPER.strip() not in t2 and "applyHomePushFab" in t2:
    # append helper before last class brace
    t2 = t2.rstrip()
    if t2.endswith("}"):
        t2 = t2[:-1] + HELPER + "\n}\n"
elif "applyHomePushFab" not in t2:
    print("[mod] WARN no link.show replacements")
else:
    pass

path.write_text(t2, encoding="utf-8")
print("[mod] VodFragment home push patched", t2.count("applyHomePushFab"))
