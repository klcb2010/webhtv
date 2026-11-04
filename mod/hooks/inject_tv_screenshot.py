#!/usr/bin/env python3
"""手机端播放页截图入口（TV 端已按用户要求全部回退，不再处理 leanback）。

改动（幂等，按方法名定位，不用整段文本锚点）：
1) app/src/mobile/.../ui/activity/VideoActivity.java
   - 新增 public capturePlaybackScreenshot()
   - 控制栏 @+id/screenshot 的点击监听
   - updateScreenshotAction()：只在「MPV + MPV截图未关」时显示按钮
2) app/src/leanback/.../ui/activity/SettingPersonalActivity.java
   - 「播放器MPV截图」右侧初值（原来只有点击才刷新）
   - 新增「MPV字体默认放大」一项（TV 端不加调试日志开关）
3) app/src/leanback/res/layout/activity_setting_personal.xml —— 上面那一项的布局

TV 播放页（leanback VideoActivity）、leanback 版 PhoneScreenshot、
view_control_vod_action.xml 的改动，全部已移除。
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
OK: list[str] = []
ERR: list[str] = []

MARK = "MOBILE_SCREENSHOT_V1"

CAPTURE_METHOD = """
    /** MOD: 截图入口（%s）。MPV 走 mpv 原画 screenshot；EXO 不支持截图。 */
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

VISIBILITY_METHOD = """    /** MOD: 截图按钮只在「MPV 播放 + MPV截图未关闭」时出现（EXO 不显示） */
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

"""

PERSONAL_XML = """        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/mpvFontScale"
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

"""


PERSONAL_CONTRAST_XML = """        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/mpvScreenshotContrast"
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
                android:text="MPV截图对比度"
                android:textColor="@color/white"
                android:textSize="16sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/mpvScreenshotContrastText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="16sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>

"""

def insert_into_method(t: str, name: str, line: str) -> tuple[str, bool]:
    i = t.find("private void %s(" % name)
    if i < 0:
        return t, False
    b = t.find("{", i)
    if b < 0:
        return t, False
    return t[: b + 1] + "\n        " + line + t[b + 1:], True


