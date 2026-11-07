#!/usr/bin/env python3
"""Install the original mod/smbicon.png artwork into Android drawable resources."""
from pathlib import Path
import shutil
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
source = ROOT / "mod/smbicon.png"
if not source.is_file():
    raise SystemExit("[mod] ERROR: mod/smbicon.png is missing")

targets = [
    ROOT / "app/src/leanback/res/drawable-nodpi/ic_home_smb.png",
    ROOT / "app/src/mobile/res/drawable-nodpi/ic_nav_smb.png",
]
for target in targets:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    print(f"[mod] SMB artwork -> {target.relative_to(ROOT)}")
