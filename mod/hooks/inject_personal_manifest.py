#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")
ACT = """        <activity
            android:name=".ui.activity.SettingPersonalActivity"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
            android:exported="false" />
"""


def ensure(path: pathlib.Path, landscape: bool = False):
    if not path.exists():
        print("[mod] skip manifest", path)
        return
    t = path.read_text(encoding="utf-8")
    if "SettingPersonalActivity" in t:
        print("[mod] personal activity already in", path)
        return
    block = ACT
    if landscape:
        block = block.replace(
            'android:exported="false" />',
            'android:exported="false"\n            android:screenOrientation="sensorLandscape" />',
        )
    if '.ui.activity.SettingActivity"' in t:
        t = re.sub(
            r'(android:name="\.ui\.activity\.SettingActivity"[\s\S]*?/>)',
            r"\1\n\n" + block,
            t,
            count=1,
        )
    else:
        t = t.replace("</application>", block + "\n    </application>")
    path.write_text(t, encoding="utf-8")
    print("[mod] registered SettingPersonalActivity in", path)


ensure(ROOT / "app/src/mobile/AndroidManifest.xml", False)
ensure(ROOT / "app/src/leanback/AndroidManifest.xml", True)
