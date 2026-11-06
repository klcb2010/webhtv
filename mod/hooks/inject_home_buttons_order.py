#!/usr/bin/env python3
"""Wire TV home function-button order (HomeButton + dialog + personal setting)."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

HOME = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java"
SPA = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
SPA_LAYOUT = ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml"

NEW_SET_FUNC = """    private void setFunc() {
        List<Func> items = new ArrayList<>();
        for (com.fongmi.android.tv.bean.HomeButton button : com.fongmi.android.tv.bean.HomeButton.getVisibleButtons()) {
            items.add(Func.create(button.getResId()));
        }
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
    }"""


def patch_home() -> None:
    if not HOME.is_file():
        print("[mod] WARN missing HomeActivity")
        return
    t = HOME.read_text(encoding="utf-8")
    if "HomeButton.getVisibleButtons" in t:
        print("[mod] HomeActivity setFunc already wired")
        return
    old = """    private void setFunc() {
        List<Func> items = new ArrayList<>();
        // 关闭「首页最近观看」时显示历史入口；关闭「默认加载点播」时显示点播入口（历史在上、点播在下）
        if (!Setting.isHomeHistory()) items.add(Func.create(R.string.home_history_button));
        if (!Setting.isHomeVodAutoLoad()) items.add(Func.create(R.string.home_vod));
        if (LiveConfig.hasUrl()) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_push));
        items.add(Func.create(R.string.home_setting));
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
    }"""
    if old in t:
        t = t.replace(old, NEW_SET_FUNC, 1)
        HOME.write_text(t, encoding="utf-8")
        print("[mod] HomeActivity setFunc -> HomeButton order")
        return
    m = re.search(
        r"private void setFunc\(\) \{\s*List<Func> items = new ArrayList<>\(\);[\s\S]*?"
        r"mFuncAdapter\.setItems\(items, new BaseDiffCallback<Func>\(\)\);\s*\}",
        t,
    )
    if not m:
        print("[mod] ERROR HomeActivity setFunc pattern not found")
        sys.exit(1)
    t = t[: m.start()] + NEW_SET_FUNC + t[m.end() :]
    HOME.write_text(t, encoding="utf-8")
    print("[mod] HomeActivity setFunc regex wired")


def patch_spa_layout() -> None:
    if not SPA_LAYOUT.is_file():
        print("[mod] WARN missing activity_setting_personal.xml")
        return
    t = SPA_LAYOUT.read_text(encoding="utf-8")
    if 'android:id="@+id/homeButtons"' in t:
        print("[mod] layout homeButtons already")
        return
    row = """
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/homeButtons"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:background="@drawable/selector_item"
            android:focusable="true"
            android:orientation="horizontal"
            android:padding="8dp"
            android:visibility="visible">

            <com.google.android.material.textview.MaterialTextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="@string/setting_home_buttons"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/homeButtonsText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="18sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>
"""
    end_pat = re.search(
        r'android:id="@\+id/playDirectPanPreferText"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>',
        t,
    )
    if not end_pat:
        print("[mod] ERROR cannot find playDirectPanPrefer block end")
        sys.exit(1)
    t = t[: end_pat.end()] + "\n" + row + t[end_pat.end() :]
    SPA_LAYOUT.write_text(t, encoding="utf-8")
    print("[mod] layout homeButtons row added")


def patch_spa_java() -> None:
    if not SPA.is_file():
        print("[mod] WARN missing SettingPersonalActivity")
        return
    t = SPA.read_text(encoding="utf-8")
    if "HomeButtonDialog" in t and "onHomeButtons" in t:
        print("[mod] SettingPersonal already has home buttons")
        return
    if "import com.fongmi.android.tv.bean.HomeButton;" not in t:
        t = t.replace(
            "import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;",
            "import com.fongmi.android.tv.bean.HomeButton;\n"
            "import com.fongmi.android.tv.databinding.ActivitySettingPersonalBinding;\n"
            "import com.fongmi.android.tv.ui.dialog.HomeButtonDialog;",
            1,
        )
    if "import com.fongmi.android.tv.event.RefreshEvent;" not in t:
        t = t.replace(
            "import com.fongmi.android.tv.bean.HomeButton;",
            "import com.fongmi.android.tv.bean.HomeButton;\n"
            "import com.fongmi.android.tv.event.RefreshEvent;",
            1,
        )
    if "homeButtons.setOnClickListener" not in t:
        anchor = (
            "try { mBinding.playDirectPanPrefer.setOnClickListener(this::setPlayDirectPanPrefer); } "
            "catch (Throwable ignored) {}"
        )
        add = (
            anchor
            + "\n        try { mBinding.homeButtons.setOnClickListener(this::onHomeButtons); } "
            + "catch (Throwable ignored) {}"
        )
        if anchor in t:
            t = t.replace(anchor, add, 1)
        else:
            t = re.sub(
                r"(try \{ mBinding\.\w+\.setOnClickListener\(this::\w+\); \} catch \(Throwable ignored\) \{\}\n)"
                r"(\s*setText\(\);)",
                r"\1        try { mBinding.homeButtons.setOnClickListener(this::onHomeButtons); } "
                r"catch (Throwable ignored) {}\n\2",
                t,
                count=1,
            )
    # summary text in init display block
    if "homeButtonsText.setText" not in t:
        anchor_txt = (
            "try { mBinding.playDirectPanPreferText.setText(getSwitch(Setting.isPlayDirectPanPrefer())); } "
            "catch (Throwable ignored) {}"
        )
        add_txt = (
            anchor_txt
            + "\n        try {\n"
            + "            mBinding.homeButtonsText.setText(getString(R.string.home_buttons_selected, "
            + "HomeButton.getButtons().size(), HomeButton.all().size()));\n"
            + "        } catch (Throwable ignored) {}\n"
        )
        if anchor_txt in t:
            t = t.replace(anchor_txt, add_txt, 1)
        else:
            # fallback near end of init text assignments
            t = re.sub(
                r"(try \{ mBinding\.\w+Text\.setText\([^;]+\); \} catch \(Throwable ignored\) \{\}\n)",
                r"\1        try {\n"
                r"            mBinding.homeButtonsText.setText(getString(R.string.home_buttons_selected, "
                r"HomeButton.getButtons().size(), HomeButton.all().size()));\n"
                r"        } catch (Throwable ignored) {}\n",
                t,
                count=1,
            )
    if "void onHomeButtons" not in t:
        method = (
            "\n    private void onHomeButtons(android.view.View view) {\n"
            "        HomeButtonDialog.show(this, () -> {\n"
            "            try {\n"
            "                mBinding.homeButtonsText.setText(getString(R.string.home_buttons_selected, "
            "HomeButton.getButtons().size(), HomeButton.all().size()));\n"
            "            } catch (Throwable ignored) {}\n"
            "            try { RefreshEvent.home(); } catch (Throwable ignored) {}\n"
            "        });\n"
            "    }\n"
        )
        # insert before final class closing brace
        last = t.rfind("\n}")
        if last < 0:
            print("[mod] ERROR cannot find class end in SettingPersonalActivity")
            sys.exit(1)
        t = t[:last] + method + t[last:]
    SPA.write_text(t, encoding="utf-8")
    print("[mod] SettingPersonalActivity home buttons entry")


def main() -> None:
    print("[mod] inject_home_buttons_order ROOT", ROOT)
    patch_home()
    patch_spa_layout()
    patch_spa_java()
    print("[mod] inject_home_buttons_order done")


if __name__ == "__main__":
    main()
