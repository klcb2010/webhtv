#!/usr/bin/env python3
"""TV 端（leanback）截图入口移植 + 个性设置补项。全部幂等。

1) dialog_control.xml        —— 底部控制面板加「截图」（次要入口）
2) ControlDialog.java        —— 上面那个按钮的点击 + 遥控器聚焦列表
3) VideoActivity.java        —— 新增 capturePlaybackScreenshot()，并挂到控制栏
                                （view_control_vod_action 那一排的 @+id/screenshot）
4) SettingPersonalActivity   —— ①「播放器MPV截图」右侧初值
                                ②新增「MPV字体默认放大」「播放器临时调试日志」两项
5) activity_setting_personal.xml —— 上面两项的布局（加在最后一页末尾）

注意：控制栏那一排的 @+id/screenshot 节点本来就在 mod 的
view_control_vod_action.xml 里，但被 android:visibility="gone" 隐掉了，
由覆盖包提供去掉 gone 的版本，本 hook 只负责补点击监听。
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
OK: list[str] = []
ERR: list[str] = []

MARK = "TV_SCREENSHOT_V1"

TV_ACTIVITY_METHOD = """
    /** MOD: TV 端截图入口（%s）。MPV 走 mpv 原画 screenshot；EXO 直接截当前 Surface。 */
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

""" % MARK

CONTROL_METHOD = """    private void onScreenshot() {
        try {
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity instanceof com.fongmi.android.tv.ui.activity.VideoActivity) {
                ((com.fongmi.android.tv.ui.activity.VideoActivity) activity).capturePlaybackScreenshot();
            }
        } catch (Throwable ignored) {
        }
    }

"""

XML_BUTTON = """                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/screenshotBtn"
                    style="@style/ControlSheetButton"
                    android:text="截图" />

"""

PERSONAL_METHODS = """    /** MPV_BRANCH: 点一下切下一个倍率（1.00 / 1.15 / 1.20 / 1.30），立即作用于 mpv 字幕 */
    private void setMpvFontScale(View view) {
        try {
            com.fongmi.android.tv.setting.Setting.cycleMpvFontScale();
            mBinding.mpvFontScaleText.setText(com.fongmi.android.tv.setting.Setting.getMpvFontScaleLabel());
        } catch (Throwable ignored) {
        }
        try { com.fongmi.android.tv.player.mpv.MpvSubtitleStylePolicy.applyRuntimeToActive(); } catch (Throwable ignored) {}
        try { com.fongmi.android.tv.player.exo.ExoUtil.reapplySubtitleStyle(); } catch (Throwable ignored) {}
    }

    /** MPV_BRANCH: 播放器临时调试日志，点一下立即生效（内存态，不落盘） */
    private void setDebugLog(View view) {
        boolean next = !com.fongmi.android.tv.utils.ModDebugLog.isRuntimeOverrideOn();
        try {
            com.fongmi.android.tv.utils.ModDebugLog.setRuntimeOverride(next);
            mBinding.debugLogText.setText(getSwitch(next));
        } catch (Throwable ignored) {
        }
    }

"""

PERSONAL_XML = """        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/mpvFontScale"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="horizontal"
            android:padding="8dp"
            android:visibility="visible">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="MPV字体默认放大"
                android:textColor="@color/white"
                android:textSize="16sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/mpvFontScaleText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="16sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>

        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/debugLog"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="24dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:focusableInTouchMode="true"
            android:orientation="horizontal"
            android:padding="8dp"
            android:visibility="visible">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="播放器临时调试日志"
                android:textColor="@color/white"
                android:textSize="16sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/debugLogText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="16sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>

