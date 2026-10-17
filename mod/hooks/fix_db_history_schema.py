#!/usr/bin/env python3
"""
Fix Room crash: Migration didn't properly handle: History

Device DB often has extra column `player` and wallPic DEFAULT NULL,
while current History entity has no player and wallPic without SQL default.

Bump DB 37 -> 38 and rebuild History table to match entity.
Also make Live.keep / History.wallPic ADD COLUMN idempotent.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
MIG = ROOT / "app/src/main/java/com/fongmi/android/tv/db/Migrations.java"
DB = ROOT / "app/src/main/java/com/fongmi/android/tv/db/AppDatabase.java"

if not MIG.exists() or not DB.exists():
    print("[mod] db files missing, skip")
    sys.exit(0)

mig = MIG.read_text(encoding="utf-8")
db = DB.read_text(encoding="utf-8")

if "addColumnIfMissing" not in mig:
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
    mig = mig.replace("public class Migrations {\n", "public class Migrations {\n" + helper + "\n", 1)
    mig = mig.replace(
        'database.execSQL("ALTER TABLE Live ADD COLUMN keep TEXT DEFAULT NULL");',
        'addColumnIfMissing(database, "Live", "keep", "TEXT DEFAULT NULL");',
    )
    mig = mig.replace(
        'database.execSQL("ALTER TABLE History ADD COLUMN wallPic TEXT DEFAULT NULL");',
        'addColumnIfMissing(database, "History", "wallPic", "TEXT");',
    )

if "MIGRATION_37_38" not in mig:
    block = """
    public static final Migration MIGRATION_37_38 = new Migration(37, 38) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Rebuild History to match entity: drop legacy player, wallPic without DEFAULT
            database.execSQL("CREATE TABLE IF NOT EXISTS `History_Rebuild` (`key` TEXT NOT NULL, `vodPic` TEXT, `wallPic` TEXT, `vodName` TEXT, `vodFlag` TEXT, `vodRemarks` TEXT, `episodeUrl` TEXT, `revSort` INTEGER NOT NULL, `revPlay` INTEGER NOT NULL, `createTime` INTEGER NOT NULL, `opening` INTEGER NOT NULL, `ending` INTEGER NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `speed` REAL NOT NULL, `scale` INTEGER NOT NULL, `cid` INTEGER NOT NULL, PRIMARY KEY(`key`))");
            try {
                database.execSQL("INSERT OR REPLACE INTO `History_Rebuild` (`key`,`vodPic`,`wallPic`,`vodName`,`vodFlag`,`vodRemarks`,`episodeUrl`,`revSort`,`revPlay`,`createTime`,`opening`,`ending`,`position`,`duration`,`speed`,`scale`,`cid`) SELECT `key`,`vodPic`,`wallPic`,`vodName`,`vodFlag`,`vodRemarks`,`episodeUrl`,`revSort`,`revPlay`,`createTime`,`opening`,`ending`,`position`,`duration`,`speed`,`scale`,`cid` FROM `History`");
            } catch (Throwable e) {
                try {
                    database.execSQL("INSERT OR REPLACE INTO `History_Rebuild` (`key`,`vodPic`,`wallPic`,`vodName`,`vodFlag`,`vodRemarks`,`episodeUrl`,`revSort`,`revPlay`,`createTime`,`opening`,`ending`,`position`,`duration`,`speed`,`scale`,`cid`) SELECT `key`,`vodPic`,NULL,`vodName`,`vodFlag`,`vodRemarks`,`episodeUrl`,`revSort`,`revPlay`,`createTime`,`opening`,`ending`,`position`,`duration`,`speed`,`scale`,`cid` FROM `History`");
                } catch (Throwable ignored) {
                }
            }
            database.execSQL("DROP TABLE IF EXISTS `History`");
            database.execSQL("ALTER TABLE `History_Rebuild` RENAME TO `History`");
        }
    };
"""
    mig = mig.rstrip()
    if mig.endswith("}"):
        mig = mig[:-1] + block + "\n}\n"
    print("[mod] added MIGRATION_37_38")
else:
    print("[mod] MIGRATION_37_38 already present")

MIG.write_text(mig, encoding="utf-8")

db2 = re.sub(r"public static final int VERSION = 37;", "public static final int VERSION = 38;", db)
if "MIGRATION_37_38" not in db2:
    db2 = db2.replace(
        ".addMigrations(Migrations.MIGRATION_36_37)",
        ".addMigrations(Migrations.MIGRATION_36_37)\n                .addMigrations(Migrations.MIGRATION_37_38)",
    )
DB.write_text(db2, encoding="utf-8")
print("[mod] AppDatabase VERSION =", "38" if "VERSION = 38" in db2 else "check")
print("[mod] done")
