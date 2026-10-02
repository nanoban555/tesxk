package miku.moe.app;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import java.util.HashSet;
import java.util.Set;

@Database(entities = {MangaFavoriteEntity.class, MangaHistoryEntity.class, MangaProgressEntity.class}, version = 3, exportSchema = false)
public abstract class MangaDatabase extends RoomDatabase {
    private static volatile MangaDatabase instance;

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            rebuildFavorites(database);
            rebuildHistory(database);
            rebuildProgress(database);
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            if (!tableColumns(database, "manga_favorites").contains("info")) database.execSQL("ALTER TABLE `manga_favorites` ADD COLUMN `info` TEXT");
        }
    };

    public abstract MangaDao mangaDao();

    public static MangaDatabase get(Context context) {
        if (instance == null) {
            synchronized (MangaDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(), MangaDatabase.class, "miku_manga_room.db")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .allowMainThreadQueries()
                            .build();
                }
            }
        }
        return instance;
    }

    private static void rebuildFavorites(SupportSQLiteDatabase database) {
        String[] columns = {"key", "slug", "sourceId", "sourceLabel", "title", "cover", "author", "status", "synopsis", "genre", "typeLabel", "info", "latestChapter", "latestChapterDate", "totalChapters", "savedAt", "position"};
        String[] defaults = {"''", "''", "'komikcast'", "''", "''", "''", "''", "''", "''", "''", "''", "''", "''", "''", "0", "0", "0"};
        rebuildTable(database, "manga_favorites", "manga_favorites_room_new", "CREATE TABLE IF NOT EXISTS `manga_favorites_room_new` (`key` TEXT NOT NULL, `slug` TEXT, `sourceId` TEXT, `sourceLabel` TEXT, `title` TEXT, `cover` TEXT, `author` TEXT, `status` TEXT, `synopsis` TEXT, `genre` TEXT, `typeLabel` TEXT, `info` TEXT, `latestChapter` TEXT, `latestChapterDate` TEXT, `totalChapters` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`key`))", columns, defaults);
    }

    private static void rebuildHistory(SupportSQLiteDatabase database) {
        String[] columns = {"key", "dayKey", "slug", "sourceId", "sourceLabel", "title", "cover", "author", "status", "synopsis", "genre", "typeLabel", "latestChapter", "latestChapterDate", "totalChapters", "chapterIndex", "chapterTitle", "chapterSlug", "chapterId", "page", "totalPages", "time", "position"};
        String[] defaults = {"''", "''", "''", "'komikcast'", "''", "''", "''", "''", "''", "''", "''", "''", "''", "''", "0", "-1.0", "''", "''", "''", "0", "1", "0", "0"};
        rebuildTable(database, "manga_history", "manga_history_room_new", "CREATE TABLE IF NOT EXISTS `manga_history_room_new` (`key` TEXT NOT NULL, `dayKey` TEXT, `slug` TEXT, `sourceId` TEXT, `sourceLabel` TEXT, `title` TEXT, `cover` TEXT, `author` TEXT, `status` TEXT, `synopsis` TEXT, `genre` TEXT, `typeLabel` TEXT, `latestChapter` TEXT, `latestChapterDate` TEXT, `totalChapters` INTEGER NOT NULL, `chapterIndex` REAL NOT NULL, `chapterTitle` TEXT, `chapterSlug` TEXT, `chapterId` TEXT, `page` INTEGER NOT NULL, `totalPages` INTEGER NOT NULL, `time` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`key`))", columns, defaults);
    }

    private static void rebuildProgress(SupportSQLiteDatabase database) {
        String[] columns = {"key", "mangaKey", "sourceId", "slug", "chapterIndex", "page", "totalPages", "updatedAt"};
        String[] defaults = {"''", "''", "'komikcast'", "''", "0.0", "0", "1", "0"};
        rebuildTable(database, "manga_progress", "manga_progress_room_new", "CREATE TABLE IF NOT EXISTS `manga_progress_room_new` (`key` TEXT NOT NULL, `mangaKey` TEXT, `sourceId` TEXT, `slug` TEXT, `chapterIndex` REAL NOT NULL, `page` INTEGER NOT NULL, `totalPages` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))", columns, defaults);
    }

    private static void rebuildTable(SupportSQLiteDatabase database, String tableName, String tempTableName, String createSql, String[] columns, String[] defaults) {
        database.execSQL("DROP TABLE IF EXISTS `" + tempTableName + "`");
        database.execSQL(createSql);
        if (tableExists(database, tableName)) {
            Set<String> existing = tableColumns(database, tableName);
            database.execSQL("INSERT OR REPLACE INTO `" + tempTableName + "` (" + quotedColumns(columns) + ") SELECT " + selectColumns(columns, defaults, existing) + " FROM `" + tableName + "`");
            database.execSQL("DROP TABLE `" + tableName + "`");
        }
        database.execSQL("ALTER TABLE `" + tempTableName + "` RENAME TO `" + tableName + "`");
    }

    private static boolean tableExists(SupportSQLiteDatabase database, String tableName) {
        Cursor cursor = database.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new Object[]{tableName});
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private static Set<String> tableColumns(SupportSQLiteDatabase database, String tableName) {
        Set<String> columns = new HashSet<>();
        Cursor cursor = database.query("PRAGMA table_info(`" + tableName + "`)");
        try {
            while (cursor.moveToNext()) columns.add(cursor.getString(1));
        } finally {
            cursor.close();
        }
        return columns;
    }

    private static String quotedColumns(String[] columns) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) out.append(", ");
            out.append('`').append(columns[i]).append('`');
        }
        return out.toString();
    }

    private static String selectColumns(String[] columns, String[] defaults, Set<String> existing) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) out.append(", ");
            if (existing.contains(columns[i])) out.append("COALESCE(`").append(columns[i]).append("`, ").append(defaults[i]).append(')');
            else out.append(defaults[i]);
        }
        return out.toString();
    }
}
