#!/usr/bin/env python3
"""Hide home typeMore button when Setting.isHideHomeTypeMore() is true."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")

path = ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/VodFragment.java"
if not path.exists():
    print("[mod] skip VodFragment missing")
    sys.exit(0)

t = path.read_text(encoding="utf-8")
if "isHideHomeTypeMore" in t:
    print("[mod] VodFragment already")
    sys.exit(0)

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
    path.write_text(t, encoding="utf-8")
    print("[mod] VodFragment updateTypeMoreVisible")
else:
    # soft regex
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
        path.write_text(t2, encoding="utf-8")
        print("[mod] VodFragment soft")
    else:
        print("[mod] WARN VodFragment pattern")

print("[mod] inject_hide_type_more done")