def patch_mobile_video_activity(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")

    if MARK not in t:
        anchor = """    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }
"""
        if anchor in t:
            t = t.replace(anchor, anchor + CAPTURE_METHOD, 1)
            OK.append("mobile VideoActivity capturePlaybackScreenshot added")
        else:
            ERR.append("mobile VideoActivity anchor (getExoView) not found")

    if "action.screenshot.setOnClickListener" not in t:
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

    if "updateScreenshotAction" not in t:
        done = False
        for name in ("updateCustomButtonVisibility", "applyActionButtonVisibility", "forceHideConfiguredActionButtons"):
            t2, ok = insert_into_method(t, name, "updateScreenshotAction();")
            if ok:
                i = t2.find("private void %s(" % name)
                t = t2[:i] + VISIBILITY_METHOD + t2[i:]
                OK.append("mobile VideoActivity screenshot visibility wired via " + name + "()")
                done = True
                break
        if not done:
            ERR.append("mobile VideoActivity no place to wire updateScreenshotAction()")

    path.write_text(t, encoding="utf-8")


def patch_tv_personal_activity(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")

    ev = "        try { mBinding.kaitiScale.setOnClickListener(this::setKaiTiScale); } catch (Throwable ignored) {}"
    if "setMpvFontScale)" in t:
        OK.append("TV personal listener already added")
    elif ev in t:
        t = t.replace(
            ev,
            ev + "\n        try { mBinding.mpvFontScale.setOnClickListener(this::setMpvFontScale); } catch (Throwable ignored) {}",
            1,
        )
        OK.append("TV personal listener added")
    else:
        ERR.append("TV personal anchor (initEvent kaitiScale) not found")

    init = "        try { mBinding.kaitiScaleText.setText(Setting.getKaiTiScaleLabel(true)); } catch (Throwable ignored) {}"
    BTN_LINE = "        try { mBinding.mpvScreenshotBtnText.setText(Setting.getMpvScreenshotModeLabel()); } catch (Throwable ignored) {}"
    if "TV_MPVFONT_INIT" not in t and init in t:
        t = t.replace(
            init,
            init
            + "\n        // TV_MPVFONT_INIT: 新增项与「播放器MPV截图」的右侧值（原来只在点击时刷新）"
            + "\n        try { mBinding.mpvFontScaleText.setText(Setting.getMpvFontScaleLabel()); } catch (Throwable ignored) {}"
            + "\n" + BTN_LINE,
            1,
        )
    if BTN_LINE in t:
        OK.append("TV personal texts initialized")
    else:
        ERR.append("TV personal anchor (initView kaitiScaleText) not found")

    m = "    private void setPlaybackScreenshotBtn(View view) {"
    if "private void setMpvFontScale(View view)" in t:
        OK.append("TV personal method already added")
    elif m in t:
        t = t.replace(m, PERSONAL_METHODS + m, 1)
        OK.append("TV personal method added")
    else:
        ERR.append("TV personal anchor (setPlaybackScreenshotBtn) not found")

    path.write_text(t, encoding="utf-8")


def patch_tv_personal_contrast_layout(path: Path) -> None:
    """TV 个性设置：「MPV截图亮度」下面加「MPV截图对比度」。"""
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/mpvScreenshotContrast"' in t:
        OK.append("TV personal contrast layout already added")
        return
    key = 'android:id="@+id/mpvScreenshotBrightnessBoost"'
    if key not in t:
        ERR.append("TV personal anchor (mpvScreenshotBrightnessBoost) not found")
        return
    i = t.index(key)
    close = "        </androidx.appcompat.widget.LinearLayoutCompat>"
    j = t.index(close, i) + len(close)
    t = t[:j] + "\n\n" + PERSONAL_CONTRAST_XML.rstrip("\n") + t[j:]
    path.write_text(t, encoding="utf-8")
    OK.append("TV personal layout: mpvScreenshotContrast added")


def hide_tv_mpv_screenshot_items(path: Path) -> None:
    """TV 端彻底没有 MPV 截图：把三项设成 gone（保留节点，否则 ViewBinding 字段消失会编译不过）。"""
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if "TV_SCREENSHOT_HIDDEN" in t:
        OK.append("TV mpv screenshot items already hidden")
        return
    for item_id in ("playbackScreenshotBtn", "mpvScreenshotBtn", "mpvScreenshotBrightnessBoost"):
        key = 'android:id="@+id/%s"' % item_id
        if key not in t:
            continue
        i = t.index(key)
        seg_end = t.index(">", i)
        seg = t[i:seg_end]
        if 'android:visibility="visible"' in seg:
            seg = seg.replace('android:visibility="visible"', 'android:visibility="gone"', 1)
        elif "android:visibility=" not in seg:
            seg = seg.replace('\n            android:padding="8dp"',
                              '\n            android:padding="8dp"\n            android:visibility="gone"', 1)
        t = t[:i] + seg + t[seg_end:]
    t = t.replace("</androidx.core.widget.NestedScrollView>",
                  "<!-- TV_SCREENSHOT_HIDDEN: MPV 截图三项已隐藏 -->\n</androidx.core.widget.NestedScrollView>", 1)
    path.write_text(t, encoding="utf-8")
    OK.append("TV mpv screenshot items hidden")


def patch_tv_personal_layout(path: Path) -> None:
    if not path.is_file():
        ERR.append("missing " + str(path))
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/mpvFontScale"' in t:
        OK.append("TV personal layout already patched")
        return
    anchor = """        </androidx.appcompat.widget.LinearLayoutCompat>

</androidx.appcompat.widget.LinearLayoutCompat>
</androidx.core.widget.NestedScrollView>"""
    if anchor not in t:
        ERR.append("TV personal layout anchor not found")
        return
    new = ("        </androidx.appcompat.widget.LinearLayoutCompat>\n\n"
           + PERSONAL_XML
           + "</androidx.appcompat.widget.LinearLayoutCompat>\n</androidx.core.widget.NestedScrollView>")
    path.write_text(t.replace(anchor, new, 1), encoding="utf-8")
    OK.append("TV personal layout: mpvFontScale added")


def main() -> None:
    print("[mod] inject_playback_screenshot ROOT", ROOT)
    patch_mobile_video_activity(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java")
    patch_tv_personal_activity(ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java")
    patch_tv_personal_layout(ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml")
    hide_tv_mpv_screenshot_items(ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml")
    for line in OK:
        print("[mod] OK", line)
    for line in ERR:
        print("[mod] ERR", line)
    if ERR:
        sys.exit(1)
    print("[mod] inject_playback_screenshot SUCCESS")


if __name__ == "__main__":
    main()
