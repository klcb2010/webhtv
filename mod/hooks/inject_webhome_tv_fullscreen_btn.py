#!/usr/bin/env python3
"""TV WebHome: toolbar fullscreen button + title right-key focus + personal setting at end."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
MARKER = "WEBHOME_TV_FS_BTN"


def patch_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        print("[mod] WARN Setting.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "isWebHomeFullscreenBtn" in t:
        print("[mod] Setting already")
        return
    anchor = "public static void putWebHomeFullscreen(boolean fullscreen) {"
    i = t.find(anchor)
    if i < 0:
        print("[mod] WARN putWebHomeFullscreen not found")
        return
    j = t.find("}", i)
    j = t.find("\n", j) + 1
    block = """
    /** TV：是否显示 HTML 站点工具栏全屏按钮，默认开 */
    public static boolean isWebHomeFullscreenBtn() {
        return Prefers.getBoolean("web_home_fullscreen_btn", true);
    }

    public static void putWebHomeFullscreenBtn(boolean enabled) {
        Prefers.put("web_home_fullscreen_btn", enabled);
    }
"""
    path.write_text(t[:j] + block + t[j:], encoding="utf-8")
    print("[mod] Setting isWebHomeFullscreenBtn")


def patch_layout() -> None:
    path = ROOT / "app/src/leanback/res/layout/activity_home.xml"
    if not path.is_file():
        print("[mod] WARN activity_home.xml missing")
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/webHomeFullscreen"' in t:
        print("[mod] layout already")
        return
    needle = """        <com.google.android.material.textview.MaterialTextView
            android:id=\"@+id/clock\""""
    insert = """        <androidx.appcompat.widget.AppCompatImageView
            android:id=\"@+id/webHomeFullscreen\"
            android:layout_width=\"48dp\"
            android:layout_height=\"48dp\"
            android:layout_marginEnd=\"12dp\"
            android:background=\"@drawable/selector_item\"
            android:contentDescription=\"@string/play_fullscreen\"
            android:focusable=\"true\"
            android:focusableInTouchMode=\"true\"
            android:padding=\"8dp\"
            android:src=\"@drawable/ic_control_fullscreen\"
            android:visibility=\"gone\"
            android:nextFocusLeft=\"@id/title\"
            android:nextFocusRight=\"@id/clock\" />

""" + needle
    if needle not in t:
        print("[mod] WARN clock view not found")
        return
    path.write_text(t.replace(needle, insert, 1), encoding="utf-8")
    print("[mod] activity_home webHomeFullscreen")


def patch_custom_title_view() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/custom/CustomTitleView.java"
    if not path.is_file():
        print("[mod] WARN CustomTitleView missing")
        return
    t = path.read_text(encoding="utf-8")
    if "onTitleNavigateRight" in t:
        print("[mod] CustomTitleView already")
        return
    old = """    private void onKeyDown(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) onKeyUp();
        else if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) listener.setSite(getSite(false));
        else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) listener.setSite(getSite(true));
    }"""
    new = """    private void onKeyDown(KeyEvent event) {
        if (KeyUtil.isActionDown(event) && KeyUtil.isUpKey(event)) onKeyUp();
        else if (KeyUtil.isActionDown(event) && KeyUtil.isLeftKey(event)) {
            if (listener != null && listener.onTitleNavigateLeft()) return;
            listener.setSite(getSite(false));
        } else if (KeyUtil.isActionDown(event) && KeyUtil.isRightKey(event)) {
            if (listener != null && listener.onTitleNavigateRight()) return;
            listener.setSite(getSite(true));
        }
    }"""
    if old not in t:
        print("[mod] WARN CustomTitleView onKeyDown not matched")
        return
    t = t.replace(old, new, 1)
    old_if = """    public interface Listener extends SiteListener {

        void showDialog();

        void onRefresh();

        void reloadConfig();
    }"""
    new_if = """    public interface Listener extends SiteListener {

        void showDialog();

        void onRefresh();

        void reloadConfig();

        default boolean onTitleNavigateRight() {
            return false;
        }

        default boolean onTitleNavigateLeft() {
            return false;
        }
    }"""
    if old_if not in t:
        print("[mod] WARN Listener interface not matched")
        return
    t = t.replace(old_if, new_if, 1)
    path.write_text(t, encoding="utf-8")
    print("[mod] CustomTitleView HTML right-key guard", t.count("{"), t.count("}"))


