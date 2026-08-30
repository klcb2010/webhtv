#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(".")

HOOK = r"""
    private int mAiRecommendGen = 0;
    private com.fongmi.android.tv.bean.Vod mAiRecommendVod;
    private String mAiRecommendTitle = "";

    private void scheduleAiForDetail(com.fongmi.android.tv.bean.Vod item) {
        if (item == null) return;
        final String rawName = item.getName() == null ? "" : item.getName().trim();
        if (rawName.isEmpty()) return;
        final int gen = ++mAiRecommendGen;
        mAiRecommendVod = item;
        mAiRecommendTitle = rawName;
        if (!com.fongmi.android.tv.setting.Setting.isAiRecommendationEnabled()
                && !com.fongmi.android.tv.setting.Setting.isAiTitleExtractionEnabled()) {
            hideAiRecommendPanel();
            return;
        }
        // 稍晚再请求，避免详情 UI 尚未完成导致 panel 绑定失败
        com.fongmi.android.tv.App.post(() -> {
            if (gen != mAiRecommendGen || isFinishing()) return;
            if (com.fongmi.android.tv.setting.Setting.isAiTitleExtractionEnabled()) {
                com.fongmi.android.tv.utils.Task.execute(() -> {
                    String title = com.fongmi.android.tv.service.AiTitleExtractService.extract(rawName);
                    com.fongmi.android.tv.App.post(() -> {
                        if (gen != mAiRecommendGen || isFinishing()) return;
                        if (title != null && !title.isEmpty() && !title.equals(rawName)) {
                            try { mBinding.name.setText(title); } catch (Throwable ignored) {}
                            mAiRecommendTitle = title;
                        }
                        loadAiRecommendations(gen, item, mAiRecommendTitle, 0);
                    });
                });
            } else {
                loadAiRecommendations(gen, item, rawName, 0);
            }
        }, 400);
    }

    private void loadAiRecommendations(int gen, com.fongmi.android.tv.bean.Vod vod, String title, int attempt) {
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
            // binding 偶发未就绪，延迟再试一次
            if (attempt < 2) {
                com.fongmi.android.tv.App.post(() -> {
                    if (gen != mAiRecommendGen) return;
                    loadAiRecommendations(gen, vod, title, attempt + 1);
                }, 500);
            }
            return;
        }
        final String reqTitle = title == null ? "" : title;
        final com.fongmi.android.tv.bean.Vod reqVod = vod;
        com.fongmi.android.tv.utils.Task.execute(() -> {
            Exception last = null;
            java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> items = null;
            for (int i = 0; i < 3; i++) {
                if (gen != mAiRecommendGen) return;
                try {
                    items = com.fongmi.android.tv.service.AiRecommendService.load(reqVod, reqTitle);
                    if (items != null && !items.isEmpty()) break;
                } catch (Exception e) {
                    last = e;
                    try { Thread.sleep(600L * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
            final java.util.List<com.fongmi.android.tv.service.AiRecommendService.Item> result = items;
            final Exception error = last;
            com.fongmi.android.tv.App.post(() -> {
                if (gen != mAiRecommendGen || isFinishing()) return;
                if (result != null && !result.isEmpty()) {
                    bindAiRecommendList(gen, result);
                } else {
                    showAiRecommendRetry(gen, reqVod, reqTitle, error);
                }
            });
        });
    }

    private void showAiRecommendRetry(int gen, com.fongmi.android.tv.bean.Vod vod, String title, Exception error) {
        try {
            if (mBinding.aiRecommendPanel == null) return;
            mBinding.aiRecommendPanel.setVisibility(android.view.View.VISIBLE);
            mBinding.aiRecommendLabel.setText(getString(R.string.ai_recommend_section) + " · " + getString(R.string.ai_recommend_retry));
            mBinding.aiRecommendList.removeAllViews();
            float density = getResources().getDisplayMetrics().density;
            int pad = (int) (10 * density);
            com.google.android.material.textview.MaterialTextView tv = new com.google.android.material.textview.MaterialTextView(this);
            tv.setText(R.string.ai_recommend_retry_action);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(13);
            tv.setPadding(pad * 2, pad, pad * 2, pad);
            tv.setBackgroundColor(0x55FFFFFF);
            tv.setOnClickListener(v -> {
                if (gen != mAiRecommendGen) return;
                loadAiRecommendations(gen, vod, title, 0);
            });
            mBinding.aiRecommendList.addView(tv);
        } catch (Throwable ignored) {
            hideAiRecommendPanel();
        }
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
            int pad = (int) (10 * density);
            int gap = (int) (8 * density);
            int maxW = (int) (160 * density);
            for (com.fongmi.android.tv.service.AiRecommendService.Item it : items) {
                com.google.android.material.textview.MaterialTextView tv = new com.google.android.material.textview.MaterialTextView(this);
                String text = it.title;
                if (it.year > 0) text = text + "\n" + it.year;
                if (it.reason != null && !it.reason.isEmpty()) {
                    String r = it.reason.length() > 28 ? it.reason.substring(0, 28) + "…" : it.reason;
                    text = text + "\n" + r;
                }
                tv.setText(text);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(12);
                tv.setPadding(pad, pad, pad, pad);
                tv.setBackgroundColor(0x33FFFFFF);
                tv.setFocusable(true);
                tv.setFocusableInTouchMode(true);
                tv.setClickable(true);
                tv.setMaxWidth(maxW);
                tv.setMinWidth((int) (120 * density));
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(gap);
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
