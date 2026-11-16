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

def patch_player_setting(text):
    if "isShowVideoSize()" in text:
        return text
    methods = """    public static boolean isShowVideoSize() {
        return Prefers.getBoolean("show_video_size", true);
    }

    public static void putShowVideoSize(boolean enabled) {
        Prefers.put("show_video_size", enabled);
    }

"""
    return add_before(text, "    public static int getAudioBackground() {", methods, "PlayerSetting")

edit("app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java", patch_player_setting)

for path in (
    "app/src/mobile/res/layout/fragment_setting_player.xml",
    "app/src/leanback/res/layout/activity_setting_player.xml",
):
    def patch_setting_layout(text):
        if '@+id/showVideoSize' in text:
            return text
        scroll_end = text.find("</androidx.core.widget.NestedScrollView>")
        if scroll_end < 0:
            raise SystemExit(f"[video-size] scroll container missing: {path}")
        parent_end = text.rfind("</androidx.appcompat.widget.LinearLayoutCompat>", 0, scroll_end)
        if parent_end < 0:
            raise SystemExit(f"[video-size] setting list missing: {path}")
        tv = "/leanback/" in path
        background = "@drawable/selector_item" if tv else "@drawable/shape_item"
        size = "18sp" if tv else "16sp"
        focus = ('                android:focusable="true"\n'
                 '                android:focusableInTouchMode="true"\n') if tv else ""
        row = f"""
            <androidx.appcompat.widget.LinearLayoutCompat
                android:id="@+id/showVideoSize"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="16dp"
                android:background="{background}"
                android:orientation="horizontal"
{focus}>
                <com.google.android.material.textview.MaterialTextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="16dp"
                    android:text="@string/player_show_video_size"
                    android:textColor="@color/white"
                    android:textSize="{size}" />

                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/showVideoSizeText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:gravity="end"
                    android:textColor="@color/white"
                    android:textSize="{size}"
                    tools:text="@string/setting_on" />

            </androidx.appcompat.widget.LinearLayoutCompat>

"""
        return text[:parent_end] + row + text[parent_end:]
    edit(path, patch_setting_layout)

for path in (
    "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/SettingPlayerFragment.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPlayerActivity.java",
):
    def patch_setting_java(text):
        text = add_before(
            text,
            "        mBinding.autoChangeText.setText(getSwitch(PlayerSetting.isAutoChange()));\n",
            "        mBinding.showVideoSizeText.setText(getSwitch(PlayerSetting.isShowVideoSize()));\n",
            path + " initView",
        )
        text = add_before(
            text,
            "        mBinding.autoChange.setOnClickListener(this::setAutoChange);\n",
            """        mBinding.showVideoSize.setOnClickListener(view -> {
            PlayerSetting.putShowVideoSize(!PlayerSetting.isShowVideoSize());
            mBinding.showVideoSizeText.setText(getSwitch(PlayerSetting.isShowVideoSize()));
        });
""",
            path + " initEvent",
        )
        return text
    edit(path, patch_setting_java)

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
        lifecycle = """    @Override
    protected void onResume() {
        super.onResume();
        refreshVideoSizeAfterStart();
    }

"""
        method = """    private int videoSizeRequestToken;

    private void refreshVideoSizeAfterStart() {
        int requestToken = ++videoSizeRequestToken;
        if (!PlayerSetting.isShowVideoSize()) {
            mBinding.control.action.videoSize.setVisibility(View.GONE);
            return;
        }
        mBinding.control.action.videoSize.setVisibility(View.GONE);
        mBinding.control.action.videoSize.post(() -> {
            if (requestToken != videoSizeRequestToken || isFinishing() || isDestroyed()
                    || !PlayerSetting.isShowVideoSize() || service() == null) return;
            PlayerManager current = player();
            if (current == null) return;
            VideoSizeProbe.query(this, current.getUrl(), current.getHeaders(), size -> runOnUiThread(() -> {
                if (requestToken != videoSizeRequestToken || isFinishing() || isDestroyed()
                        || !PlayerSetting.isShowVideoSize()) return;
                if (size == null) {
                    mBinding.control.action.videoSize.setVisibility(View.GONE);
                } else {
                    mBinding.control.action.videoSize.setText(getString(R.string.player_video_size, size));
                    mBinding.control.action.videoSize.setVisibility(View.VISIBLE);
                }
            }));
        });
    }

"""
        text = add_before(text, "    private void setPlayerKernel() {", lifecycle + method, path + " methods")
        return text
    edit(path, patch_video_activity)

print("[video-size] all source hooks applied")
