#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Port Silent1566/webhtv SUB-EXT-HISTORY subtitle memory onto webhtv/webhtv.

The base repo is intentionally kept untouched in git; this hook patches the
upstream sources at build time. The implementation follows
docs/SUB-EXT-HISTORY-external-subtitle-restore.md:
History JSON -> Coordinator -> PlayerManager pending subtitle ->
prepareMpvOutputForNewItem() before setMediaItem().
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

def read(rel):
    p = ROOT / rel
    return p, p.read_text(encoding="utf-8")

def write(p, s):
    p.write_text(s, encoding="utf-8")

def add_import(text, anchor, imp):
    if imp in text:
        return text
    if anchor in text:
        return text.replace(anchor, anchor + "\n" + imp, 1)
    return text

def fail(msg):
    raise SystemExit("[mod] subtitle_restore ERROR: " + msg)

# ---------------------------------------------------------------------------
# History: persistent subtitleSource JSON.
# ---------------------------------------------------------------------------
p, t = read("app/src/main/java/com/fongmi/android/tv/bean/History.java")
if "private String subtitleSource;" not in t:
    anchor = '    private int cid;'
    if anchor not in t:
        fail("History cid field anchor not found")
    t = t.replace(
        anchor,
        anchor + '''
    /**
     * 外挂字幕来源 JSON；空串表示没有外挂字幕偏好。
     */
    @SerializedName("subtitleSource")
    @androidx.room.ColumnInfo(defaultValue = "")
    private String subtitleSource;''',
        1,
    )

if "item.subtitleSource = subtitleSource;" not in t:
    anchor = "        item.cid = cid;"
    if anchor not in t:
        fail("History.copy cid anchor not found")
    t = t.replace(anchor, anchor + "\n        item.subtitleSource = subtitleSource;", 1)

if "getSubtitleSourceObject()" not in t:
    anchor = "    public int getCid() {"
    if anchor not in t:
        fail("History getCid anchor not found")
    methods = '''
    public String getSubtitleSource() {
        return subtitleSource == null ? "" : subtitleSource;
    }

    public void setSubtitleSource(String subtitleSource) {
        this.subtitleSource = subtitleSource == null ? "" : subtitleSource;
    }

    public SubtitleSource getSubtitleSourceObject() {
        return SubtitleSource.decode(subtitleSource);
    }

    public void setSubtitleSourceObject(SubtitleSource source) {
        this.subtitleSource = SubtitleSource.encode(source);
    }

'''
    t = t.replace(anchor, methods + anchor, 1)

t = add_import(
    t,
    "import com.fongmi.android.tv.playback.PlaybackProgressWriter;",
    "import com.fongmi.android.tv.playback.SubtitleSource;",
)
write(p, t)
print("[mod] subtitle_restore: History patched")

# ---------------------------------------------------------------------------
# Startup: this upstream branch does not contain the generated EventIndex
# class. Use EventBus default installation instead.
# ---------------------------------------------------------------------------
p, t = read("app/src/main/java/com/fongmi/android/tv/Startup.java")
t = t.replace("import com.fongmi.android.tv.event.EventIndex;\n", "")
t = t.replace("EventBus.builder().addIndex(new EventIndex()).installDefaultEventBus();", "EventBus.builder().installDefaultEventBus();")
write(p, t)
print("[mod] subtitle_restore: Startup patched")

# ---------------------------------------------------------------------------
# Database: current fork already has a build-time 37->38 repair. Add the
# subtitle column as 38->39, so old installs migrate 37->38->39.
# ---------------------------------------------------------------------------
p, t = read("app/src/main/java/com/fongmi/android/tv/db/Migrations.java")
if "MIGRATION_38_39" not in t:
    if "addColumnIfMissing" not in t:
        helper = '''
    /** Idempotent ADD COLUMN helper. */
    private static void addColumnIfMissing(androidx.sqlite.db.SupportSQLiteDatabase database, String table, String column, String typeDef) {
        android.database.Cursor cursor = null;
        try {
            cursor = database.query("PRAGMA table_info(`" + table + "`)");
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(cursor.getString(nameIndex))) return;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        database.execSQL("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + typeDef);
    }
'''
        t = t.replace("public class Migrations {\n", "public class Migrations {\n" + helper + "\n", 1)

    block = '''
    public static final Migration MIGRATION_38_39 = new Migration(38, 39) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            addColumnIfMissing(database, "History", "subtitleSource", "TEXT DEFAULT ''");
        }
    };
'''
    pos = t.rfind("\n}")
    if pos < 0:
        fail("Migrations class closing brace not found")
    t = t[:pos] + block + t[pos:]

write(p, t)

p, t = read("app/src/main/java/com/fongmi/android/tv/db/AppDatabase.java")
t2, n = re.subn(r"public static final int VERSION = 3[78];", "public static final int VERSION = 39;", t, count=1)
if n == 0:
    if "public static final int VERSION = 39;" not in t:
        fail("AppDatabase VERSION 37/38 anchor not found")
    t2 = t
