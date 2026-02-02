/*
 * Copyright (c) 2025 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 24 to 25
 * Adds WebDAV support with configs and songs tables
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Drop existing WebDAV tables if they exist (for clean migration)
        database.execSQL("DROP TABLE IF EXISTS webdav_songs")
        database.execSQL("DROP TABLE IF EXISTS webdav_configs")

        // Create webdav_configs table (with music_folders column already)
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS webdav_configs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                name TEXT NOT NULL,
                server_url TEXT NOT NULL,
                username TEXT NOT NULL,
                password TEXT NOT NULL,
                music_folders TEXT NOT NULL DEFAULT '',
                is_enabled INTEGER NOT NULL DEFAULT 1,
                last_synced INTEGER NOT NULL DEFAULT 0
            )
        """)

        // Create webdav_songs table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS webdav_songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                config_id INTEGER NOT NULL,
                remote_path TEXT NOT NULL,
                title TEXT NOT NULL,
                artist_name TEXT NOT NULL,
                album_name TEXT NOT NULL,
                duration INTEGER NOT NULL,
                album_art_path TEXT,
                track_number INTEGER NOT NULL DEFAULT 0,
                year INTEGER NOT NULL DEFAULT 0,
                file_size INTEGER NOT NULL DEFAULT 0,
                content_type TEXT NOT NULL DEFAULT 'audio/mpeg',
                FOREIGN KEY(config_id) REFERENCES webdav_configs(id) ON DELETE CASCADE
            )
        """)

        // Create unique index for config_id and remote_path
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_webdav_songs_config_id_remote_path ON webdav_songs(config_id, remote_path)"
        )
    }
}
