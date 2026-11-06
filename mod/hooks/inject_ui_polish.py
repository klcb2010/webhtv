#!/usr/bin/env python3
"""Control transparent (layout default + showControl), SPA switch, live OSD z-order."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

HELPER = """
    private void applyControlTransparentBg() {
        try {
            android.view.View root = mBinding.control.getRoot();
            if (com.fongmi.android.tv.setting.Setting.isControlTransparent()) {
                root.setBackgroundResource(R.drawable.shape_controller_transparent);
            } else {
                root.setBackgroundResource(R.drawable.shape_controller_opaque);
            }
        } catch (Throwable ignored) {
        }
    }
"""


def patch_setting() -> None:
    path = ROOT / "app/src/main/java/com/fongmi/android/tv/setting/Setting.java"
    if not path.is_file():
        print("[mod] WARN Setting.java missing")
        return
    t = path.read_text(encoding="utf-8")
    if "isControlTransparent" in t:
        print("[mod] Setting control transparent already")
        return
    block = """
    public static boolean isControlTransparent() {
        return Prefers.getBoolean("control_transparent", true);
    }

    public static void putControlTransparent(boolean value) {
        Prefers.put("control_transparent", value);
    }
"""
    last = t.rfind("\n}")
    path.write_text(t[:last] + block + t[last:], encoding="utf-8")
    print("[mod] Setting control transparent helpers")


def patch_spa() -> None:
    layout = ROOT / "app/src/leanback/res/layout/activity_setting_personal.xml"
    java = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
    if layout.is_file():
        t = layout.read_text(encoding="utf-8")
        if 'android:id="@+id/controlTransparent"' not in t:
            row = """
        <androidx.appcompat.widget.LinearLayoutCompat
            android:id="@+id/controlTransparent"
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
                android:text="@string/setting_control_transparent"
                android:textColor="@color/white"
                android:textSize="18sp" />

            <com.google.android.material.textview.MaterialTextView
                android:id="@+id/controlTransparentText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:gravity="end"
                android:textColor="@color/white"
                android:textSize="18sp" />
        </androidx.appcompat.widget.LinearLayoutCompat>
"""
            m = re.search(
                r'android:id="@\+id/homeButtonsText"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>',
                t,
            )
            if not m:
                m = re.search(
                    r'android:id="@\+id/playDirectPanPreferText"[\s\S]*?</androidx\.appcompat\.widget\.LinearLayoutCompat>',
                    t,
                )
            if not m:
                print("[mod] ERROR SPA layout anchor")
                sys.exit(1)
            t = t[: m.end()] + "\n" + row + t[m.end() :]
            layout.write_text(t, encoding="utf-8")
            print("[mod] SPA layout controlTransparent")
    if not java.is_file():
        return
    t = java.read_text(encoding="utf-8")
    if "setControlTransparent" not in t:
        if "controlTransparent.setOnClickListener" not in t:
            for anchor in [
                "try { mBinding.homeButtons.setOnClickListener(this::onHomeButtons); } catch (Throwable ignored) {}",
                "try { mBinding.playDirectPanPrefer.setOnClickListener(this::setPlayDirectPanPrefer); } catch (Throwable ignored) {}",
            ]:
                if anchor in t:
                    t = t.replace(
                        anchor,
                        anchor
                        + "\n        try { mBinding.controlTransparent.setOnClickListener(this::setControlTransparent); } "
                        "catch (Throwable ignored) {}",
                        1,
                    )
                    break
        if "controlTransparentText.setText" not in t:
            t = re.sub(
                r"(try \{ mBinding\.playDirectPanPreferText\.setText\([^\n]+\); \} catch \(Throwable ignored\) \{\})",
                r"\1\n        try { mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent())); } "
                r"catch (Throwable ignored) {}",
                t,
                count=1,
            )
            if "controlTransparentText.setText" not in t:
                t = re.sub(
                    r"(mBinding\.homeButtonsText\.setText\([^\n]+\);)",
                    r"\1\n            mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent()));",
                    t,
                    count=1,
                )
        method = """
    private void setControlTransparent(android.view.View view) {
        Setting.putControlTransparent(!Setting.isControlTransparent());
        try {
            mBinding.controlTransparentText.setText(getSwitch(Setting.isControlTransparent()));
        } catch (Throwable ignored) {
        }
    }