t = t2
if "Migrations.MIGRATION_38_39" not in t:
    anchor = "                .addMigrations(Migrations.MIGRATION_36_37)"
    if anchor not in t:
        fail("AppDatabase migration registration anchor not found")
    t = t.replace(
        anchor,
        anchor + '''
                .addMigrations(Migrations.MIGRATION_37_38)
                .addMigrations(Migrations.MIGRATION_38_39)''',
        1,
    )
write(p, t)
print("[mod] subtitle_restore: database patched to VERSION 39")

# ---------------------------------------------------------------------------
# PlayerManager: pending restore consumed before the MPV early return and
# before externalSubtitleActive is calculated.
# ---------------------------------------------------------------------------
p, t = read("app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java")

if "private Sub pendingRestoreSub;" not in t:
    anchor = "    private boolean initTrack;"
    if anchor not in t:
        fail("PlayerManager initTrack anchor not found")
    t = t.replace(
        anchor,
        '''    /**
     * History 中恢复出的外挂字幕，下一次起播前消费一次。
     */
    private Sub pendingRestoreSub;
    
''' + anchor,
        1,
    )

if "public void setPendingRestoreSub(Sub sub)" not in t:
    anchor = "    public void setFormat(String format) {"
    if anchor not in t:
        fail("PlayerManager setFormat anchor not found")
    t = t.replace(
        anchor,
        '''    public void setPendingRestoreSub(Sub sub) {
        pendingRestoreSub = sub;
    }

''' + anchor,
        1,
    )

if "callback.onSubtitleSelected(sub);" not in t:
    anchor = "        spec.setSub(sub);\n        setMediaItem();"
    if anchor in t:
        t = t.replace(
            anchor,
            "        spec.setSub(sub);\n        callback.onSubtitleSelected(sub);\n        setMediaItem();",
            1,
        )
    else:
        # Accept the slightly newer setSub shape while preserving everything else.
        m = re.search(r"(    public void setSub\(Sub sub\) \{.*?\n    \})", t, re.S)
        if not m:
            fail("PlayerManager setSub method not found")
        body = m.group(1)
        if "spec.setSub(sub);" not in body:
            fail("PlayerManager setSub spec.setSub anchor not found")
        body2 = body.replace("        spec.setSub(sub);", "        spec.setSub(sub);\n        callback.onSubtitleSelected(sub);", 1)
        t = t[:m.start()] + body2 + t[m.end():]

if "restorePendingSubtitle();" not in t:
    anchor = "    private void prepareMpvOutputForNewItem() {\n        resetMpvOutputEvaluationState();\n        mpvSurfaceFallbackTried = false;"
    if anchor not in t:
        fail("PlayerManager prepareMpvOutputForNewItem exact anchor not found")
    t = t.replace(
        anchor,
        '''    private void prepareMpvOutputForNewItem() {
        resetMpvOutputEvaluationState();
        // Must run before the MPV instanceof early-return: EXO/IJK also consume spec.subs.
        // It must also run before external-subtitle/output decisions are calculated.
        restorePendingSubtitle();
        mpvSurfaceFallbackTried = false;''',
        1,
    )

if "private void restorePendingSubtitle()" not in t:
    anchor = "    private void resetMpvOutputRuntime() {"
    if anchor not in t:
        fail("PlayerManager resetMpvOutputRuntime anchor not found")
    method = '''
    private void restorePendingSubtitle() {
        Sub sub = pendingRestoreSub;
        pendingRestoreSub = null;
        if (spec == null || sub == null) return;
        spec.setSub(sub);
    }

'''
    t = t.replace(anchor, method + anchor, 1)

if "void onSubtitleSelected(Sub sub)" not in t:
    anchor = "        default void onPlayerOutputReady() {"
    if anchor not in t:
        fail("PlayerManager.Callback output-ready anchor not found")
    t = t.replace(
        anchor,
        '''        default void onSubtitleSelected(Sub sub) {
        }

''' + anchor,
        1,
    )

write(p, t)
print("[mod] subtitle_restore: PlayerManager patched")

# ---------------------------------------------------------------------------
# PlaybackService forwards the PlayerManager callback to all registered UI
# callbacks. PlaybackActivity then exposes a protected hook to VideoActivity.
# ---------------------------------------------------------------------------
p, t = read("app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java")
if "void onSubtitleSelected(Sub sub)" not in t:
    anchor = "        default void onPlayerOutputReady() {"
    if anchor not in t:
        fail("PlaybackService PlayerCallback anchor not found")
    t = t.replace(
        anchor,
        '''        default void onSubtitleSelected(Sub sub) {
        }

''' + anchor,
        1,
    )
