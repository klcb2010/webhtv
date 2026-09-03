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
                String text = it.title == null ? "" : it.title.trim();
                if (it.year > 0) text = text + " (" + it.year + ")";
                tv.setText(text);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(11);
                tv.setPadding(pad, pad, pad, pad);
                tv.setBackgroundColor(0x33FFFFFF);
                tv.setFocusable(true);
                tv.setMinHeight((int) (36 * density));
                tv.setClickable(true);
                tv.setFocusableInTouchMode(false);
                tv.setBackgroundColor(0x33FFFFFF);
                tv.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        v.setBackgroundColor(0x88FFFFFF);
                        android.view.ViewParent parent = v.getParent();
                        while (parent != null) {
                            if (parent instanceof android.widget.HorizontalScrollView) {
                                android.widget.HorizontalScrollView hsv = (android.widget.HorizontalScrollView) parent;
                                int x = Math.max(0, v.getLeft() - (int) (40 * density));
                                hsv.smoothScrollTo(x, 0);
                                break;
                            }
                            parent = parent.getParent();
                        }
                    } else {
                        v.setBackgroundColor(0x33FFFFFF);
                    }
                });
                tv.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() == android.view.KeyEvent.ACTION_UP
                            && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                            || keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A)) {
                        v.performClick();
                        return true;
                    }
                    return false;
                });
                tv.setMaxWidth(maxW);
                tv.setMaxLines(2);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
            wireFlagToAiFocus();
            com.fongmi.android.tv.App.post(this::wireFlagToAiFocus, 300);
            com.fongmi.android.tv.App.post(this::wireFlagToAiFocus, 800);
        
                
                try {
                    mBinding.aiRecommendPanel.setFocusable(false);
                    mBinding.aiRecommendScroll.setFocusable(true);
                    mBinding.aiRecommendScroll.setFocusableInTouchMode(false);
                    mBinding.aiRecommendScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    mBinding.aiRecommendList.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    mBinding.aiRecommendList.setFocusable(false);
                    // 下键从选集进入推荐：把 episode / flag 的 nextFocusDown 指到推荐滚动条
                    if (mBinding.episode != null) {
                        // episode nextFocus left to upstream

                }
                    try {
                        if (mBinding.flag != null) {
                            // 无选集时从线路也能下到推荐
                            java.lang.reflect.Field f = mBinding.getClass().getDeclaredField("flag");
                            // ViewBinding field access already via mBinding.flag if present
                        }
                    } catch (Throwable ignored) {}
                    // 推荐左上回到选集
                    if (mBinding.aiRecommendList.getChildCount() > 0) {
                        android.view.View first = mBinding.aiRecommendList.getChildAt(0);
                        if (mBinding.episode != null) {
                            first.setNextFocusUpId(mBinding.episode.getId());
                            mBinding.aiRecommendScroll.setNextFocusUpId(mBinding.episode.getId());
                        }
                    }
                } catch (Throwable ignored) {}
} catch (Throwable ignored) {
        }
                try {
                    android.view.View flagView = mBinding.getRoot().findViewById(R.id.flag);
                    if (flagView != null && mBinding.aiRecommendScroll != null) {
            flagView.setNextFocusDownId(mBinding.aiRecommendScroll.getId());

            // 兜底：任意 View 上监听下键/右键跳到 AI
            flagView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        ) return false;
                try {
                    if (mBinding.aiRecommendPanel != null
                            && mBinding.aiRecommendPanel.getVisibility() == android.view.View.VISIBLE
                            && mBinding.aiRecommendList.getChildCount() > 0) {
                        // 右键：仅在无法再向右时交给 AI（简化：总是允许下键；右键仅当无下一个右焦点）
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                                || v.focusSearch(android.view.View.FOCUS_RIGHT) == null
                                || v.focusSearch(android.view.View.FOCUS_RIGHT) == v) {
                            mBinding.aiRecommendList.getChildAt(0).requestFocus();
                            return true;
                        }
                    }
                } catch (Throwable ignored) {}
                return false;
            });

                        mBinding.aiRecommendScroll.setNextFocusLeftId(flagView.getId());
                        mBinding.aiRecommendScroll.setFocusable(true);
                        mBinding.aiRecommendScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        mBinding.aiRecommendList.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                        // TV(leanback) only: 最后一条线路再按右键 -> AI 推荐（反射，避免 mobile 依赖 leanback）
                        try {
                            Class<?> bgvCls = Class.forName("androidx.leanback.widget.BaseGridView");
                            if (bgvCls.isInstance(flagView)) {
                                Object grid = flagView;
                                Class<?> selCls = Class.forName("androidx.leanback.widget.OnChildViewHolderSelectedListener");
                                Object selListener = java.lang.reflect.Proxy.newProxyInstance(
                                        selCls.getClassLoader(),
                                        new Class<?>[]{selCls},
                                        (proxy, method, args) -> {
                                            if ("onChildViewHolderSelected".equals(method.getName()) && args != null && args.length >= 3) {
                                                try {
                                                    Object child = args[1];
                                                    int position = args[2] instanceof Integer ? (Integer) args[2] : -1;
                                                    if (child == null) return null;
                                                    java.lang.reflect.Method getItemView = child.getClass().getMethod("itemView");
                                                    // ViewHolder.itemView is a field
                                                } catch (Throwable ignored) {}
                                                try {
                                                    Object vh = args[1];
                                                    if (vh != null) {
                                                        java.lang.reflect.Field f = null;
                                                        Class<?> c = vh.getClass();
                                                        while (c != null && f == null) {
                                                            try { f = c.getDeclaredField("itemView"); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                                                        }
                                                        if (f != null) {
                                                            f.setAccessible(true);
                                                            android.view.View itemView = (android.view.View) f.get(vh);
                                                            Object adapter = bgvCls.getMethod("getAdapter").invoke(grid);
                                                            int count = 0;
                                                            if (adapter != null) {
                                                                Object n = adapter.getClass().getMethod("getItemCount").invoke(adapter);
                                                                count = n instanceof Integer ? (Integer) n : 0;
                                                            }
                                                            int position = args[2] instanceof Integer ? (Integer) args[2] : -1;
                                                            if (itemView != null && count > 0 && position >= count - 1
                                                                    && mBinding.aiRecommendPanel != null
                                                                    && mBinding.aiRecommendPanel.getVisibility() == android.view.View.VISIBLE) {
                                                            } else if (itemView != null) {
                                                            }
                                                        }
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                            return null;
                                        });
                                bgvCls.getMethod("setOnChildViewHolderSelectedListener", selCls).invoke(grid, selListener);
                                Class<?> keyCls = Class.forName("androidx.leanback.widget.BaseGridView$OnKeyInterceptListener");
                                Object keyListener = java.lang.reflect.Proxy.newProxyInstance(
                                        keyCls.getClassLoader(),
                                        new Class<?>[]{keyCls},
                                        (proxy, method, args) -> {
                                            if (!"onInterceptKeyEvent".equals(method.getName()) || args == null || args.length < 1)
                                                return false;
                                            android.view.KeyEvent event = (android.view.KeyEvent) args[0];
                                            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                                            if (event.getKeyCode() != android.view.KeyEvent.KEYCODE_DPAD_DOWN) return false;
                                            try {
                                                Object selectedObj = bgvCls.getMethod("getSelectedPosition").invoke(grid);
                                                int selected = selectedObj instanceof Integer ? (Integer) selectedObj : -1;
                                                Object adapter = bgvCls.getMethod("getAdapter").invoke(grid);
                                                int count = 0;
                                                if (adapter != null) {
                                                    Object n = adapter.getClass().getMethod("getItemCount").invoke(adapter);
                                                    count = n instanceof Integer ? (Integer) n : 0;
                                                }
                                                if (count > 0 && selected >= count - 1
                                                        && mBinding.aiRecommendPanel != null
                                                        && mBinding.aiRecommendPanel.getVisibility() == android.view.View.VISIBLE
                                                        && mBinding.aiRecommendList.getChildCount() > 0) {
                                                    mBinding.aiRecommendList.getChildAt(0).requestFocus();
                                                    return true;
                                                }
                                            } catch (Throwable ignored) {}
                                            return false;
                                        });
                                bgvCls.getMethod("setOnKeyInterceptListener", keyCls).invoke(grid, keyListener);
                            }
                        } catch (Throwable ignored) {}
                        if (mBinding.aiRecommendList.getChildCount() > 0) {
                            mBinding.aiRecommendList.getChildAt(0).setNextFocusLeftId(flagView.getId());
                        }
                    }
                } catch (Throwable ignored) {}

    }

    private void wireFlagToAiFocus() {
        try {
            if (!com.fongmi.android.tv.utils.Util.isLeanback()) return;
            if (mBinding.aiRecommendPanel == null
                    || mBinding.aiRecommendPanel.getVisibility() != android.view.View.VISIBLE
                    || mBinding.aiRecommendList == null
                    || mBinding.aiRecommendList.getChildCount() == 0
                    || mBinding.aiRecommendScroll == null) return;
            int aiId = mBinding.aiRecommendScroll.getId();
            android.view.View firstAi = mBinding.aiRecommendList.getChildAt(0);

            // 上游顺序：flag → quality → array(集数分组) → episode
            // AI 插在线路下方：flag ↓ → AI；集数 ↑ 先到分组，分组 ↑ 再到 AI
            android.view.View flagView = mBinding.getRoot().findViewById(R.id.flag);
            if (flagView != null) {
                flagView.setNextFocusDownId(aiId);
                firstAi.setNextFocusUpId(flagView.getId());
                mBinding.aiRecommendScroll.setNextFocusUpId(flagView.getId());
                installKeyToAi(flagView, android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            }

            android.view.View arrayView = null;
            try {
                int arrayId = getResources().getIdentifier("array", "id", getPackageName());
                if (arrayId != 0) arrayView = mBinding.getRoot().findViewById(arrayId);
            } catch (Throwable ignored) {}
            boolean arrayVisible = arrayView != null
                    && arrayView.getVisibility() == android.view.View.VISIBLE
                    && arrayView.getWidth() > 0;

            java.util.List<android.view.View> episodeViews = new java.util.ArrayList<>();
            try {
                android.view.View ep = mBinding.getRoot().findViewById(R.id.episode);
                if (ep != null) episodeViews.add(ep);
            } catch (Throwable ignored) {}
            try {
                int gridId = getResources().getIdentifier("episodeGrid", "id", getPackageName());
                if (gridId != 0) {
                    android.view.View ep2 = mBinding.getRoot().findViewById(gridId);
                    if (ep2 != null) episodeViews.add(ep2);
                }
            } catch (Throwable ignored) {}

            if (arrayVisible) {
                // 集数「上」→ 分组；分组「上」→ AI；不拦截集数上键，避免回不去 1-20
                for (android.view.View ep : episodeViews) {
                    if (ep.getVisibility() != android.view.View.VISIBLE) continue;
                    ep.setNextFocusUpId(arrayView.getId());
                    // 明确不装 UP 拦截
                }
                arrayView.setNextFocusUpId(aiId);
                arrayView.setNextFocusDownId(episodeViews.isEmpty() ? aiId : episodeViews.get(0).getId());
                firstAi.setNextFocusDownId(arrayView.getId());
                mBinding.aiRecommendScroll.setNextFocusDownId(arrayView.getId());
                installKeyToAi(arrayView, android.view.KeyEvent.KEYCODE_DPAD_UP);
            } else {
                // 无分组时：集数「上」→ AI
                for (android.view.View ep : episodeViews) {
                    if (ep.getVisibility() != android.view.View.VISIBLE) continue;
                    ep.setNextFocusUpId(aiId);
                    firstAi.setNextFocusDownId(ep.getId());
                    mBinding.aiRecommendScroll.setNextFocusDownId(ep.getId());
                    installKeyToAi(ep, android.view.KeyEvent.KEYCODE_DPAD_UP);
                }
            }

            mBinding.aiRecommendScroll.setFocusable(true);
            mBinding.aiRecommendScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
            mBinding.aiRecommendList.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        } catch (Throwable ignored) {}
    }

    private void installKeyToAi(android.view.View host, int keyCodeWanted) {
        try {
            Class<?> bgvCls = Class.forName("androidx.leanback.widget.BaseGridView");
            if (bgvCls.isInstance(host)) {
                Class<?> keyCls = Class.forName("androidx.leanback.widget.BaseGridView$OnKeyInterceptListener");
                final int want = keyCodeWanted;
                Object keyListener = java.lang.reflect.Proxy.newProxyInstance(
                        keyCls.getClassLoader(),
                        new Class[]{keyCls},
                        (proxy, method, args) -> {
                            if (!"onInterceptKeyEvent".equals(method.getName())) return false;
                            android.view.KeyEvent event = (android.view.KeyEvent) args[0];
                            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                            if (event.getKeyCode() != want) return false;
                            try {
                                if (mBinding.aiRecommendList != null && mBinding.aiRecommendList.getChildCount() > 0) {
                                    mBinding.aiRecommendList.getChildAt(0).requestFocus();
                                    return true;
                                }
                            } catch (Throwable ignored) {}
                            return false;
                        });
                bgvCls.getMethod("setOnKeyInterceptListener", keyCls).invoke(host, keyListener);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (host instanceof androidx.recyclerview.widget.RecyclerView) {
                final int want = keyCodeWanted;
                host.setOnKeyListener((v, keyCode, event) -> {
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
                    if (keyCode != want) return false;
                    try {
                        if (mBinding.aiRecommendList != null && mBinding.aiRecommendList.getChildCount() > 0) {
                            mBinding.aiRecommendList.getChildAt(0).requestFocus();
                            return true;
                        }
                    } catch (Throwable ignored) {}
                    return false;
                });
            }
        } catch (Throwable ignored) {}
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
