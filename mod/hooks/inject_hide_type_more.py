#!/usr/bin/env python3
"""Hide home typeMore + immediate apply via RefreshEvent.TYPE_MORE."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def patch_refresh_event(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] skip RefreshEvent missing")
        return
    t = path.read_text(encoding="utf-8")
    if "TYPE_MORE" in t:
        print("[mod] RefreshEvent already")
        return
    # add static method
    if "public static void player()" in t and "typeMore()" not in t:
        t = t.replace(
            """    public static void player() {
        EventBus.getDefault().post(new RefreshEvent(Type.PLAYER));
    }
""",
            """    public static void player() {
        EventBus.getDefault().post(new RefreshEvent(Type.PLAYER));
    }

    /** 首页分类「更多」按钮显隐（个性设置立即生效） */
    public static void typeMore() {
        EventBus.getDefault().post(new RefreshEvent(Type.TYPE_MORE));
    }
""",
            1,
        )
    # enum
    old_enum = "HOME, CATEGORY, HISTORY, KEEP, SIZE, THEME, LANGUAGE, LIVE, DETAIL, PLAYER, SUBTITLE, DANMAKU, VOD"
    new_enum = "HOME, CATEGORY, HISTORY, KEEP, SIZE, THEME, LANGUAGE, LIVE, DETAIL, PLAYER, SUBTITLE, DANMAKU, VOD, TYPE_MORE"
    if old_enum in t:
        t = t.replace(old_enum, new_enum, 1)
    elif "TYPE_MORE" not in t:
        t = t.replace("DANMAKU, VOD", "DANMAKU, VOD, TYPE_MORE", 1)
    path.write_text(t, encoding="utf-8")
    print("[mod] RefreshEvent TYPE_MORE")


def patch_vod_fragment(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] skip VodFragment missing")
        return
    t = path.read_text(encoding="utf-8")
    orig = t

    # updateTypeMoreVisible gate
    if "isHideHomeTypeMore" not in t:
        old = """    private void updateTypeMoreVisible() {
        if (mBinding.type.getWidth() == 0 || mBinding.typeBar.getWidth() == 0) {
            mBinding.type.post(this::updateTypeMoreVisible);
            return;
        }
        int typeWidth = mBinding.typeBar.getWidth() - mBinding.typeBar.getPaddingStart() - mBinding.typeBar.getPaddingEnd();
        boolean visible = mAdapter.getItemCount() > 0 && mBinding.type.computeHorizontalScrollRange() > typeWidth;
        mBinding.typeMore.setVisibility(visible ? View.VISIBLE : View.GONE);
    }"""
        new = """    private void updateTypeMoreVisible() {
        try {
            if (com.fongmi.android.tv.setting.Setting.isHideHomeTypeMore()) {
                mBinding.typeMore.setVisibility(View.GONE);
                return;
            }
        } catch (Throwable ignored) {
        }
        if (mBinding.type.getWidth() == 0 || mBinding.typeBar.getWidth() == 0) {
            mBinding.type.post(this::updateTypeMoreVisible);
            return;
        }
        int typeWidth = mBinding.typeBar.getWidth() - mBinding.typeBar.getPaddingStart() - mBinding.typeBar.getPaddingEnd();
        boolean visible = mAdapter.getItemCount() > 0 && mBinding.type.computeHorizontalScrollRange() > typeWidth;
        mBinding.typeMore.setVisibility(visible ? View.VISIBLE : View.GONE);
    }"""
        if old in t:
            t = t.replace(old, new, 1)
            print("[mod] VodFragment updateTypeMoreVisible")
        else:
            t2, n = re.subn(
                r"private void updateTypeMoreVisible\(\)\s*\{",
                """private void updateTypeMoreVisible() {
        try {
            if (com.fongmi.android.tv.setting.Setting.isHideHomeTypeMore()) {
                mBinding.typeMore.setVisibility(View.GONE);
                return;
            }
        } catch (Throwable ignored) {
        }
""",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] VodFragment soft gate")
            else:
                print("[mod] WARN updateTypeMoreVisible")

    # onRefreshEvent case TYPE_MORE
    if "TYPE_MORE" not in t or "case TYPE_MORE" not in t:
        # insert case before closing of switch in onRefreshEvent
        marker = """            case CATEGORY:
                if (mWeb != null && mWeb.isVisible()) return;
                getFragment().onRefresh();
                break;
        }
    }"""
        insert = """            case CATEGORY:
                if (mWeb != null && mWeb.isVisible()) return;
                getFragment().onRefresh();
                break;
            case TYPE_MORE:
                try {
                    updateTypeMoreVisible();
                } catch (Throwable ignored) {
                }
                break;
        }
    }"""
        if marker in t:
            t = t.replace(marker, insert, 1)
            print("[mod] VodFragment onRefresh TYPE_MORE")
        else:
            # softer
            t2, n = re.subn(
                r"(case CATEGORY:\s*if \(mWeb != null && mWeb\.isVisible\(\)\) return;\s*getFragment\(\)\.onRefresh\(\);\s*break;)",
                r"""\1
            case TYPE_MORE:
                try {
                    updateTypeMoreVisible();
                } catch (Throwable ignored) {
                }
                break;""",
                t,
                count=1,
            )
            if n:
                t = t2
                print("[mod] VodFragment onRefresh soft")
            else:
                print("[mod] WARN onRefreshEvent")

    # onResume also refresh (return from settings)
    if "updateTypeMoreVisible" in t and "onResume" in t:
        on_resume = """    public void onResume() {
        super.onResume();
        if (mWeb != null) mWeb.onResume();
"""
        on_resume_new = """    public void onResume() {
        super.onResume();
        if (mWeb != null) mWeb.onResume();
        try {
            updateTypeMoreVisible();
        } catch (Throwable ignored) {
        }
"""
        if on_resume in t and "onResume" in t and t.count("updateTypeMoreVisible()") < 3:
            # only if onResume doesn't already call it
            idx = t.find("public void onResume()")
            chunk = t[idx:idx + 250] if idx >= 0 else ""
            if "updateTypeMoreVisible" not in chunk and on_resume in t:
                t = t.replace(on_resume, on_resume_new, 1)
                print("[mod] VodFragment onResume")

    if t != orig:
        path.write_text(t, encoding="utf-8")
    else:
        print("[mod] VodFragment no change")


def patch_setting_personal(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    old = """    private void setHideHomeTypeMore(View view) {
        try {
            Setting.putHideHomeTypeMore(!Setting.isHideHomeTypeMore());
            mBinding.hideHomeTypeMoreText.setText(getSwitch(Setting.isHideHomeTypeMore()));
        } catch (Throwable ignored) {
        }
    }"""
    new = """    private void setHideHomeTypeMore(View view) {
        try {
            Setting.putHideHomeTypeMore(!Setting.isHideHomeTypeMore());
            mBinding.hideHomeTypeMoreText.setText(getSwitch(Setting.isHideHomeTypeMore()));
            // 立即生效：通知首页刷新 typeMore 显隐
            try {
                com.fongmi.android.tv.event.RefreshEvent.typeMore();
            } catch (Throwable ignored2) {
            }
        } catch (Throwable ignored) {
        }
    }"""
    if "RefreshEvent.typeMore()" in t:
        print("[mod] SettingPersonal already immediate", path.parent.parent.parent.name)
        return
    if old in t:
        path.write_text(t.replace(old, new, 1), encoding="utf-8")
        print("[mod] SettingPersonal immediate", path)
        return
    t2, n = re.subn(
        r"private void setHideHomeTypeMore\(View view\)\s*\{.*?^\s*\}",
        new.strip(),
        t,
        count=1,
        flags=re.S | re.M,
    )
    if n:
        path.write_text(t2, encoding="utf-8")
        print("[mod] SettingPersonal soft", path)
    else:
        print("[mod] WARN SettingPersonal", path)


patch_refresh_event(ROOT / "app/src/main/java/com/fongmi/android/tv/event/RefreshEvent.java")
patch_vod_fragment(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java")
for flavor in ("mobile", "leanback"):
    patch_setting_personal(
        ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/SettingPersonalActivity.java"
    )

print("[mod] inject_hide_type_more done")
