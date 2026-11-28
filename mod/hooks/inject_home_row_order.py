#!/usr/bin/env python3
"""TV home: func row (历史/直播…) above type/点播 row; wire funcRecycler."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
path = ROOT / "app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java"
if not path.exists():
    print("[mod] skip HomeActivity missing")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
if "funcRecycler" in t and "mBinding.funcRecycler.setAdapter" in t:
    print("[mod] HomeActivity funcRecycler already")
else:
    # Ensure ItemBridgeAdapter import if needed - usually already via leanback
    # 1) setRecyclerView: init funcRecycler + don't rely on ListRow for display
    old_rv = """        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setRowHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
"""
    new_rv = """        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        try {
            mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this));
            mBinding.funcRecycler.setHorizontalSpacing(ResUtil.dp2px(16));
            mBinding.funcRecycler.setRowHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            mBinding.funcRecycler.setAdapter(new ItemBridgeAdapter(mFuncAdapter));
        } catch (Throwable ignored) {
        }
        mBinding.typeRecycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setRowHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
"""
    if old_rv in t:
        t = t.replace(old_rv, new_rv, 1)
        print("[mod] setRecyclerView funcRecycler")
    else:
        print("[mod] WARN setRecyclerView pattern")

    # 2) Remove ListRow func from mAdapter init
    for old, new, label in [
        (
            "        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));\n        if (Setting.isHomeHistory()) mAdapter.add(R.string.home_history);",
            "        if (mFuncAdapter == null) mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this));\n        if (Setting.isHomeHistory()) mAdapter.add(R.string.home_history);",
            "init no ListRow func",
        ),
        (
            "        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));\n        mAdapter.add(R.string.home_history);",
            "        if (mFuncAdapter == null) mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this));\n        mAdapter.add(R.string.home_history);",
            "init fish style",
        ),
    ]:
        if old in t:
            t = t.replace(old, new, 1)
            print("[mod]", label)
            break
    else:
        if "new ListRow(mFuncAdapter" in t:
            t = t.replace(
                "mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));",
                "if (mFuncAdapter == null) mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this));",
                1,
            )
            print("[mod] removed ListRow func generic")
        else:
            print("[mod] WARN ListRow func")

    # 3) Focus: title down to func; up from type to func
    t = t.replace('android:nextFocusDown="@id/typeRecycler"', 'android:nextFocusDown="@id/funcRecycler"')  # no-op in java

    # key handlers
    old_key = """        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.typeRecycler.hasFocus()) return requestTitleFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && mBinding.typeRecycler.hasFocus()) return requestContentFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.recycler.hasFocus() && mBinding.typeRecycler.getVisibility() == View.VISIBLE) updateToolbarVisibility(true);"""
    new_key = """        try {
            if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.funcRecycler.hasFocus()) return requestTitleFocus();
            if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && mBinding.funcRecycler.hasFocus()) {
                if (mBinding.typeRecycler.getVisibility() == View.VISIBLE) return mBinding.typeRecycler.requestFocus();
                return requestContentFocus();
            }
        } catch (Throwable ignored) {
        }
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.typeRecycler.hasFocus()) {
            try { return mBinding.funcRecycler.requestFocus(); } catch (Throwable ignored) { return requestTitleFocus(); }
        }
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && mBinding.typeRecycler.hasFocus()) return requestContentFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.recycler.hasFocus() && mBinding.typeRecycler.getVisibility() == View.VISIBLE) updateToolbarVisibility(true);"""
    if old_key in t:
        t = t.replace(old_key, new_key, 1)
        print("[mod] key focus chain")
    else:
        print("[mod] WARN key focus")

    # requestTypeFocus / first focus targets
    if "requestTypeFocus" in t or "typeRecycler.requestFocus" in t:
        # when requesting type focus for home load, prefer func first for toolbar nextFocus
        pass

    # requestContentFocus path from title - title nextFocusDown is funcRecycler in xml
    path.write_text(t, encoding="utf-8")

print("[mod] inject_home_row_order done")