"""


def patch_dialog_xml(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if "screenshotBtn" in t:
        OK.append("dialog_control already has screenshot button")
        return
    anchor = '''                <com.google.android.material.textview.MaterialTextView
                    android:id="@+id/panDiagnostic"
                    style="@style/ControlSheetButton"
                    android:contentDescription="@string/pan_diagnostic_title"
                    android:text="@string/pan_diagnostic_entry" />
'''
    if anchor not in t:
        ERR.append("dialog_control anchor (panDiagnostic) not found")
        return
    idx = t.index(anchor) + len(anchor)
    tail = t[idx:]
    close = "            </androidx.appcompat.widget.LinearLayoutCompat>"
    pos = tail.find(close)
    if pos < 0:
        ERR.append("dialog_control group close not found")
        return
    path.write_text(t[:idx] + tail[:pos] + XML_BUTTON + tail[pos:], encoding="utf-8")
    OK.append("dialog_control screenshot button added")


def patch_control_dialog(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
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
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if MARK in t:
        OK.append("VideoActivity already patched")
        return
    anchor = """    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }
"""
    if anchor not in t:
        ERR.append("VideoActivity anchor (getExoView) not found")
        return
    t = t.replace(anchor, anchor + TV_ACTIVITY_METHOD, 1)
    hook = "        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());"
    if hook in t:
        t = t.replace(
            hook,
            hook + "\n        mBinding.control.action.screenshot.setOnClickListener(view -> capturePlaybackScreenshot());",
            1,
        )
        OK.append("VideoActivity control-bar screenshot listener added")
    else:
        ERR.append("VideoActivity anchor (control.action.repeat listener) not found")
    path.write_text(t, encoding="utf-8")
    OK.append("VideoActivity capturePlaybackScreenshot added")


def patch_screenshot_visibility(path: Path, anchor: str, tag: str) -> None:
    """把截图按钮的显隐挂到 applyActionButtonVisibility() 上。

    anchor: applyActionButtonVisibility() 的方法头到某一行为止的整段（在其后追加一行调用）
    """
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if "updateScreenshotAction" in t:
        OK.append(tag + " screenshot visibility already wired")
        return
    if anchor not in t:
        ERR.append(tag + " anchor for applyActionButtonVisibility not found")
        return
    t = t.replace(anchor, anchor + "\n        updateScreenshotAction();", 1)
    # 方法本体插在 applyActionButtonVisibility 之前
    m = "    private void updateCustomButtonVisibility() {"
    if m not in t:
        ERR.append(tag + " updateCustomButtonVisibility not found")
        return
    VIS = """    /** MOD: 截图按钮只在「MPV 播放 + MPV截图未关闭」时出现（EXO 不显示） */
    private void updateScreenshotAction() {
        try {
            boolean show = player() != null && player().isMpv()
                    && com.fongmi.android.tv.setting.Setting.getMpvScreenshotMode()
                    != com.fongmi.android.tv.setting.Setting.SCREENSHOT_OFF;
            mBinding.control.action.screenshot.setVisibility(show ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) {
        }
    }

"""
    t = t.replace(m, VIS + m, 1)
    path.write_text(t, encoding="utf-8")
    OK.append(tag + " screenshot visibility wired")


def patch_mobile_video_activity(path: Path) -> None:
    """手机端播放页：加截图入口 + 控制栏监听 + 显隐规则（与 TV 对齐）。"""
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if MARK in t:
        OK.append("mobile VideoActivity already patched")
        return
    anchor = """    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }
