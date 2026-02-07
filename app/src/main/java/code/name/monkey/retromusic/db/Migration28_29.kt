package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 28 to 29
 * Persists source metadata for playlist/history/play-count songs so WebDAV playback works
 * from favorites, playlists and history-style pages.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE SongEntity ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
        database.execSQL("ALTER TABLE SongEntity ADD COLUMN remote_path TEXT")
        database.execSQL("ALTER TABLE SongEntity ADD COLUMN web_dav_config_id INTEGER")
        database.execSQL("ALTER TABLE SongEntity ADD COLUMN web_dav_album_art_path TEXT")

        database.execSQL("ALTER TABLE HistoryEntity ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
        database.execSQL("ALTER TABLE HistoryEntity ADD COLUMN remote_path TEXT")
        database.execSQL("ALTER TABLE HistoryEntity ADD COLUMN web_dav_config_id INTEGER")
        database.execSQL("ALTER TABLE HistoryEntity ADD COLUMN web_dav_album_art_path TEXT")

        database.execSQL("ALTER TABLE PlayCountEntity ADD COLUMN source_type TEXT NOT NULL DEFAULT 'LOCAL'")
        database.execSQL("ALTER TABLE PlayCountEntity ADD COLUMN remote_path TEXT")
        database.execSQL("ALTER TABLE PlayCountEntity ADD COLUMN web_dav_config_id INTEGER")
        database.execSQL("ALTER TABLE PlayCountEntity ADD COLUMN web_dav_album_art_path TEXT")

        // Backfill existing remote rows from the legacy data column.
        database.execSQL(
            "UPDATE SongEntity SET source_type = 'WEBDAV', remote_path = data WHERE data LIKE 'http://%' OR data LIKE 'https://%'"
        )
        database.execSQL(
            "UPDATE HistoryEntity SET source_type = 'WEBDAV', remote_path = data WHERE data LIKE 'http://%' OR data LIKE 'https://%'"
        )
        database.execSQL(
            "UPDATE PlayCountEntity SET source_type = 'WEBDAV', remote_path = data WHERE data LIKE 'http://%' OR data LIKE 'https://%'"
        )
    }
}
