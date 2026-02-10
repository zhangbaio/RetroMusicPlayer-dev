package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. Create new server_configs table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `server_configs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `server_url` TEXT NOT NULL,
                `api_token` TEXT NOT NULL,
                `is_enabled` INTEGER NOT NULL DEFAULT 1,
                `last_synced` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // 2. Create new server_songs table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `server_songs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `config_id` INTEGER NOT NULL,
                `server_track_id` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `artist_name` TEXT NOT NULL,
                `album_name` TEXT NOT NULL,
                `album_artist` TEXT,
                `duration` INTEGER NOT NULL,
                `track_number` INTEGER NOT NULL DEFAULT 0,
                `disc_number` INTEGER NOT NULL DEFAULT 0,
                `year` INTEGER NOT NULL DEFAULT 0,
                `genre` TEXT,
                `has_cover` INTEGER NOT NULL DEFAULT 0,
                `has_lyric` INTEGER NOT NULL DEFAULT 0,
                `source_path` TEXT NOT NULL DEFAULT '',
                `mime_type` TEXT NOT NULL DEFAULT 'audio/mpeg',
                `source_size` INTEGER NOT NULL DEFAULT 0,
                `updated_at` TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        // 3. Create unique index on server_songs
        database.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS `index_server_songs_config_id_server_track_id`
            ON `server_songs` (`config_id`, `server_track_id`)
        """.trimIndent())

        // 4. Migrate source_type from 'WEBDAV' to 'SERVER' in existing entities
        database.execSQL("UPDATE SongEntity SET source_type = 'SERVER' WHERE source_type = 'WEBDAV'")
        database.execSQL("UPDATE HistoryEntity SET source_type = 'SERVER' WHERE source_type = 'WEBDAV'")
        database.execSQL("UPDATE PlayCountEntity SET source_type = 'SERVER' WHERE source_type = 'WEBDAV'")

        // 5. Drop old WebDAV tables
        database.execSQL("DROP TABLE IF EXISTS `webdav_songs`")
        database.execSQL("DROP TABLE IF EXISTS `webdav_configs`")
    }
}