"""
        last = t.rfind("\n}")
        t = t[:last] + method + t[last:]
        java.write_text(t, encoding="utf-8")
        print("[mod] SPA controlTransparent logic")
    else:
        print("[mod] SPA controlTransparent already")


def patch_video_live_control() -> None:
    for rel in [
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
        "app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java",
    ]:
        path = ROOT / rel
        if not path.is_file():
            continue
        t = path.read_text(encoding="utf-8")
        changed = False
        if "applyControlTransparentBg" not in t:
            last = t.rfind("\n}")
            t = t[:last] + HELPER + t[last:]
            changed = True
        # call at start of showControl
        if "showControl" in t and "applyControlTransparentBg();" not in t:
            t2, n = re.subn(
                r"(private void showControl\([^\)]*\) \{\n)",
                r"\1        applyControlTransparentBg();\n",
                t,
                count=1,
            )
            if n:
                t = t2
                changed = True
            else:
                t2, n = re.subn(
                    r"(void showControl\([^\)]*\) \{\n)",
                    r"\1        applyControlTransparentBg();\n",
                    t,
                    count=1,
                )
                if n:
                    t = t2
                    changed = True
        # also onResume
        if "super.onResume();" in t and "applyControlTransparentBg();" not in t.split("super.onResume();")[1][:80]:
            t = t.replace(
                "super.onResume();",
                "super.onResume();\n        applyControlTransparentBg();",
                1,
            )
            changed = True
        if changed:
            path.write_text(t, encoding="utf-8")
            print("[mod] control transparent wire", rel)
        else:
            print("[mod] control transparent skip", rel)


def patch_live_osd_z() -> None:
    live = ROOT / "app/src/leanback/res/layout/activity_live.xml"
    if live.is_file():
        t = live.read_text(encoding="utf-8")
        t = live.read_text(encoding="utf-8")
        if 'android:id="@+id/osd"' in t and "elevation" not in t.split('android:id="@+id/osd"')[1][:220]:
            t = t.replace(
                'android:id="@+id/osd"\n            layout="@layout/view_player_osd"',
                'android:id="@+id/osd"\n            layout="@layout/view_player_osd"\n            android:elevation="24dp"',
                1,
            )
            live.write_text(t, encoding="utf-8")
            print("[mod] live osd elevation")

    osd_xml = ROOT / "app/src/main/res/layout/view_player_osd.xml"
    if osd_xml.is_file():
        t = osd_xml.read_text(encoding="utf-8")
        if "android:elevation" not in t:
            t = t.replace(
                'android:importantForAccessibility="no"',
                'android:importantForAccessibility="no"\n    android:elevation="24dp"',
                1,
            )
            osd_xml.write_text(t, encoding="utf-8")
            print("[mod] view_player_osd elevation")

    osd_java = ROOT / "app/src/main/java/com/fongmi/android/tv/ui/custom/PlayerOsdController.java"
    if not osd_java.is_file():
        return
    t = osd_java.read_text(encoding="utf-8")
    needle = "diagnosticsPanel.setVisibility(TextUtils.isEmpty(text.all()) ? View.GONE : View.VISIBLE);"
    if needle in t and "bringToFront()" not in t[t.find(needle) : t.find(needle) + 250]:
        rep = (
            needle
            + "\n        if (diagnosticsPanel.getVisibility() == View.VISIBLE) {\n"
            + "            diagnosticsPanel.bringToFront();\n"
            + "            if (root != null) {\n"
            + "                root.setVisibility(View.VISIBLE);\n"
            + "                root.bringToFront();\n"
            + "                root.setElevation(24f);\n"
            + "            }\n"
            + "        }"
        )
        osd_java.write_text(t.replace(needle, rep, 1), encoding="utf-8")
        print("[mod] PlayerOsdController bringToFront")


def main() -> None:
    print("[mod] inject_ui_polish ROOT", ROOT)
    patch_setting()
    patch_spa()
    patch_video_live_control()
    patch_live_osd_z()
    print("[mod] inject_ui_polish done")


if __name__ == "__main__":
    main()
