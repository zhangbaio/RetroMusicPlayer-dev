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

import android.util.Log
import code.name.monkey.retromusic.db.WebDAVConfigEntity
import code.name.monkey.retromusic.db.WebDAVSongEntity
import code.name.monkey.retromusic.db.WebDAVDao
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.WebDAVConfig
import code.name.monkey.retromusic.webdav.SardineWebDAVClient
import code.name.monkey.retromusic.webdav.WebDAVClient
import code.name.monkey.retromusic.webdav.WebDAVCryptoUtil
import code.name.monkey.retromusic.webdav.WebDAVFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of WebDAVRepository
 */
class RealWebDAVRepository(
    private val webDAVDao: WebDAVDao,
    private val webDAVClient: WebDAVClient = SardineWebDAVClient()
) : WebDAVRepository {

    companion object {
        private const val TAG = "RealWebDAVRepository"

        // Audio file extensions to scan for
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "ogg", "wav", "aac", "opus", "wma", "m4b", "mp4a"
        )
    }

    override suspend fun getAllConfigs(): List<WebDAVConfig> = withContext(Dispatchers.IO) {
        webDAVDao.getAllConfigs().map { it.toModel() }
    }

    override suspend fun getEnabledConfigs(): List<WebDAVConfig> = withContext(Dispatchers.IO) {
        webDAVDao.getEnabledConfigs().map { it.toModel() }
    }

    override suspend fun getConfigById(configId: Long): WebDAVConfig? = withContext(Dispatchers.IO) {
        webDAVDao.getConfigById(configId)?.toModel()
    }

    override suspend fun saveConfig(config: WebDAVConfig): Long = withContext(Dispatchers.IO) {
        // Encrypt password before saving
        val encryptedPassword = if (config.id > 0) {
            WebDAVCryptoUtil.encryptPassword(config.password, config.id)
        } else {
            // For new configs, we'll use a temporary ID and re-encrypt after insert
            config.password
        }

        val entity = WebDAVConfigEntity(
            id = config.id,
            name = config.name,
            serverUrl = config.serverUrl,
            username = config.username,
            password = encryptedPassword,
            musicFolders = WebDAVConfigEntity.foldersToString(config.musicFolders),
            isEnabled = config.isEnabled,
            lastSynced = config.lastSynced
        )

        val id = webDAVDao.insertConfig(entity)

        // Re-encrypt with the actual ID if this was a new config
        if (config.id == 0L) {
            WebDAVCryptoUtil.encryptPassword(config.password, id)
        }

        id
    }

    override suspend fun deleteConfig(config: WebDAVConfig) = withContext(Dispatchers.IO) {
        webDAVDao.deleteConfigById(config.id)
        WebDAVCryptoUtil.removePassword(config.id)
    }

    override suspend fun testConnection(config: WebDAVConfig): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val success = webDAVClient.testConnection(config)
                if (success) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Connection failed"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                Result.failure(e)
            }
        }

    override suspend fun syncSongs(configId: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val config = getConfigById(configId)
                ?: return@withContext Result.failure(Exception("Configuration not found"))

            Log.d(TAG, "Starting sync for config: ${config.name}")
            Log.d(TAG, "Config musicFolders: ${config.musicFolders}")
            Log.d(TAG, "Config serverUrl: ${config.serverUrl}")

            // Decrypt password for the sync operation
            val decryptedPassword = WebDAVCryptoUtil.decryptPassword(config.password, config.id)
            Log.d(TAG, "Password decrypted, length: ${decryptedPassword.length}")

            val decryptedConfig = config.copy(password = decryptedPassword)

            // Scan for audio files
            val webDAVClient = webDAVClient as? SardineWebDAVClient
                ?: return@withContext Result.failure(Exception("Invalid WebDAV client type"))

            // Scan only the configured music folders, not the root
            val foldersToScan = if (config.musicFolders.isNotEmpty()) {
                config.musicFolders
            } else {
                listOf("/") // Fallback to root if no folders configured
            }
            Log.d(TAG, "Folders to scan: $foldersToScan")

            val audioFiles = mutableListOf<WebDAVFile>()
            for (folder in foldersToScan) {
                Log.d(TAG, "Scanning folder: '$folder'")
                val files = webDAVClient.scanAudioFiles(decryptedConfig, folder)
                Log.d(TAG, "Found ${files.size} files in folder: '$folder'")
                audioFiles.addAll(files)
            }
            Log.d(TAG, "Total found ${audioFiles.size} audio files")

            // Convert to Song entities
            val songEntities = audioFiles.mapIndexed { index, file ->
                WebDAVSongEntity(
                    configId = configId,
                    remotePath = file.path,
                    title = extractTitle(file.name),
                    artistName = "Unknown Artist",
                    albumName = "Unknown Album",
                    duration = 0, // Will be updated when played
                    albumArtPath = null,
                    trackNumber = index + 1,
                    year = 0,
                    fileSize = file.size,
                    contentType = file.contentType ?: "audio/mpeg"
                )
            }

            // Clear existing songs for this config and insert new ones
            webDAVDao.deleteSongsByConfig(configId)
            webDAVDao.insertSongs(songEntities)

            // Verify insertion
            val insertedCount = webDAVDao.getSongCount(configId)
            Log.d(TAG, "Verified: $insertedCount songs in database for config $configId")

            // Update last sync time
            val updatedConfig = config.copy(lastSynced = System.currentTimeMillis())
            saveConfig(updatedConfig)

            Log.d(TAG, "Sync completed: ${songEntities.size} songs inserted")
            Result.success(songEntities.size)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure(e)
        }
    }

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val entities = webDAVDao.getAllSongs()
        Log.d(TAG, "getAllSongs: found ${entities.size} entities in database")
        entities.map { entity ->
            entity.toModel(webDAVDao)
        }
    }

    override suspend fun getSongsByConfig(configId: Long): List<Song> =
        withContext(Dispatchers.IO) {
            val entities = webDAVDao.getSongsByConfig(configId)
            Log.d(TAG, "getSongsByConfig($configId): found ${entities.size} entities in database")
            entities.map { entity ->
                entity.toModel(webDAVDao)
            }
        }

    override suspend fun getSongById(songId: Long): Song? = withContext(Dispatchers.IO) {
        webDAVDao.getSongById(songId)?.toModel(webDAVDao)
    }

    override suspend fun deleteSongsByConfig(configId: Long) = withContext(Dispatchers.IO) {
        webDAVDao.deleteSongsByConfig(configId)
    }

    // Extension functions for mapping

    private fun WebDAVConfigEntity.toModel(): WebDAVConfig {
        return WebDAVConfig(
            id = id,
            name = name,
            serverUrl = serverUrl,
            username = username,
            password = password, // This is the encrypted placeholder
            musicFolders = getMusicFoldersList(),
            isEnabled = isEnabled,
            lastSynced = lastSynced
        )
    }

    private suspend fun WebDAVSongEntity.toModel(dao: WebDAVDao): Song {
        // Get the config to build the full URL
        val config = dao.getConfigById(configId)

        val fullUrl = if (config != null) {
            buildFullUrl(config.serverUrl, remotePath)
        } else {
            remotePath
        }

        return Song(
            id = id,
            title = title,
            trackNumber = trackNumber,
            year = year,
            duration = duration,
            data = fullUrl, // For WebDAV songs, data contains the full URL
            dateModified = 0,
            albumId = configId, // Use configId as albumId for grouping
            albumName = albumName,
            artistId = configId, // Use configId as artistId for grouping
            artistName = artistName,
            composer = null,
            albumArtist = null,
            sourceType = SourceType.WEBDAV,
            remotePath = fullUrl,
            webDavConfigId = configId
        )
    }

    private fun buildFullUrl(serverUrl: String, path: String): String {
        val cleanBaseUrl = serverUrl.trimEnd('/')
        val cleanPath = path.trim()
        return if (cleanPath.isEmpty() || cleanPath == "/") {
            "$cleanBaseUrl/"
        } else {
            val pathWithSlash = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
            "$cleanBaseUrl$pathWithSlash"
        }
    }

    private fun extractTitle(filename: String): String {
        // Remove extension and clean up the filename
        return filename.substringBeforeLast('.')
            .replace("_", " ")
            .replace(".", " ")
            .trim()
    }

    private fun isAudioFile(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }
}
