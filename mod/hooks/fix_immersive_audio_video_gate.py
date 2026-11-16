#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])
path = root / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java"
if not path.exists():
    raise SystemExit("[immersive-audio] missing mobile VideoActivity.java")

text = path.read_text(encoding="utf-8")
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
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("[immersive-audio] shouldUseImmersiveAudio marker missing or already changed")
else:
    print("[immersive-audio] already patched")
path.write_text(text, encoding="utf-8")
print("[immersive-audio] patched mobile VideoActivity.java")
