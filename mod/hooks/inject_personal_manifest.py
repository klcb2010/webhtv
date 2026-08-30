#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
ACTIVITIES = [
    "SettingPersonalActivity",
    "SettingAiActivity",
    "SettingAssrtActivity",
]


def ensure(path: pathlib.Path, landscape: bool = False):
    if not path.exists():
        print("[mod] skip manifest", path)
        return
    t = path.read_text(encoding="utf-8")
    changed = False
    for name in ACTIVITIES:
        if name in t:
            continue
        block = (
            f'        <activity\n'
            f'            android:name=".ui.activity.{name}"\n'
            f'            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"\n'
            f'            android:exported="false"'
        )
        if landscape:
            block += '\n            android:screenOrientation="sensorLandscape"'
        block += " />\n"
        if ".ui.activity.SettingActivity" in t:
            t = re.sub(
                r'(android:name="\.ui\.activity\.SettingActivity"[\s\S]*?/>)',
                r"\1\n\n" + block,
                t,
                count=1,
            )
        else:
            t = t.replace("</application>", block + "\n    </application>")
        changed = True
        print("[mod] registered", name, "in", path)
    if changed:
        path.write_text(t, encoding="utf-8")


ensure(ROOT / "app/src/mobile/AndroidManifest.xml", False)
ensure(ROOT / "app/src/leanback/AndroidManifest.xml", True)
