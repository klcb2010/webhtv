#!/usr/bin/env python3
import pathlib
import re

SHOW = """
    private void showSubtitleSearch() {
        String keyword = "";
        if (mHistory != null && mHistory.getVodName() != null) keyword = mHistory.getVodName();
        if (getEpisode() != null && getEpisode().getName() != null && !getEpisode().getName().isEmpty()) {
            if (!keyword.isEmpty()) keyword = keyword + " " + getEpisode().getName();
            else keyword = getEpisode().getName();
        }
        AssrtSubtitleSearchDialog.show(this, player(), keyword);
    }
"""

for rel in [
 "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
 "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
  p = pathlib.Path(rel)
  if not p.exists():
    print("[mod] skip missing", rel)
    continue
  t = p.read_text(encoding="utf-8")

  # imports
  if "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;" not in t:
    if "import com.fongmi.android.tv.setting.Setting;" in t:
      t = t.replace("import com.fongmi.android.tv.setting.Setting;", "import com.fongmi.android.tv.setting.Setting;\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;", 1)
    else:
      t = t.replace("package com.fongmi.android.tv.ui.activity;", "package com.fongmi.android.tv.ui.activity;\n\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;", 1)
  if "import com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;" not in t:
    if "import com.fongmi.android.tv.ui.dialog.TrackDialog;" in t:
      t = t.replace("import com.fongmi.android.tv.ui.dialog.TrackDialog;", "import com.fongmi.android.tv.ui.dialog.TrackDialog;\nimport com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;", 1)
    else:
      t = t.replace("import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;", "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;\nimport com.fongmi.android.tv.ui.dialog.AssrtSubtitleSearchDialog;", 1)

  # auto-match on startPlayer
  old1 = "startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata(), mInitialPlaybackPosition);"
  if old1 in t and "AssrtSubtitleMatch.onPlayerReady" not in t.split(old1)[1][:120]:
    t = t.replace(old1, old1 + "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());", 1)
  # all startPlayer without initial pos
  old2 = "startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());"
  if old2 in t:
    t = t.replace(
      old2,
      old2 + "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());",
    )
    # avoid double if already had
    t = t.replace(
      "AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());",
      "AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());",
    )

  if "AssrtSubtitleMatch.cancel()" not in t and "protected void onDestroy()" in t:
    t = t.replace("protected void onDestroy() {", "protected void onDestroy() {\n        AssrtSubtitleMatch.cancel();", 1)

  # Silent style: TrackDialog....search(this::showSubtitleSearch)
  # Replace patterns that open TrackDialog without search
  t2 = t
  t2 = re.sub(
    r'TrackDialog\.create\(\)\.type\(([^)]+)\)\.player\(player\(\)\)\.show\(this\);',
    r'TrackDialog.create().type(\1).player(player()).search(this::showSubtitleSearch).show(this);',
    t2,
  )
  if t2 != t:
    t = t2
    print("[mod] TrackDialog.search wired", rel)
  else:
    print("[mod] TrackDialog.search pattern unchanged", rel)

  if "void showSubtitleSearch()" not in t:
    # insert before onSubtitleClick
    idx = t.find("public void onSubtitleClick()")
    if idx < 0:
      idx = t.find("private void onSubtitleClick()")
    if idx >= 0:
      t = t[:idx] + SHOW + "\n" + t[idx:]
      print("[mod] showSubtitleSearch added", rel)
    else:
      # append before last class closing - weak
      t = t.rstrip()
      if t.endswith("}"):
        t = t[:-1] + SHOW + "\n}\n"
        print("[mod] showSubtitleSearch appended", rel)
  else:
    print("[mod] showSubtitleSearch present", rel)

  # remove mistaken onSubtitleSearchClick if any (with or without @Override)
  t = re.sub(
    r'\n[ \t]*@Override[ \t]*\n[ \t]*public void onSubtitleSearchClick\(\) \{[\s\S]*?\n[ \t]*\}\n',
    '\n',
    t,
  )
  t = re.sub(
    r'\n[ \t]*public void onSubtitleSearchClick\(\) \{[\s\S]*?\n[ \t]*\}\n',
    '\n',
    t,
  )

  p.write_text(t, encoding="utf-8")
  print("[mod] done", rel)
