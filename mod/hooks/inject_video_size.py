#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])

def edit(path, transform):
    file = root / path
    if not file.exists():
        raise SystemExit(f"[video-size] missing {path}")
    original = file.read_text(encoding="utf-8")
    updated = transform(original)
    if updated != original:
        file.write_text(updated, encoding="utf-8")
        print(f"[video-size] patched {path}")

def add_before(text, marker, addition, label):
    if addition.strip() in text:
        return text
    if marker not in text:
        raise SystemExit(f"[video-size] marker missing: {label}")
    return text.replace(marker, addition + marker, 1)

for path in (
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
):
    def patch_video_activity(text):
        if "private void refreshVideoSizeAfterStart()" in text:
            return text
        text = add_before(
            text,
            "import com.fongmi.android.tv.setting.PlayerSetting;\n",
            "import com.fongmi.android.tv.player.VideoSizeProbe;\n",
            path + " import",
        )
        if "import com.fongmi.android.tv.setting.Setting;" not in text:
            text = add_before(
                text,
                "import com.fongmi.android.tv.player.VideoSizeProbe;\\n",
                "import com.fongmi.android.tv.setting.Setting;\\n",
                path + " Setting import",
            )
        start_pattern = re.compile(
            r"(?m)^([ \t]*)startPlayer\(getHistoryKey\(\), result, isUseParse\(\), "
            r"getSite\(\)\.getTimeout\(\), buildMetadata\(\)(?:, mInitialPlaybackPosition)?\);\n"
        )
        text, count = start_pattern.subn(
            lambda match: match.group(0) + match.group(1) + "refreshVideoSizeAfterStart();\n",
            text,
        )
        if count == 0:
            raise SystemExit(f"[video-size] startPlayer marker missing: {path}")
        text = add_before(
            text,
            "        syncKaraokePosition();\n",
            "        refreshVideoSizeAfterStart();\n",
            path + " onResume",
        )
        method = """    private int videoSizeRequestToken;

    private void refreshVideoSizeAfterStart() {
        int requestToken = ++videoSizeRequestToken;
        if (!Setting.isShowVideoSize()) {
            mBinding.control.action.videoSize.setVisibility(View.GONE);
            return;
        }
        mBinding.control.action.videoSize.setVisibility(View.GONE);
        mBinding.control.action.videoSize.post(() -> {
            if (requestToken != videoSizeRequestToken || isFinishing() || isDestroyed()
                    || !Setting.isShowVideoSize() || service() == null) return;
            PlayerManager current = player();
            if (current == null) return;
            VideoSizeProbe.query(this, current.getUrl(), current.getHeaders(), size -> runOnUiThread(() -> {
                if (requestToken != videoSizeRequestToken || isFinishing() || isDestroyed()
                        || !Setting.isShowVideoSize()) return;
                if (size == null) {
                    mBinding.control.action.videoSize.setVisibility(View.GONE);
                } else {
                    mBinding.control.action.videoSize.setText(size);
                    mBinding.control.action.videoSize.setVisibility(View.VISIBLE);
                }
            }));
        });
    }

"""
        text = add_before(text, "    private void setPlayerKernel() {", method, path + " methods")
        return text
    edit(path, patch_video_activity)


def add_after(text, marker, addition, label):
    if addition.strip() in text:
        return text
    if marker not in text:
        raise SystemExit(f"[video-size] marker missing: {label}")
    return text.replace(marker, marker + addition, 1)


def patch_video_size_order(text):
    text = add_after(
        text,
        "        PlayerButtonSetting.applyOrder(mBinding.control.action.container, mActionButtons);\n",
        "        placeVideoSizeAfterChange();\n",
        "video size action order",
    )
    method = """    private void placeVideoSizeAfterChange() {
        ViewGroup container = mBinding.control.action.container;
        View change = mBinding.control.action.change2;
        View videoSize = mBinding.control.action.videoSize;
        if (container == null || change == null || videoSize == null) return;
        if (videoSize.getParent() == container) container.removeView(videoSize);
        int changeIndex = container.indexOfChild(change);
        if (changeIndex >= 0) container.addView(videoSize, changeIndex + 1);
    }

"""
    text = add_before(
        text,
        "    private void setPlayerKernel() {",
        method,
        "video size placement method",
    )
    return text


for path in (
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
):
    edit(path, patch_video_size_order)

print("[video-size] all source hooks applied")
