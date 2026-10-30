#!/usr/bin/env python3
"""Ensure PlaybackActivity has capturePlaybackScreenshot wrapper (optional)."""
from pathlib import Path
import sys
ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
path = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java"
print("[mod] inject_playback_screenshot", path)
if not path.is_file():
    print("[mod] WARN missing PlaybackActivity")
    raise SystemExit(0)
t = path.read_text(encoding="utf-8")
if "capturePlaybackScreenshot" in t:
    print("[mod] already")
    raise SystemExit(0)
method = '''
    /** MOD: 截取当前视频 surface，不含控制栏 */
    protected void capturePlaybackScreenshot() {
        try {
            com.fongmi.android.tv.utils.ScreenshotHelper.capture(this, getExoView(),
                new com.fongmi.android.tv.utils.ScreenshotHelper.Callback() {
                    @Override public void onSuccess(android.net.Uri uri) {
                        try { com.fongmi.android.tv.utils.Notify.show(getString(com.fongmi.android.tv.R.string.screenshot_ok)); } catch (Throwable ignored) {}
                    }
                    @Override public void onError(String message) {
                        try { com.fongmi.android.tv.utils.Notify.show(message == null || message.isEmpty() ? getString(com.fongmi.android.tv.R.string.screenshot_fail) : message); } catch (Throwable ignored) {}
                    }
                });
        } catch (Throwable e) {
            try { com.fongmi.android.tv.utils.Notify.show(getString(com.fongmi.android.tv.R.string.screenshot_fail)); } catch (Throwable ignored) {}
        }
    }
'''
# insert before last closing brace of class
idx = t.rfind('}')
t = t[:idx] + method + '\n' + t[idx:]
path.write_text(t, encoding='utf-8')
print("[mod] PlaybackActivity capturePlaybackScreenshot added", t.count('{')==t.count('}'))
