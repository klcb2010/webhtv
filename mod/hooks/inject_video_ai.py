#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")

HOOK = r"""
    private int mAiRecommendGen = 0;

    private void scheduleAiForDetail(com.fongmi.android.tv.bean.Vod item) {
        if (item == null) return;
        final String rawName = item.getName() == null ? "" : item.getName().trim();
        if (rawName.isEmpty()) return;
        final int gen = ++mAiRecommendGen;
        if (com.fongmi.android.tv.setting.Setting.isAiTitleExtractionEnabled()) {
            com.fongmi.android.tv.utils.Task.execute(() -> {
                String title = com.fongmi.android.tv.service.AiTitleExtractService.extract(rawName);
                com.fongmi.android.tv.App.post(() -> {
                    if (gen != mAiRecommendGen || isFinishing()) return;
                    if (title != null && !title.isEmpty() && !title.equals(rawName)) {
                        try { mBinding.name.setText(title); } catch (Throwable ignored) {}
                    }
                    loadAiRecommendations(gen, item, title == null || title.isEmpty() ? rawName : title);
                });
            });
        } else {
            loadAiRecommendations(gen, item, rawName);
        }
    }

    private void loadAiRecommendations(int gen, com.fongmi.android.tv.bean.Vod vod, String title) {
        if (!com.fongmi.android.tv.setting.Setting.isAiRecommendationEnabled()) {
            hideAiRecommendPanel();
            return;
        }
        try {
            if (mBinding.aiRecommendPanel == null) return;
            mBinding.aiRecommendPanel.setVisibility(android.view.View.VISIBLE);
            mBinding.aiRecommendLabel.setText(getString(R.string.ai_recommend_section) + " · " + getString(R.string.ai_recommend_loading_short));
            mBinding.aiRecommendList.removeAllViews();
        } catch (Throwable e) {
            return;
        }
        com.fongmi.android.tv.utils.Task.execute(() -> {
            try {
                java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> items =
                        com.fongmi.android.tv.service.AiRecommendService.load(vod, title);
                com.fongmi.android.tv.App.post(() -> bindAiRecommendList(gen, items));
            } catch (Exception e) {
                com.fongmi.android.tv.App.post(() -> {
                    if (gen != mAiRecommendGen) return;
                    hideAiRecommendPanel();
                });
            }
        });
    }

    private void bindAiRecommendList(int gen, java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> items) {
        if (gen != mAiRecommendGen || isFinishing()) return;
        try {
            if (items == null || items.isEmpty()) {
                hideAiRecommendPanel();
                return;
            }
            mBinding.aiRecommendPanel.setVisibility(android.view.View.VISIBLE);
            mBinding.aiRecommendLabel.setText(getString(R.string.ai_recommend_section));
            mBinding.aiRecommendList.removeAllViews();
            float density = getResources().getDisplayMetrics().density;
            int pad = (int) (8 * density);
            for (com.fongmi.android.tv.service.AiRecommendService.Item it : items) {
                com.google.android.material.textview.MaterialTextView tv = new com.google.android.material.textview.MaterialTextView(this);
                tv.setText(it.label());
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(13);
                tv.setPadding(pad, pad, pad, pad);
                tv.setBackgroundColor(0x33FFFFFF);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = pad / 2;
                tv.setLayoutParams(lp);
                final String clickTitle = it.title;
                tv.setOnClickListener(v -> {
                    try {
                        com.fongmi.android.tv.ui.activity.SearchActivity.start(this, clickTitle);
                    } catch (Throwable e) {
                        com.fongmi.android.tv.utils.Notify.show(clickTitle);
                    }
                });
                mBinding.aiRecommendList.addView(tv);
            }
        } catch (Throwable ignored) {
        }
    }

    private void hideAiRecommendPanel() {
        try {
            if (mBinding.aiRecommendPanel != null) mBinding.aiRecommendPanel.setVisibility(android.view.View.GONE);
        } catch (Throwable ignored) {
        }
    }
"""


def inject_file(path: pathlib.Path):
    if not path.exists():
        print("[mod] skip missing", path)
        return
    t = path.read_text(encoding="utf-8")
    if "scheduleAiForDetail" in t:
        t = re.sub(
            r"\n[ \t]*private int mAiRecommendGen[\s\S]*?private void hideAiRecommendPanel\(\) \{[\s\S]*?\n[ \t]*\}\n",
            "\n",
            t,
        )

    def add_call(m):
        body = m.group(0)
        if "scheduleAiForDetail" in body:
            return body
        return body[:-1] + "        scheduleAiForDetail(item);\n    }"

    t2, n = re.subn(
        r"private void setDetail\(Vod item\) \{[\s\S]*?\n    \}",
        add_call,
        t,
        count=1,
    )
    if n == 0:
        print("[mod] WARN setDetail(Vod) not found in", path)
        return
    t = t2
    if t.rstrip().endswith("}"):
        t = t.rstrip()[:-1] + HOOK + "\n}\n"
    path.write_text(t, encoding="utf-8")
    print("[mod] video-ai injected", path)


for rel in [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
    inject_file(ROOT / rel)
