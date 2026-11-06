#!/usr/bin/env python3
"""Idempotently add clear.py to upstream-sync protect list in android-release.yml."""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
cands = [
    ROOT / ".github/workflows/android-release.yml",
    ROOT / "android-release.yml",
]
path = next((p for p in cands if p.is_file()), None)
if path is None:
    print("[mod] WARN no android-release.yml for clear.py protect")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
if "webhtv-protected/clear.py" in t and "已恢复 clear.py" in t:
    print("[mod] clear.py already protected in", path)
    sys.exit(0)

if 'echo "🔒 mod/"' in t and "clear.py" not in t[t.find('echo "🔒 mod/"'): t.find('echo "🔒 mod/"') + 500]:
    t = t.replace(
        '            echo "🔒 mod/"\n          fi\n',
        '            echo "🔒 mod/"\n          fi\n\n'
        '          if [ -f clear.py ]; then\n'
        '            cp -a clear.py /tmp/webhtv-protected/clear.py\n'
        '            echo "🔒 clear.py"\n'
        '          fi\n',
        1,
    )

if "--exclude='mod/'" in t and "--exclude='clear.py'" not in t:
    t = t.replace(
        "--exclude='mod/' \\",
        "--exclude='mod/' \\\n            --exclude='clear.py' \\",
        1,
    )

if 'echo "🔒 已恢复 mod/"' in t and "已恢复 clear.py" not in t:
    t = t.replace(
        '            echo "🔒 已恢复 mod/"\n          fi\n',
        '            echo "🔒 已恢复 mod/"\n          fi\n\n'
        '          if [ -f /tmp/webhtv-protected/clear.py ]; then\n'
        '            cp -a /tmp/webhtv-protected/clear.py clear.py\n'
        '            echo "🔒 已恢复 clear.py"\n'
        '          fi\n',
        1,
    )

if 'echo "✅ mod/ 未被修改"' in t and "clear.py 未被修改" not in t:
    t = t.replace(
        '            echo "✅ mod/ 未被修改"\n          else\n            echo "❌ mod/ 被修改"\n            PROTECTED_CHANGED=1\n          fi\n',
        '            echo "✅ mod/ 未被修改"\n          else\n            echo "❌ mod/ 被修改"\n            PROTECTED_CHANGED=1\n          fi\n'
        '          if git diff --cached --quiet -- clear.py; then\n'
        '            echo "✅ clear.py 未被修改"\n'
        '          else\n'
        '            echo "❌ clear.py 被修改"\n'
        '            PROTECTED_CHANGED=1\n'
        '          fi\n',
        1,
    )

path.write_text(t, encoding="utf-8")
tt = path.read_text(encoding="utf-8")
if "webhtv-protected/clear.py" in tt and "已恢复 clear.py" in tt:
    print("[mod] protected clear.py in", path)
else:
    print("[mod] WARN incomplete clear.py protect", path)
    sys.exit(1)
