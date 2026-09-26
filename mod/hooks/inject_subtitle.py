#!/usr/bin/env python3
import pathlib
import re

SHOW = r"""
    private void showSubtitleSearch() {
        String keyword = getSubtitleSearchKeyword();
        com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.updateKeyword(keyword);
        AssrtSubtitleSearchDialog.show(this, player(), keyword);
    }

    public String getSubtitleSearchKeyword() {
        String title = "";
        String ep = "";
        // 优先站源上方片名（详情标题），更贴近用户看到的名字
        try {
            if (mBinding != null && mBinding.name != null && mBinding.name.getText() != null) {
                title = mBinding.name.getText().toString().trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (title.isEmpty()) {
                String n = getName();
                if (n != null && !n.isEmpty()) title = n.trim();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (title.isEmpty() && mHistory != null && mHistory.getVodName() != null) title = mHistory.getVodName().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (getEpisode() != null && getEpisode().getName() != null) ep = getEpisode().getName().trim();
        } catch (Throwable ignored) {
        }
        try {
            if (ep.isEmpty() && mHistory != null && mHistory.getVodRemarks() != null) {
                ep = mHistory.getVodRemarks().trim();
            }
        } catch (Throwable ignored) {
        }
        if (title != null && !title.isEmpty()) {
            title = com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.cleanTitleForSearch(title);
        }
        return com.fongmi.android.tv.subtitle.AssrtSubtitleMatch.formatKeyword(title, ep);
    }
"""



def inject_set_player(t: str) -> str:
    """Before startPlayer in setPlayer(Result), attach remembered external sub."""
    if "attachRememberedSub" in t and "setPlayer" in t:
        # already
        pass
    marker = "startPlayer(getHistoryKey(), result,"
    if marker not in t:
        marker = "startPlayer("
    # Only inject once near setPlayer
    if "AssrtSubtitleMatch.attachRememberedSub(result" in t:
        return t
    # Prefer exact mobile/leanback pattern
    for pat in [
        "        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());",
        "        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());\n",
    ]:
        if pat in t:
            t = t.replace(
                pat,
                "        try { AssrtSubtitleMatch.attachRememberedSub(result, mHistory, getEpisode()); } catch (Throwable ignored) {}\n        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 800); } catch (Throwable ignored) {}\n        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 2000); } catch (Throwable ignored) {}\n        try { com.fongmi.android.tv.App.post(() -> { try { AssrtSubtitleMatch.selectPendingIfAny(player()); } catch (Throwable ignored) {} }, 4000); } catch (Throwable ignored) {}\n" + pat,
                1,
            )
            return t
    # generic: first startPlayer after setPlayer method
    m = re.search(r"private void setPlayer\(Result result\) \{", t)
    if not m:
        return t
    region = t[m.start():m.start()+2500]
    idx = region.find("startPlayer(")
    if idx < 0:
        return t
    abs_idx = m.start() + idx
    line_start = t.rfind("\n", 0, abs_idx) + 1
    indent = re.match(r"[ \t]*", t[line_start:]).group(0)
    insert = f"{indent}try {{ AssrtSubtitleMatch.attachRememberedSub(result, mHistory, getEpisode()); }} catch (Throwable ignored) {{}}\n{indent}try {{ com.fongmi.android.tv.App.post(() -> {{ try {{ AssrtSubtitleMatch.selectPendingIfAny(player()); }} catch (Throwable ignored) {{}} }}, 800); }} catch (Throwable ignored) {{}}\n"
    t = t[:line_start] + insert + t[line_start:]
    return t


def insert_after_on_subtitle_click(t: str) -> str:
    if "void showSubtitleSearch()" in t and "getSubtitleSearchKeyword()" in t:
        # refresh method bodies if old version
        t = re.sub(
            r"\n[ \t]*private void showSubtitleSearch\(\) \{[\s\S]*?\n[ \t]*\}\n",
            "\n",
            t,
            count=1,
        )
        t = re.sub(
            r"\n[ \t]*public String getSubtitleSearchKeyword\(\) \{[\s\S]*?\n[ \t]*\}\n",
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



def inject_reapply_on_decode(t: str) -> str:
    """切换解码/内核后重挂外挂字幕。"""
    if "AssrtSubtitleMatch.reapplyAfterPlayerChange" in t:
        return t
    # onDecode end: typically setDecode(); }
    patterns = [
        (
            r"(private void onDecode\(\) \{[\s\S]*?setDecode\(\);)",
            r"\1\n        try { AssrtSubtitleMatch.reapplyAfterPlayerChange(() -> player()); } catch (Throwable ignored) {}",
        ),
        (
            r"(private void switchPlayerKernel\(int type\) \{[\s\S]*?setDecode\(\);)",
            r"\1\n        try { AssrtSubtitleMatch.reapplyAfterPlayerChange(() -> player()); } catch (Throwable ignored) {}",
        ),
    ]
    for pat, repl in patterns:
        t2, n = re.subn(pat, repl, t, count=1)
        if n:
            t = t2
            print("[mod] hooked decode/kernel reapply")
    # also switchPlayerKernelWithResult after startPlayer if present
    if "reapplyAfterPlayerChange" not in t or t.count("reapplyAfterPlayerChange") < 1:
        # fallback: any setDecode(); that is alone on line inside onDecode already tried
        pass
    # After switchPlayer with result - look for setPlayerKernel after switch
    if "switchPlayerKernelWithResult" in t and "reapplyAfterPlayerChange" in t:
        t2, n = re.subn(
            r"(private void switchPlayerKernelWithResult\([\s\S]*?setDecode\(\);)",
            r"\1\n        try { AssrtSubtitleMatch.reapplyAfterPlayerChange(() -> player()); } catch (Throwable ignored) {}",
            t,
            count=1,
        )
        if n:
            t = t2
            print("[mod] hooked switchPlayerKernelWithResult")
    return t


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
        extra = (
            "\n        try { AssrtSubtitleMatch.updateKeyword(mHistory != null ? mHistory.getVodName() : \"\", getEpisode() != null ? getEpisode().getName() : \"\"); } catch (Throwable ignored) {}"
            "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());"
        )
        if "AssrtSubtitleMatch.onPlayerReady" in s:
            return s
        return s + extra

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

    t = re.sub(
        r"TrackDialog\.create\(\)\.type\(([^)]+)\)\.player\(player\(\)\)(?:\.search\([^;]*\))?\.show\(this\);",
        r"TrackDialog.create().type(\1).player(player()).search(this::showSubtitleSearch).show(this);",
        t,
    )

    t = insert_after_on_subtitle_click(t)
    t = inject_reapply_on_decode(t)
    t = inject_set_player(t)

    t = re.sub(
        r"[ \t]*@Override[ \t]*\n[ \t]*\n?[ \t]*private void showSubtitleSearch\(\)",
        "    private void showSubtitleSearch()",
        t,
    )
    t = re.sub(
        r"(?:[ \t]*@Override[ \t]*\n)+[ \t]*public void onSubtitleClick\(\)",
        "    @Override\n    public void onSubtitleClick()",
        t,
    )

    # implements Host
    if "TrackDialog.SubtitleSearchHost" not in t:
        t = re.sub(
            r"implements ([^{\n]+)",
            lambda m: m.group(0)
            if "SubtitleSearchHost" in m.group(0)
            else "implements " + m.group(1).rstrip() + ", TrackDialog.SubtitleSearchHost",
            t,
            count=1,
        )

    p.write_text(t, encoding="utf-8")
    print("[mod] done", rel)
