#!/usr/bin/env python3
"""Make Live.keep migration idempotent (duplicate column crash on upgrade)."""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
path = ROOT / "app/src/main/java/com/fongmi/android/tv/db/Migrations.java"
if not path.exists():
    print("[mod] Migrations.java missing, skip")
    sys.exit(0)

text = path.read_text(encoding="utf-8")
if "addColumnIfMissing" in text:
    print("[mod] Migrations already patched")
    sys.exit(0)

helper = """
    /** Idempotent ADD COLUMN - avoids SQLiteException: duplicate column name */
    private static void addColumnIfMissing(androidx.sqlite.db.SupportSQLiteDatabase database, String table, String column, String typeDef) {
        android.database.Cursor cursor = null;
        try {
            cursor = database.query("PRAGMA table_info(`" + table + "`)", null);
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && column.equalsIgnoreCase(cursor.getString(nameIndex))) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        try {
            database.execSQL("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + typeDef);
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.toLowerCase().contains("duplicate column")) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        }
    }
"""

if "public class Migrations {\n" not in text:
    print("[mod] WARN cannot find class Migrations")
    sys.exit(0)

text = text.replace(
    "public class Migrations {\n",
    "public class Migrations {\n" + helper + "\n",
    1,
)

old = 'database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");'
new = 'addColumnIfMissing(database, "Live", "keep", "TEXT DEFAULT NULL");'
if old in text:
    text = text.replace(old, new)
    print("[mod] patched Live.keep ADD COLUMN")
else:
    text2, n = re.subn(
        r'database\.execSQL\(\s*"ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL"\s*\);',
        'addColumnIfMissing(database, "Live", "keep", "TEXT DEFAULT NULL");',
        text,
    )
    text = text2
    print("[mod] regex patch count", n)

path.write_text(text, encoding="utf-8")
print("[mod] Migrations.java updated")
