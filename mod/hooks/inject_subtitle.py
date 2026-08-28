#!/usr/bin/env python3
import pathlib
for rel in [
 "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
 "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
  p = pathlib.Path(rel)
  if not p.exists():
    print("[mod] skip missing", rel)
    continue
  t = p.read_text(encoding="utf-8")
  if "AssrtSubtitleMatch" in t:
    print("[mod] already hooked", rel)
    continue
  if "import com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;" not in t:
    if "import com.fongmi.android.tv.setting.Setting;" in t:
      t = t.replace(
        "import com.fongmi.android.tv.setting.Setting;",
        "import com.fongmi.android.tv.setting.Setting;\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;",
        1,
      )
    elif "import com.fongmi.android.tv.player.PlayerManager;" in t:
      t = t.replace(
        "import com.fongmi.android.tv.player.PlayerManager;",
        "import com.fongmi.android.tv.player.PlayerManager;\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;",
        1,
      )
    else:
      t = t.replace("package com.fongmi.android.tv.ui.activity;", "package com.fongmi.android.tv.ui.activity;\n\nimport com.fongmi.android.tv.subtitle.AssrtSubtitleMatch;", 1)
  old1 = "startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata(), mInitialPlaybackPosition);"
  new1 = old1 + "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());"
  if old1 in t:
    t = t.replace(old1, new1, 1)
  old2 = "startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata());"
  new2 = old2 + "\n        AssrtSubtitleMatch.onPlayerReady(this, mHistory, getEpisode(), () -> player());"
  if old2 in t:
    t = t.replace(old2, new2)
  if "AssrtSubtitleMatch.cancel()" not in t and "protected void onDestroy()" in t:
    t = t.replace("protected void onDestroy() {", "protected void onDestroy() {\n        AssrtSubtitleMatch.cancel();", 1)
  p.write_text(t, encoding="utf-8")
  print("[mod] hooked", rel)
