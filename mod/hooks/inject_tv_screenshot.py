#!/usr/bin/env python3
"""TV 端（leanback）移植手机端的截图入口。

- dialog_control.xml：控制面板按钮组里加「截图」按钮
- ControlDialog.java：点击 → 调 VideoActivity.capturePlaybackScreenshot()
- VideoActivity.java：新增 public capturePlaybackScreenshot()，走 PhoneScreenshot
  （MPV → mpv screenshot-to-file video 原画；EXO → 独立解码 + 黑帧判废 + Surface 回退）

手机端的 PhoneScreenshot.java 已随包放到 app/src/leanback/java/.../utils/，
与 mobile 那份同名同实现（不同 source set，互不冲突）。
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
OK: list[str] = []
ERR: list[str] = []

MARK = "TV_SCREENSHOT_V1"

TV_ACTIVITY_METHOD = '''
    /** MOD: TV 端截图入口（%s）。MPV 走 mpv 原画 screenshot，EXO 走独立解码 + Surface 回退。 */
    public void capturePlaybackScreenshot() {
        try {
            com.fongmi.android.tv.utils.PhoneScreenshot.capture(this, getExoView(),
                    new com.fongmi.android.tv.utils.PhoneScreenshot.Callback() {
                        @Override
                        public void onSuccess(android.net.Uri uri) {
                            try {
                                android.widget.Toast.makeText(VideoActivity.this, "截图已保存",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        public void onError(String message) {
                            try {
                                android.widget.Toast.makeText(VideoActivity.this,
                                        message == null || message.isEmpty() ? "截图失败" : message,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

''' % MARK

CONTROL_METHOD = '''    private void onScreenshot() {
        try {
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity instanceof com.fongmi.android.tv.ui.activity.VideoActivity) {
                ((com.fongmi.android.tv.ui.activity.VideoActivity) activity).capturePlaybackScreenshot();
            }
        } catch (Throwable ignored) {
        }
    }

'''

XML_BUTTON = '''                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/screenshotBtn"
                    style="@style/ControlSheetButton"
                    android:text="截图" />

'''


def patch_xml(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"missing {path}")
        return
    t = path.read_text(encoding="utf-8")
    if "screenshotBtn" in t:
        OK.append("dialog_control already has screenshot button")
        return
    anchor = ('''                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/panDiagnostic"
                    style="@style/ControlSheetButton"
                    android:contentDescription="@string/pan_diagnostic_title"
                    android:text="@string/pan_diagnostic_entry" />
''')
    if anchor not in t:
        ERR.append("dialog_control anchor (panDiagnostic) not found")
        return
    # panDiagnostic 之后、该按钮组闭合之前插入
    idx = t.index(anchor) + len(anchor)
    tail = t[idx:]
    close = "            </androidx.appcompat.widget.LinearLayoutCompat>"
    pos = tail.find(close)
    if pos < 0:
        ERR.append("dialog_control group close not found")
        return
    tail = tail[:pos] + XML_BUTTON + tail[pos:]
    path.write_text(t[:idx] + tail, encoding="utf-8")
    OK.append("dialog_control screenshot button added")


def patch_control_dialog(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"missing {path}")
        return
    t = path.read_text(encoding="utf-8")
    if "onScreenshot()" in t:
        OK.append("ControlDialog already patched")
        return
    hook = "        binding.panDiagnostic.setOnClickListener(v -> onPanDiagnostic());"
    if hook not in t:
        ERR.append("ControlDialog anchor (panDiagnostic listener) not found")
        return
    t = t.replace(hook, hook + "\n        binding.screenshotBtn.setOnClickListener(v -> onScreenshot());", 1)
    m = "    private void onPanDiagnostic() {"
    if m not in t:
        ERR.append("ControlDialog anchor (onPanDiagnostic method) not found")
        return
    t = t.replace(m, CONTROL_METHOD + m, 1)
    # 遥控器聚焦列表：不加进去就永远聚焦不到这个新按钮
    focus_old = "                binding.panDiagnostic,\n"
    if focus_old in t:
        t = t.replace(focus_old, "                binding.panDiagnostic, binding.screenshotBtn,\n", 1)
        OK.append("ControlDialog remote-focus list updated")
    else:
        ERR.append("ControlDialog anchor (bindRemoteFocus list) not found")
    path.write_text(t, encoding="utf-8")
    OK.append("ControlDialog listener + method added")


def patch_video_activity(path: Path) -> None:
    if not path.is_file():
        ERR.append(f"missing {path}")
        return
    t = path.read_text(encoding="utf-8")
    if MARK in t:
        OK.append("VideoActivity already patched")
        return
    anchor = '''    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }
'''
    if anchor not in t:
        ERR.append("VideoActivity anchor (getExoView) not found")
        return
    t = t.replace(anchor, anchor + TV_ACTIVITY_METHOD, 1)
    path.write_text(t, encoding="utf-8")
    OK.append("VideoActivity capturePlaybackScreenshot added")


def main() -> None:
    print("[mod] inject_tv_screenshot ROOT", ROOT)
    patch_xml(ROOT / "app/src/leanback/res/layout/dialog_control.xml")
    patch_control_dialog(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/dialog/ControlDialog.java")
    patch_video_activity(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java")
    for line in OK:
        print("[mod] OK", line)
    for line in ERR:
        print("[mod] ERR", line)
    if ERR:
        sys.exit(1)
    print("[mod] inject_tv_screenshot SUCCESS")


if __name__ == "__main__":
    main()
