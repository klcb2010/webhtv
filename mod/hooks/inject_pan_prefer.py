#!/usr/bin/env python3
"""Search pan-first + direct-play pan prefer (Setting gated, default off)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")


def patch_quick(path: pathlib.Path) -> None:
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if "isPlayDirectPanPrefer" in t:
        print("[mod] QuickAdapter already")
        return
    old = """    public int getBestPosition() {
        int position = 0;
        for (int i = 1; i < mItems.size(); i++) {
            if (SiteHealthStore.compareVods(mItems.get(i), mItems.get(position)) < 0) position = i;
        }
        return position;
    }"""
    new = """    public int getBestPosition() {
        int position = 0;
        boolean panPrefer = false;
        try {
            panPrefer = com.fongmi.android.tv.setting.Setting.isPlayDirectPanPrefer();
        } catch (Throwable ignored) {
        }
        for (int i = 1; i < mItems.size(); i++) {
            if (panPrefer) {
                boolean iPan = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(mItems.get(i));
                boolean pPan = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(mItems.get(position));
                if (iPan && !pPan) {
                    position = i;
                    continue;
                }
                if (!iPan && pPan) continue;
            }
            if (SiteHealthStore.compareVods(mItems.get(i), mItems.get(position)) < 0) position = i;
        }
        return position;
    }"""
    if old in t:
        path.write_text(t.replace(old, new, 1), encoding="utf-8")
        print("[mod] QuickAdapter", path)
        return
    t2, n = re.subn(
        r"public int getBestPosition\(\)\s*\{\s*int position = 0;\s*"
        r"for \(int i = 1; i < mItems\.size\(\); i\+\+\) \{\s*"
        r"if \(SiteHealthStore\.compareVods\(mItems\.get\(i\), mItems\.get\(position\)\) < 0\) position = i;\s*"
        r"\}\s*return position;\s*\}",
        new.strip(),
        t,
        count=1,
        flags=re.S,
    )
    if n:
        path.write_text(t2, encoding="utf-8")
        print("[mod] QuickAdapter soft", path)
    else:
        print("[mod] WARN QuickAdapter", path)


def patch_collect_adapter(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "PanSiteHelper.isPanCollect" in t:
        print("[mod] CollectAdapter already")
        return
    old = """    public void add(Collect item) {
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }"""
    new = """    public void add(Collect item) {
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()
                    && com.fongmi.android.tv.utils.PanSiteHelper.isPanCollect(item)
                    && mItems.size() > 1) {
                mItems.add(1, item);
                notifyItemInserted(1);
                return;
            }
        } catch (Throwable ignored) {
        }
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }"""
    if old in t:
        path.write_text(t.replace(old, new, 1), encoding="utf-8")
        print("[mod] CollectAdapter", path)
        return
    t2, n = re.subn(
        r"public void add\(Collect item\)\s*\{\s*mItems\.add\(item\);\s*notifyItemInserted\(mItems\.size\(\) - 1\);\s*\}",
        new.strip(),
        t,
        count=1,
    )
    if n:
        path.write_text(t2, encoding="utf-8")
        print("[mod] CollectAdapter soft", path)
    else:
        print("[mod] WARN CollectAdapter", path)


def patch_collect_activity(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "SiteHealthStore.sortSites(mSites);" in t and "PanSiteHelper.isPanSite" not in t:
        t = t.replace(
            "SiteHealthStore.sortSites(mSites);",
            """SiteHealthStore.sortSites(mSites);
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()) {
                mSites.sort((a, b) -> {
                    boolean ap = com.fongmi.android.tv.utils.PanSiteHelper.isPanSite(a);
                    boolean bp = com.fongmi.android.tv.utils.PanSiteHelper.isPanSite(b);
                    if (ap == bp) return 0;
                    return ap ? -1 : 1;
                });
            }
        } catch (Throwable ignored) {
        }""",
            1,
        )
        print("[mod] CollectActivity sites", path)
    if "private void addSearchItems(List<Vod> items)" in t and "PanSiteHelper.isPanVod" not in t:
        t2, n = re.subn(
            r"private void addSearchItems\(List<Vod> items\)\s*\{",
            """private void addSearchItems(List<Vod> items) {
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst() && items != null && items.size() > 1) {
                items = new java.util.ArrayList<>(items);
                items.sort((a, b) -> {
                    boolean ap = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(a);
                    boolean bp = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(b);
                    if (ap == bp) return 0;
                    return ap ? -1 : 1;
                });
            }
        } catch (Throwable ignored) {
        }
