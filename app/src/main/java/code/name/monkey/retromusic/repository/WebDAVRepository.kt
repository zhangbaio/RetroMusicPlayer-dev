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
package code.name.monkey.retromusic.repository

import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.WebDAVConfig

/**
 * Repository interface for WebDAV operations
 */
interface WebDAVRepository {
    /**
     * Get all WebDAV configurations
     */
    suspend fun getAllConfigs(): List<WebDAVConfig>

    /**
     * Get enabled WebDAV configurations
     */
    suspend fun getEnabledConfigs(): List<WebDAVConfig>

    /**
     * Get a specific configuration by ID
     */
    suspend fun getConfigById(configId: Long): WebDAVConfig?

    /**
     * Save a WebDAV configuration (insert or update)
     * @return The ID of the saved configuration
     */
    suspend fun saveConfig(config: WebDAVConfig): Long

    /**
     * Delete a WebDAV configuration
     */
    suspend fun deleteConfig(config: WebDAVConfig)

    /**
     * Test connection to a WebDAV server
     * @return true if connection successful
     */
    suspend fun testConnection(config: WebDAVConfig): Result<Boolean>

    /**
     * Sync songs from a WebDAV server
     * Scans for audio files and updates the local cache
     * @param configId The ID of the configuration to sync
     * @return Result containing the number of songs synced
     */
    suspend fun syncSongs(configId: Long): Result<Int>

    /**
     * Get all cached WebDAV songs
     */
    suspend fun getAllSongs(): List<Song>

    /**
     * Get songs from a specific WebDAV configuration
     */
    suspend fun getSongsByConfig(configId: Long): List<Song>

    /**
     * Get a specific song by ID
     */
    suspend fun getSongById(songId: Long): Song?

    /**
     * Delete all songs for a specific configuration
     */
    suspend fun deleteSongsByConfig(configId: Long)

    /**
     * Delete cached WebDAV songs by local database IDs
     */
    suspend fun deleteSongsByIds(songIds: List<Long>)
}