def patch_home_activity() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java"
    if not path.is_file():
        print("[mod] WARN HomeActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if MARKER in t and "onTitleNavigateRight" in t:
        print("[mod] HomeActivity already")
        return

    if "updateWebHomeFullscreenBtn" not in t:
        method = """
    // WEBHOME_TV_FS_BTN
    private void updateWebHomeFullscreenBtn() {
        try {
            android.view.View btn = mBinding.getRoot().findViewById(com.fongmi.android.tv.R.id.webHomeFullscreen);
            if (btn == null) return;
            com.fongmi.android.tv.bean.Site home = getHome();
            boolean html = home != null && home.hasHomePage();
            boolean show = Setting.isWebHomeFullscreenBtn()
                    && Setting.isWebHomeFullscreen()
                    && html
                    && !TV_FULL.equals(webChromeMode)
                    && !TV_TOOLBAR_HIDDEN.equals(webChromeMode);
            btn.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
            if (show) {
                btn.setFocusable(true);
                btn.setFocusableInTouchMode(true);
                btn.setOnClickListener(v -> {
                    if (!Setting.isWebHomeFullscreen()) return;
                    applyTvChrome(TV_FULL);
                    updateWebHomeFullscreenBtn();
                });
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onTitleNavigateRight() {
        try {
            updateWebHomeFullscreenBtn();
            if (mWeb == null && !(getHome() != null && getHome().hasHomePage())) return false;
            if (!Setting.isWebHomeFullscreenBtn() || !Setting.isWebHomeFullscreen()) return false;
            if (TV_FULL.equals(webChromeMode) || TV_TOOLBAR_HIDDEN.equals(webChromeMode)) return false;
            com.fongmi.android.tv.bean.Site home = getHome();
            if (home == null || !home.hasHomePage()) return false;
            android.view.View btn = mBinding.getRoot().findViewById(com.fongmi.android.tv.R.id.webHomeFullscreen);
            if (btn == null || btn.getVisibility() != android.view.View.VISIBLE) return false;
            btn.setFocusable(true);
            btn.setFocusableInTouchMode(true);
            return btn.requestFocus();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean onTitleNavigateLeft() {
        return false;
    }

"""
        if "private void applyTvChrome(String mode)" in t:
            t = t.replace("private void applyTvChrome(String mode)", method + "\n    private void applyTvChrome(String mode)", 1)
            print("[mod] HomeActivity methods inserted")
        else:
            print("[mod] WARN applyTvChrome not found")

    old_apply = """    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        updateToolbarVisibility(webToolbarVisible);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
    }"""
    new_apply = """    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        updateToolbarVisibility(webToolbarVisible);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
        updateWebHomeFullscreenBtn();
    }"""
    if old_apply in t and t.count("updateWebHomeFullscreenBtn();") < 1:
        t = t.replace(old_apply, new_apply, 1)
        print("[mod] applyTvChrome hook")
    elif "updateWebHomeFullscreenBtn();" not in t:
        t2, n = re.subn(
            r"(private void applyTvChrome\(String mode\) \{[\s\S]*?if \(mWeb != null\) mWeb\.setViewport\(tvViewport\(mode\)\);)",
            r"\1\n        updateWebHomeFullscreenBtn();",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] applyTvChrome soft hook")

    if "public void onWebReady()" in t and "updateWebHomeFullscreenBtn();" not in t[t.find("onWebReady()"):t.find("onWebReady()") + 80]:
        t2, n = re.subn(
            r"(public void onWebReady\(\) \{)",
            r"\1\n        updateWebHomeFullscreenBtn();",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] onWebReady hook")


    # setSite: HTML 站立即显示按钮
    if "setSite(Site item)" in t and "updateWebHomeFullscreenBtn" in t:
        if "setHome(item);\n        updateWebHomeFullscreenBtn" not in t:
            t2, n = re.subn(
                r"(public void setSite\(Site item\) \{[\s\S]*?VodConfig\.get\(\)\.setHome\(item\);)",
                r"\1\n        updateWebHomeFullscreenBtn();",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] setSite hook")
    # onResume: 设置返回后立即生效
    if "protected void onResume()" in t and "updateWebHomeFullscreenBtn" in t:
        if "mWeb.onResume();\n        updateWebHomeFullscreenBtn" not in t and "onResume()\n        updateWebHomeFullscreenBtn" not in t:
            t2, n = re.subn(
                r"(protected void onResume\(\) \{\s*super\.onResume\(\);)",
                r"\1\n        updateWebHomeFullscreenBtn();",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] onResume hook")
            else:
                t2, n = re.subn(
                    r"(if \(mWeb != null\) mWeb\.onResume\(\);)",
                    r"\1\n        updateWebHomeFullscreenBtn();",
                    t,
                    count=1,
                )
                if n:
                    t = t2
                    print("[mod] onResume soft hook")
    # onWebLoading
    if "public void onWebLoading()" in t:
        if "onWebLoading()\n        updateWebHomeFullscreenBtn" not in t and "onWebLoading() {\n        updateWebHomeFullscreenBtn" not in t:
            t2, n = re.subn(
                r"(public void onWebLoading\(\) \{)",
                r"\1\n        updateWebHomeFullscreenBtn();",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] onWebLoading hook")

    path.write_text(t, encoding="utf-8")
    print("[mod] HomeActivity braces", t.count("{"), t.count("}"), "nav", "onTitleNavigateRight" in t)


def patch_personal_layout() -> None:
    path = ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml"
    if not path.is_file():
        print("[mod] WARN personal layout missing")
        return
    t = path.read_text(encoding="utf-8")
    if 'android:id="@+id/webHomeFullscreenBtn"' in t:
        t = re.sub(
            r"\s*<androidx\.appcompat\.widget\.LinearLayoutCompat\s+android:id=\"@\+id/webHomeFullscreenBtn\"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>",
            "",
            t,
            count=1,
        )
        print("[mod] personal layout removed old row")
    row = """
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id=\"@+id/webHomeFullscreenBtn\"
            android:layout_width=\"match_parent\"
            android:layout_height=\"wrap_content\"
            android:layout_marginTop=\"16dp\"
            android:background=\"@drawable/selector_item\"
            android:focusable=\"true\"
            android:focusableInTouchMode=\"true\"
            android:orientation=\"horizontal\"
            android:padding=\"8dp\">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width=\"0dp\"
                android:layout_height=\"wrap_content\"
                android:layout_weight=\"1\"
                android:text=\"html站点全屏按钮\"
                android:textColor=\"@color/white\"
                android:textSize=\"18sp\" />

            <com.google.android.material.textview.MaterialTextView
                android:id=\"@+id/webHomeFullscreenBtnText\"
                android:layout_width=\"wrap_content\"
                android:layout_height=\"wrap_content\"
                android:gravity=\"end\"
                android:textColor=\"@color/white\"
                android:textSize=\"16sp\" />
        </androidx.appcompat.widget.LinearLayoutCompat>
"""
    nsv_close = t.rfind("</androidx.core.widget.NestedScrollView>")
    if nsv_close < 0:
        print("[mod] WARN NestedScrollView close not found")
        return
    chunk = t[:nsv_close]
    last_llc = chunk.rfind("</androidx.appcompat.widget.LinearLayoutCompat>")
    if last_llc < 0:
        print("[mod] WARN outer LLC close not found")
        return
    t = t[:last_llc] + row + "\n" + t[last_llc:]
    path.write_text(t, encoding="utf-8")
    print("[mod] personal layout row at end")


def patch_personal_java() -> None:
    path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
    if not path.is_file():
        print("[mod] WARN SettingPersonalActivity missing")
        return
    t = path.read_text(encoding="utf-8")
    if "setWebHomeFullscreenBtn" in t:
        print("[mod] personal java already")
        return
    if "mBinding.homeSiteLock.setOnClickListener" in t:
        t = t.replace(
            "mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);",
            "mBinding.homeSiteLock.setOnClickListener(this::setHomeSiteLock);\n"
            "        try { mBinding.webHomeFullscreenBtn.setOnClickListener(this::setWebHomeFullscreenBtn); } catch (Throwable ignored) {}",
            1,
        )
    elif "try { mBinding.searchPanFirst" in t:
        t = t.replace(
            "try { mBinding.searchPanFirst",
            "try { mBinding.webHomeFullscreenBtn.setOnClickListener(this::setWebHomeFullscreenBtn); } catch (Throwable ignored) {}\n"
            "        try { mBinding.searchPanFirst",
            1,
        )
    for cand in (
        "mBinding.homeSiteLockText.setText",
        "mBinding.homeHistoryText.setText",
        "mBinding.globalHistoryText.setText",
    ):
        if cand in t:
            t = t.replace(
                cand,
                "try { mBinding.webHomeFullscreenBtnText.setText(getSwitch(Setting.isWebHomeFullscreenBtn())); } catch (Throwable ignored) {}\n"
                "        " + cand,
                1,
            )
            break
    method = """
    // WEBHOME_TV_FS_BTN
    private void setWebHomeFullscreenBtn(View view) {
        Setting.putWebHomeFullscreenBtn(!Setting.isWebHomeFullscreenBtn());
        try {
            mBinding.webHomeFullscreenBtnText.setText(getSwitch(Setting.isWebHomeFullscreenBtn()));
        } catch (Throwable ignored) {
        }
    }
"""
    last = t.rfind("}")
    if last > 0:
        t = t[:last] + method + t[last:]
    path.write_text(t, encoding="utf-8")
    print("[mod] personal setWebHomeFullscreenBtn", t.count("{"), t.count("}"))


def ensure_drawable() -> None:
    for sub in (
        "app/src/main/res/drawable/ic_control_fullscreen.xml",
        "app/src/mobile/res/drawable/ic_control_fullscreen.xml",
        "app/src/leanback/res/drawable/ic_control_fullscreen.xml",
    ):
        p = ROOT / sub
        if p.is_file():
            print("[mod] fullscreen icon at", sub)
            if "mobile" in sub:
                dst = ROOT / "app/src/main/res/drawable/ic_control_fullscreen.xml"
                if not dst.is_file():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    dst.write_text(p.read_text(encoding="utf-8"), encoding="utf-8")
                    print("[mod] copied icon to main")
            return
    print("[mod] WARN ic_control_fullscreen not in tree")


def main() -> None:
    print("[mod] inject_webhome_tv_fullscreen_btn ROOT", ROOT)
    patch_setting()
    patch_layout()
    patch_custom_title_view()
    patch_home_activity()
    patch_personal_layout()
    patch_personal_java()
    ensure_drawable()
    print("[mod] inject_webhome_tv_fullscreen_btn done")


if __name__ == "__main__":
    main()
