package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 27 to 28
 * Adds file-level fingerprint fields for incremental WebDAV sync.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE webdav_songs ADD COLUMN remote_last_modified INTEGER NOT NULL DEFAULT 0"
        )
        database.execSQL(
            "ALTER TABLE webdav_songs ADD COLUMN file_fingerprint TEXT NOT NULL DEFAULT ''"
        )
    }
}
