#!/usr/bin/env python3
"""Inject SMB build dependencies and R8 compatibility rules without modifying upstream files."""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def inject(path, anchor, addition, label):
    if not path.is_file():
        raise SystemExit(f"[mod] ERROR: missing {path.relative_to(ROOT)}")
    text = path.read_text(encoding="utf-8")
    if addition.lstrip().splitlines()[0].strip() in text:
        print(f"[mod] {label} already present")
        return
    if anchor not in text:
        raise SystemExit(f"[mod] ERROR: injection anchor not found in {path.relative_to(ROOT)}")
    path.write_text(text.replace(anchor, anchor + addition, 1), encoding="utf-8")
    print(f"[mod] injected {label}")


versions = ROOT / "gradle/libs.versions.toml"
inject(
    versions,
    'smbj = "0.14.0"\n',
    'dcerpc = "0.12.13"\n',
    "SMB DCERPC version",
)
inject(
    versions,
    'smbj = { group = "com.hierynomus", name = "smbj", version.ref = "smbj" }\n',
    'dcerpc = { group = "com.rapid7.client", name = "dcerpc", version.ref = "dcerpc" }\n',
    "SMB DCERPC catalog alias",
)

build = ROOT / "app/build.gradle"
inject(
    build,
    '    implementation libs.nanohttpd\n',
    '    implementation libs.smbj\n'
    '    implementation(libs.dcerpc) { exclude group: \'com.hierynomus\', module: \'smbj\' }\n',
    "SMB runtime dependencies",
)

proguard = ROOT / "app/proguard-rules.pro"
rule = (
    "# SMBJ / Rapid7 DCERPC\n"
    "# Android has no java.rmi; Rapid7 only references this exception for malformed RPC responses.\n"
    "-dontwarn java.rmi.UnmarshalException\n"
)
if not proguard.is_file():
    raise SystemExit("[mod] ERROR: missing app/proguard-rules.pro")
text = proguard.read_text(encoding="utf-8")
if "-dontwarn java.rmi.UnmarshalException" not in text:
    proguard.write_text(text.rstrip() + "\n\n" + rule, encoding="utf-8")
    print("[mod] injected SMB R8 compatibility rule")
else:
    print("[mod] SMB R8 compatibility rule already present")

required = {
    "app/src/main/java/com/fongmi/android/tv/ui/activity/SmbBrowserActivity.java": "class SmbBrowserActivity",
    "app/src/main/java/com/fongmi/android/tv/smb/SmbClient.java": "class SmbClient",
    "app/src/main/java/com/fongmi/android/tv/smb/SmbNetworkScanner.java": "class SmbNetworkScanner",
    "app/src/main/java/com/fongmi/android/tv/smb/SmbPlaybackProxy.java": "class SmbPlaybackProxy",
    "app/src/main/java/com/fongmi/android/tv/setting/Setting.java": "isSmbEntryEnabled",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java": "setSmbEntry",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java": "SmbBrowserActivity.start",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java": "startSmb",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java": "SmbBrowserActivity.start",
    "app/src/leanback/java/com/fongmi/android/tv/bean/HomeButton.java": "ID_SMB",
    "app/src/mobile/res/menu/menu_nav.xml": 'android:id="@+id/smb"',
    "app/src/mobile/res/layout/activity_setting_personal.xml": 'android:id="@+id/smbEntry"',
    "app/src/mobile/res/drawable-nodpi/ic_nav_smb.png": None,
    "app/src/leanback/res/drawable-nodpi/ic_nav_smb.png": None,
}
for relative, marker in required.items():
    path = ROOT / relative
    if not path.is_file():
        raise SystemExit(f"[mod] ERROR: SMB packaging file missing: {relative}")
    if marker is not None and marker not in path.read_text(encoding="utf-8"):
        raise SystemExit(f"[mod] ERROR: SMB packaging marker missing: {relative}: {marker}")
menu = (ROOT / "app/src/mobile/res/menu/menu_nav.xml").read_text(encoding="utf-8")
if menu.index('android:id="@+id/smb"') > menu.index('android:id="@+id/setting"'):
    raise SystemExit("[mod] ERROR: mobile SMB menu must be before Settings")
print(f"[mod] SMB packaging verified ({len(required)} files)")
