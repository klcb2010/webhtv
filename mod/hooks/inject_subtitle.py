#!/usr/bin/env python3
import pathlib
import re

SHOW = """
    private void showSubtitleSearch() {
        AssrtSubtitleSearchDialog.show(this, player(), getSubtitleSearchKeyword());
    }

    public String getSubtitleSearchKeyword() {
        String keyword = "";
        try {
            if (mHistory != null && mHistory.getVodName() != null) keyword = mHistory.getVodName();
        } catch (Throwable ignored) {
        }
        try {
            if ((keyword == null || keyword.isEmpty()) && mBinding != null && mBinding.name != null && mBinding.name.getText() != null) {
                keyword = mBinding.name.getText().toString();
            }
        } catch (Throwable ignored) {
        }
        if (keyword == null) keyword = "";
        try {
            if (getEpisode() != null && getEpisode().getName() != null && !getEpisode().getName().isEmpty()) {
                String ep = getEpisode().getName();
                if (!keyword.isEmpty() && !keyword.contains(ep)) keyword = keyword + " " + ep;
                else if (keyword.isEmpty()) keyword = ep;
            }
        } catch (Throwable ignored) {
        }
        return keyword.trim();
    }
"""


def insert_after_on_subtitle_click(t: str) -> str:
    if "void showSubtitleSearch()" in t and "getSubtitleSearchKeyword()" in t:
        return t
    # remove old incomplete showSubtitleSearch only blocks
    t = re.sub(
        r"\n[ \t]*private void showSubtitleSearch\(\) \{[\s\S]*?\n[ \t]*\}\n",
        "\n",
        t,
        count=1,
    )
    m = re.search(
        r"[ \t]*(?:@Override[ \t]*\n[ \t]*)?(?:public|private) void onSubtitleClick\(\)[ \t]*\{",
        t,
    )
    if not m:
        t = t.rstrip()
        if t.endswith("}"):
            return t[:-1] + SHOW + "\n}\n"
        return t + SHOW
    brace = t.find("{", m.end() - 1)
    depth = 0
    end = brace
    for i in range(brace, len(t)):
        if t[i] == "{":
            depth += 1
        elif t[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    return t[:end] + "\n" + SHOW + t[end:]


for rel in [
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
    p = pathlib.Path(rel)
    if not p.exists():
        print("[mod] skip missing", rel)
        continue
    t = p.read_text(encoding="utf-8")

    if "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;" not in t:
        if "import com.fongmi.android.tv.setting.Setting;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.setting.Setting;",
                "import com.fongmi.android.tv.setting.Setting;\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;",
                1,
            )
        else:
            t = t.replace(
                "package com.fongmi.android.tv.ui.activity;",
                "package com.fongmi.android.tv.ui.activity;\n\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;",
                1,
            )

    if "import com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;" not in t:
        if "import com.fongmi.android.tv.ui.dialog.TrackDialog;" in t:
            t = t.replace(
                "import com.fongmi.android.tv.ui.dialog.TrackDialog;",
                "import com.fongmi.android.tv.ui.dialog.TrackDialog;\nimport com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;",
                1,
            )
        else:
            t = t.replace(
                "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;",
                "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;\nimport com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;",
                1,
            )

    def add_ready(match):
        s = match.group(0)
        if "AssrtSubtitleMatch.onPlayerReady" in s:
            return s
        return s + "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());"

    t = re.sub(
        r"startPlayer\(getHistoryKey\(\), result, isUseParse\(\), getSite\(\)\.getTimeout\(\), buildMetadata\(\), mInitialPlaybackPosition\);",
        add_ready,
        t,
    )
    t = re.sub(
        r"startPlayer\(getHistoryKey\(\), result, isUseParse\(\), getSite\(\)\.getTimeout\(\), buildMetadata\(\)\);",
        add_ready,
        t,
    )
    t = t.replace(
        "AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());",
        "AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());",
    )

    if "AssrtSubtitleMatch.cancel()" not in t and "protected void onDestroy()" in t:
        t = t.replace("protected void onDestroy() {", "protected void onDestroy() {\n        AssrtSubtitleMatch.cancel();", 1)

    # Wire .search on every TrackDialog chain (with or without existing search)
    t = re.sub(
        r"TrackDialog\.create\(\)\.type\(([^)]+)\)\.player\(player\(\)\)(?:\.search\([^;]*\))?\.show\(this\);",
        r"TrackDialog.create().type(\1).player(player()).search(this::showSubtitleSearch).show(this);",
        t,
    )

    t = insert_after_on_subtitle_click(t)

    t = re.sub(
        r"[ \t]*@Override[ \t]*\n[ \t]*\n?[ \t]*private void showSubtitleSearch\(\)",
        "    private void showSubtitleSearch()",
        t,
    )
    t = re.sub(
        r"\n[ \t]*@Override[ \t]*\n[ \t]*public void onSubtitleSearchClick\(\) \{[\s\S]*?\n[ \t]*\}\n",
        "\n",
        t,
    )
    t = re.sub(
        r"(?:[ \t]*@Override[ \t]*\n)+[ \t]*public void onSubtitleClick\(\)",
        "    @Override\n    public void onSubtitleClick()",
        t,
    )

    # implement host interface on class declaration if possible
    if "TrackDialog.SubtitleSearchHost" not in t:
        t = t.replace(
            "implements TrackDialog.Listener",
            "implements TrackDialog.Listener, TrackDialog.SubtitleSearchHost",
            1,
        )

    p.write_text(t, encoding="utf-8")
    print("[mod] done", rel)