if "playerCallbacks.forEach(callback -> callback.onSubtitleSelected(sub));" not in t:
    anchor = "    @Override\n    public void onPlayerOutputReady() {"
    if anchor not in t:
        fail("PlaybackService onPlayerOutputReady anchor not found")
    t = t.replace(
        anchor,
        '''    @Override
    public void onSubtitleSelected(Sub sub) {
        playerCallbacks.forEach(callback -> callback.onSubtitleSelected(sub));
    }

''' + anchor,
        1,
    )
t = add_import(t, "import com.fongmi.android.tv.bean.Result;", "import com.fongmi.android.tv.bean.Sub;")
write(p, t)
print("[mod] subtitle_restore: PlaybackService patched")

p, t = read("app/src/main/java/com/fongmi/android/tv/ui/activity/PlaybackActivity.java")
if "protected void onSubtitleSelected(Sub sub)" not in t:
    anchor = "    protected void onTracksChanged() {"
    if anchor not in t:
        fail("PlaybackActivity onTracksChanged anchor not found")
    t = t.replace(
        anchor,
        '''    protected void onSubtitleSelected(Sub sub) {
    }

''' + anchor,
        1,
    )
if "public void onSubtitleSelected(Sub sub)" not in t:
    # Forward from the service callback to the Activity hook.
    anchor = "        @Override\n        public void onTracksChanged() {\n            if (isOwner()) PlaybackActivity.this.onTracksChanged();\n        }"
    if anchor not in t:
        fail("PlaybackActivity mPlayerCallback tracks anchor not found")
    t = t.replace(
        anchor,
        anchor + '''

        @Override
        public void onSubtitleSelected(Sub sub) {
            if (isOwner()) PlaybackActivity.this.onSubtitleSelected(sub);
        }''',
        1,
    )
t = add_import(t, "import com.fongmi.android.tv.bean.Result;", "import com.fongmi.android.tv.bean.Sub;")
write(p, t)
print("[mod] subtitle_restore: PlaybackActivity patched")

# ---------------------------------------------------------------------------
# Shared coordinator/policy are copied into mod/app by apply.sh. The two
# VideoActivity variants call restore immediately before every startPlayer()
# for a Result, and persist explicit subtitle selections through the callback.
# ---------------------------------------------------------------------------
for rel in [
    "app/src/mobile/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
    "app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java",
]:
    p, t = read(rel)

    # The old custom Assrt restore path can overwrite the remembered subtitle.
    t = re.sub(
        r"^\s*try \{ AssrtSubtitleMatch\.attachRememberedSub\([^\n]*\}\s*$\n?",
        "",
        t,
        flags=re.M,
    )

    t = add_import(
        t,
        "import com.fongmi.android.tv.bean.Sub;",
        "import com.fongmi.android.tv.playback.SubtitleRestoreCoordinator;",
    )

    # Inject restore before each direct Result startPlayer call. The Result
    # itself is passed so Result.setSubs() can prevent auto-match from replacing
    # the remembered subtitle.
    lines = t.splitlines(True)
    out = []
    for line in lines:
        if "startPlayer(getHistoryKey(), result" in line and "SubtitleRestoreCoordinator.restore(mHistory, player(), result)" not in "".join(out[-3:]):
            indent = re.match(r"\s*", line).group(0)
            out.append(indent + "if (SubtitleRestoreCoordinator.restore(mHistory, player(), result)) syncHistory();\n")
        out.append(line)
    t = "".join(out)

    # Normalize duplicate annotations produced by overlapping mod hooks.
    # Some hooks run after this one, so also collapse repeated @Override lines
    # with blank/whitespace lines between them.
    t = re.sub(r"(?m)(^[ \t]*@Override[ \t]*\n)(?:[ \t]*\n)*[ \t]*@Override[ \t]*\n", r"\1", t)
    t = re.sub(r"(?m)(^[ \t]*@Override[ \t]*\n)(?:[ \t]*\n)*[ \t]*@Override[ \t]*\n", r"\1", t)

    # Avoid duplicate restore if this hook is rerun.
    t = t.replace(
        "if (SubtitleRestoreCoordinator.restore(mHistory, player(), result)) syncHistory();\n"
        "        if (SubtitleRestoreCoordinator.restore(mHistory, player(), result)) syncHistory();\n",
        "        if (SubtitleRestoreCoordinator.restore(mHistory, player(), result)) syncHistory();\n",
    )

    if "protected void onSubtitleSelected(Sub sub)" not in t:
        # Insert near onTracksChanged; this is an Activity-level hook from PlaybackActivity.
        anchor = "    protected void onTracksChanged() {"
        if anchor not in t:
            fail(f"{rel}: onTracksChanged anchor not found")
        method = '''    @Override
    protected void onSubtitleSelected(Sub sub) {
        if (SubtitleRestoreCoordinator.remember(mHistory, sub)) syncHistory();
    }

'''
        t = t.replace(anchor, method + anchor, 1)

    write(p, t)
    print("[mod] subtitle_restore: patched", rel)

print("[mod] subtitle_restore: complete")