"""
    if anchor not in t:
        ERR.append("mobile VideoActivity anchor (getExoView) not found")
        return
    t = t.replace(anchor, anchor + TV_ACTIVITY_METHOD, 1)
    hook = "        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());"
    if hook in t:
        t = t.replace(
            hook,
            hook + "\n        mBinding.control.action.screenshot.setOnClickListener(view -> capturePlaybackScreenshot());",
            1,
        )
        OK.append("mobile VideoActivity control-bar screenshot listener added")
    else:
        ERR.append("mobile VideoActivity anchor (control.action.repeat listener) not found")
    path.write_text(t, encoding="utf-8")
    OK.append("mobile VideoActivity capturePlaybackScreenshot added")


def patch_personal_activity(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")

    # ① 点击监听
    ev = "        try { mBinding.kaitiScale.setOnClickListener(this::setKaiTiScale); } catch (Throwable ignored) {}"
    if "setMpvFontScale)" in t:
        OK.append("TV personal listeners already added")
    elif ev in t:
        t = t.replace(
            ev,
            ev
            + "\n        try { mBinding.mpvFontScale.setOnClickListener(this::setMpvFontScale); } catch (Throwable ignored) {}"
            + "\n        try { mBinding.debugLog.setOnClickListener(this::setDebugLog); } catch (Throwable ignored) {}",
            1,
        )
        OK.append("TV personal listeners added")
    else:
        ERR.append("TV personal anchor (initEvent kaitiScale) not found")

    # ② 初值
    init = "        try { mBinding.kaitiScaleText.setText(Setting.getKaiTiScaleLabel(true)); } catch (Throwable ignored) {}"
    if "TV_MPVFONT_INIT" in t:
        OK.append("TV personal texts already initialized")
    elif init in t:
        t = t.replace(
            init,
            init
            + "\n        // TV_MPVFONT_INIT: 新增两项的右侧值 + 「播放器MPV截图」初值（原来只在点击时刷新）"
            + "\n        try { mBinding.mpvFontScaleText.setText(Setting.getMpvFontScaleLabel()); } catch (Throwable ignored) {}"
            + "\n        try { mBinding.debugLogText.setText(getSwitch(com.fongmi.android.tv.utils.ModDebugLog.isRuntimeOverrideOn())); } catch (Throwable ignored) {}"
            + "\n        try { mBinding.mpvScreenshotBtnText.setText(Setting.getMpvScreenshotModeLabel()); } catch (Throwable ignored) {}",
            1,
        )
        OK.append("TV personal texts initialized")
    else:
        ERR.append("TV personal anchor (initView kaitiScaleText) not found")

    # ③ 两个处理方法
    m = "    private void setPlaybackScreenshotBtn(View view) {"
    if "private void setMpvFontScale(View view)" in t:
        OK.append("TV personal methods already added")
    elif m in t:
        t = t.replace(m, PERSONAL_METHODS + m, 1)
        OK.append("TV personal methods added")
    else:
        ERR.append("TV personal anchor (setPlaybackScreenshotBtn) not found")

    path.write_text(t, encoding="utf-8")


def patch_personal_layout(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/mpvFontScale"' in t:
        OK.append("TV personal layout already patched")
        return
    # 锚点：最后一项 hdrToSdr 的结束 + 根容器关闭
    anchor = """        </androidx.appcompat.widget.LinearLayoutCompat>

</androidx.appcompat.widget.LinearLayoutCompat>
</androidx.core.widget.NestedScrollView>"""
    if anchor not in t:
        ERR.append("TV personal layout anchor (hdrToSdr tail) not found")
        return
    new = ("        </androidx.appcompat.widget.LinearLayoutCompat>\n\n"
           + PERSONAL_XML
           + "</androidx.appcompat.widget.LinearLayoutCompat>\n</androidx.core.widget.NestedScrollView>")
    t = t.replace(anchor, new, 1)
    # 原最后一项的 24dp 底边距挪给新末项，避免出现两段大空白
    t = t.replace('android:id="@+id/hdrToSdr"', 'android:id="@+id/hdrToSdr"', 1)
    path.write_text(t, encoding="utf-8")
    OK.append("TV personal layout: mpvFontScale + debugLog added")


def main() -> None:
    print("[mod] inject_tv_screenshot ROOT", ROOT)
    # 底部控制面板不加按钮：截图入口只放在控制栏那一排（view_control_vod_action），
    # 由 updateScreenshotAction() 统一控制显隐，避免出现「面板里有、控制栏里没有」的两套入口。
    # patch_dialog_xml(...)
    # patch_control_dialog(...)
    patch_video_activity(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java")
    HEAD = (
        "    private void updateCustomButtonVisibility() {\n"
        "        updateCustomButtonStates();"
    )
    patch_screenshot_visibility(
        ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        HEAD,
        "TV VideoActivity",
    )
    patch_mobile_video_activity(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java")
    patch_screenshot_visibility(
        ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        HEAD,
        "mobile VideoActivity",
    )
    patch_personal_activity(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java")
    patch_personal_layout(ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml")
    for line in OK:
        print("[mod] OK", line)
    for line in ERR:
        print("[mod] ERR", line)
    if ERR:
        sys.exit(1)
    print("[mod] inject_tv_screenshot SUCCESS")


if __name__ == "__main__":
    main()