""",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] CollectActivity addSearchItems", path)
    if t != orig:
        path.write_text(t, encoding="utf-8")


def patch_video(path: pathlib.Path) -> None:
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    if "PanSiteHelper.isPanVod" in t:
        print("[mod] VideoActivity already")
        return
    old = "        mQuickAdapter.addAll(items);"
    new = """        try {
            if (com.fongmi.android.tv.setting.Setting.isPlayDirectPanPrefer()
                    || com.fongmi.android.tv.setting.Setting.isSearchPanFirst()) {
                items = new java.util.ArrayList<>(items);
                items.sort((a, b) -> {
                    boolean ap = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(a);
                    boolean bp = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(b);
                    if (ap == bp) return 0;
                    return ap ? -1 : 1;
                });
            }
        } catch (Throwable ignored) {
        }
        mQuickAdapter.addAll(items);"""
    if old in t:
        path.write_text(t.replace(old, new, 1), encoding="utf-8")
        print("[mod] VideoActivity", path)
    else:
        print("[mod] WARN VideoActivity", path)




def patch_collect_fragment(path: pathlib.Path) -> None:
    """Mobile search uses CollectFragment instead of CollectActivity."""
    if not path.exists():
        return
    t = path.read_text(encoding="utf-8")
    orig = t
    if "SiteHealthStore.sortSites(mSites);" in t and "PanSiteHelper.isPanSite" not in t:
        t = t.replace(
            "SiteHealthStore.sortSites(mSites);",
            """SiteHealthStore.sortSites(mSites);
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()) {
                mSites.sort((a, b) -> {
                    boolean ap = com.fongmi.android.tv.utils.PanSiteHelper.isPanSite(a);
                    boolean bp = com.fongmi.android.tv.utils.PanSiteHelper.isPanSite(b);
                    if (ap == bp) return 0;
                    return ap ? -1 : 1;
                });
            }
        } catch (Throwable ignored) {
        }""",
            1,
        )
        print("[mod] CollectFragment sites", path)
    # setCollect: insert pan collect near front, pan vods in all list front
    if "private void setCollect(Result result)" in t and "PanSiteHelper.isPanVod" not in t:
        old = """    private void setCollect(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        List<Vod> items = new ArrayList<>(result.getList());
        mAllResults.addAll(items);
        mCollects.get(0).getList().addAll(items);
        mCollects.add(Collect.create(items));
        mCollectAdapter.setItems(new ArrayList<>(mCollects));
        if (mCollectAdapter.getPosition() == 0) mSearchAdapter.setItems(new ArrayList<>(mAllResults));
    }"""
        new = """    private void setCollect(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        List<Vod> items = new ArrayList<>(result.getList());
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()) {
                items.sort((a, b) -> {
                    boolean ap = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(a);
                    boolean bp = com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(b);
                    if (ap == bp) return 0;
                    return ap ? -1 : 1;
                });
            }
        } catch (Throwable ignored) {
        }
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()) {
                // pan batches → front of 全部
                java.util.List<Vod> pan = new java.util.ArrayList<>();
                java.util.List<Vod> rest = new java.util.ArrayList<>();
                for (Vod v : items) {
                    if (com.fongmi.android.tv.utils.PanSiteHelper.isPanVod(v)) pan.add(v);
                    else rest.add(v);
                }
                mAllResults.addAll(0, pan);
                mAllResults.addAll(rest);
                mCollects.get(0).getList().clear();
                mCollects.get(0).getList().addAll(mAllResults);
            } else {
                mAllResults.addAll(items);
                mCollects.get(0).getList().addAll(items);
            }
        } catch (Throwable ignored) {
            mAllResults.addAll(items);
            mCollects.get(0).getList().addAll(items);
        }
        Collect created = Collect.create(items);
        try {
            if (com.fongmi.android.tv.setting.Setting.isSearchPanFirst()
                    && com.fongmi.android.tv.utils.PanSiteHelper.isPanCollect(created)
                    && mCollects.size() > 1) {
                mCollects.add(1, created);
            } else {
                mCollects.add(created);
            }
        } catch (Throwable ignored) {
            mCollects.add(created);
        }
        mCollectAdapter.setItems(new ArrayList<>(mCollects));
        if (mCollectAdapter.getPosition() == 0) mSearchAdapter.setItems(new ArrayList<>(mAllResults));
    }"""
        if old in t:
            t = t.replace(old, new, 1)
            print("[mod] CollectFragment setCollect", path)
        else:
            print("[mod] WARN CollectFragment setCollect pattern")
    if t != orig:
        path.write_text(t, encoding="utf-8")


# patch calls at end

for flavor in ("mobile", "leanback"):
    base = ROOT / f"app/src/{flavor}/java/com/fongmi/android/tv/ui"
    patch_quick(base / "adapter/QuickAdapter.java")
    patch_collect_adapter(base / "adapter/CollectAdapter.java")
    patch_collect_activity(base / "activity/CollectActivity.java")
    patch_video(base / "activity/VideoActivity.java")

patch_collect_fragment(ROOT / "app/src/mobile/java/com/fongmi/android/tv/ui/fragment/CollectFragment.java")

print("[mod] inject_pan_prefer done")
