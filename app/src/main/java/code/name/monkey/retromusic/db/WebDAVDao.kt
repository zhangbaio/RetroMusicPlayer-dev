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

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for WebDAV configurations and songs
 */
@Dao
interface WebDAVDao {

    // Config operations
    @Query("SELECT * FROM webdav_configs WHERE is_enabled = 1 ORDER BY name")
    fun getEnabledConfigsFlow(): Flow<List<WebDAVConfigEntity>>

    @Query("SELECT * FROM webdav_configs WHERE is_enabled = 1 ORDER BY name")
    suspend fun getEnabledConfigs(): List<WebDAVConfigEntity>

    @Query("SELECT * FROM webdav_configs ORDER BY name")
    suspend fun getAllConfigs(): List<WebDAVConfigEntity>

    @Query("SELECT * FROM webdav_configs WHERE id = :configId")
    suspend fun getConfigById(configId: Long): WebDAVConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: WebDAVConfigEntity): Long

    @Update
    suspend fun updateConfig(config: WebDAVConfigEntity)

    @Delete
    suspend fun deleteConfig(config: WebDAVConfigEntity)

    @Query("DELETE FROM webdav_configs WHERE id = :configId")
    suspend fun deleteConfigById(configId: Long)

    // Song operations
    @Query("SELECT * FROM webdav_songs WHERE config_id = :configId ORDER BY artist_name, album_name, track_number")
    suspend fun getSongsByConfig(configId: Long): List<WebDAVSongEntity>

    @Query("SELECT * FROM webdav_songs WHERE config_id = :configId ORDER BY artist_name, album_name, track_number")
    fun getSongsByConfigFlow(configId: Long): Flow<List<WebDAVSongEntity>>

    @Query("SELECT * FROM webdav_songs ORDER BY artist_name, album_name, track_number")
    suspend fun getAllSongs(): List<WebDAVSongEntity>

    @Query("SELECT * FROM webdav_songs ORDER BY artist_name, album_name, track_number")
    fun getAllSongsFlow(): Flow<List<WebDAVSongEntity>>

    @Query("SELECT * FROM webdav_songs WHERE id = :songId")
    suspend fun getSongById(songId: Long): WebDAVSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: WebDAVSongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<WebDAVSongEntity>)

    @Query("DELETE FROM webdav_songs WHERE config_id = :configId")
    suspend fun deleteSongsByConfig(configId: Long)

    @Query("DELETE FROM webdav_songs WHERE config_id = :configId AND remote_path IN (:paths)")
    suspend fun deleteSongsByPaths(configId: Long, paths: List<String>)

    @Query("DELETE FROM webdav_songs WHERE id = :songId")
    suspend fun deleteSongById(songId: Long)

    @Query("DELETE FROM webdav_songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("DELETE FROM webdav_songs WHERE config_id = :configId AND remote_path LIKE :folderPath || '/%'")
    suspend fun deleteSongsByFolder(configId: Long, folderPath: String)

    @Query("SELECT COUNT(*) FROM webdav_songs WHERE config_id = :configId")
    suspend fun getSongCount(configId: Long): Int

    @Query("SELECT * FROM webdav_songs WHERE config_id = :configId AND duration <= 0 ORDER BY file_size ASC, id ASC LIMIT :limit")
    suspend fun getSongsMissingDuration(configId: Long, limit: Int): List<WebDAVSongEntity>

    @Query("SELECT COUNT(*) FROM webdav_songs WHERE config_id = :configId AND duration <= 0")
    suspend fun getSongsMissingDurationCount(configId: Long): Int

    @Query("UPDATE webdav_songs SET duration = :duration WHERE id = :songId")
    suspend fun updateSongDuration(songId: Long, duration: Long)

    @Query("SELECT COUNT(*) FROM webdav_songs")
    suspend fun getTotalSongCount(): Int
}
