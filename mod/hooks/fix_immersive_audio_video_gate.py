#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
targets = (
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
)

old = """    private boolean shouldUseImmersiveAudio() {
        return PlayerSetting.isImmersiveAudioMode() && (isAudioOnly() || isMusicLike());
    }
"""
new = """    private boolean shouldUseImmersiveAudio() {
        if (!PlayerSetting.isImmersiveAudioMode() || service() == null) return false;
        PlayerManager current = player();
        if (current == null || current.isEmpty()) return false;

        // 等待真实轨道信息到达，不能把尚未加载轨道的普通视频误判为纯音频。
        androidx.media3.common.Tracks tracks = current.getCurrentTracks();
        if (tracks == null || tracks.isEmpty()) return false;

        // 只要存在视频轨道或已经取得视频尺寸，就必须留在普通视频界面。
        // 带画面的音乐 MV 也不自动切换沉浸音频模式。
        if (tracks.containsType(C.TRACK_TYPE_VIDEO)
                || current.getVideoWidth() > 0
                || current.getVideoHeight() > 0) return false;

        return tracks.containsType(C.TRACK_TYPE_AUDIO) && isAudioOnly();
    }
"""
for relative in targets:
    path = root / relative
    if not path.exists():
        print(f"[immersive-audio] source set absent, skip: {relative}")
        continue
    content = path.read_text(encoding="utf-8")
    if old in content:
        content = content.replace(old, new, 1)
        path.write_text(content, encoding="utf-8")
        print(f"[immersive-audio] patched {relative}")
    elif new in content:
        print(f"[immersive-audio] already patched: {relative}")
    else:
        raise SystemExit(f"[immersive-audio] shouldUseImmersiveAudio marker missing: {relative}")
