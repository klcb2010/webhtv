#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
targets = (
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
)

method = """    private boolean shouldUseImmersiveAudio() {
        return PlayerSetting.isImmersiveAudioMode() && (isAudioOnly() || isImmersiveMusicTitle());
    }"""

helper = """    private boolean isImmersiveMusicTitle() {
        String title = (getName() + " " + getOsdTitle()).toLowerCase(java.util.Locale.ROOT);
        String[] keywords = {"无损", "好听", "经典", "榜", "曲", "音乐", "歌单"};
        for (String keyword : keywords) {
            if (title.contains(keyword)) return true;
        }
        return false;
    }"""

pattern = re.compile(
    r"^    private boolean shouldUseImmersiveAudio\(\) \{.*?^    \}",
    re.MULTILINE | re.DOTALL,
)

patched = 0
for relative in targets:
    path = root / relative
    if not path.exists():
        print(f"[immersive-audio] source set absent, skip: {relative}")
        continue

    source = path.read_text(encoding="utf-8")
    updated, count = pattern.subn(lambda _: method, source, count=1)
    if count != 1:
        if method not in source:
            raise SystemExit(f"[immersive-audio] shouldUseImmersiveAudio method missing: {relative}")
        updated = source

    if helper not in updated:
        anchor = method + "\n"
        if anchor not in updated:
            raise SystemExit(f"[immersive-audio] method insertion anchor missing: {relative}")
        updated = updated.replace(anchor, anchor + "\n" + helper + "\n", 1)

    if updated != source:
        path.write_text(updated, encoding="utf-8")
        print(f"[immersive-audio] patched {relative}")
    else:
        print(f"[immersive-audio] already patched: {relative}")
    patched += 1

if patched == 0:
    raise SystemExit("[immersive-audio] no VideoActivity source set was patched")
print(f"[immersive-audio] done total={patched}")
