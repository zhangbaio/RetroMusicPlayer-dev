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
        private const val DELETE_PATH_BATCH_SIZE = 300

        private val COVER_FILE_NAMES = setOf(
            "cover.jpg", "album.jpg", "folder.jpg",
            "cover.png", "album.png", "folder.png",
            "cover.webp", "album.webp", "folder.webp"
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
        val plainPassword = resolvePlainPassword(config)
        if (plainPassword.isBlank()) {
            throw IllegalArgumentException("Password is missing, please re-enter password")
        }

        // Encrypt password before saving
        val encryptedPassword = if (config.id > 0) {
            WebDAVCryptoUtil.encryptPassword(plainPassword, config.id)
        } else {
            // For new configs, we'll use a temporary ID and re-encrypt after insert
            plainPassword
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
            WebDAVCryptoUtil.encryptPassword(plainPassword, id)
        }

        id
    }

    override suspend fun deleteConfig(config: WebDAVConfig) = withContext(Dispatchers.IO) {
        webDAVDao.deleteSongsByConfig(config.id)
        webDAVDao.deleteConfigById(config.id)
        WebDAVCryptoUtil.removePassword(config.id)
    }

    override suspend fun testConnection(config: WebDAVConfig): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val plainPassword = resolvePlainPassword(config)
                if (plainPassword.isBlank()) {
                    return@withContext Result.failure(Exception("Password is missing, please re-enter password"))
                }
                val resolvedConfig = config.copy(password = plainPassword)
                val success = webDAVClient.testConnection(resolvedConfig)
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

            val plainPassword = resolvePlainPassword(config)
            if (plainPassword.isBlank()) {
                return@withContext Result.failure(Exception("Password is missing, please re-enter password"))
            }
            val decryptedConfig = config.copy(password = plainPassword)

            val existingSongs = webDAVDao.getSongsByConfig(configId)
            val selectedFolders = config.musicFolders
                .map(::normalizeFolderPath)
                .distinct()

            if (selectedFolders.isEmpty()) {
                if (existingSongs.isNotEmpty()) {
                    Log.d(TAG, "No folders selected, clearing ${existingSongs.size} local songs for config $configId")
                    webDAVDao.deleteSongsByConfig(configId)
                }
                val updatedConfig = config.copy(lastSynced = System.currentTimeMillis())
                saveConfig(updatedConfig)
                return@withContext Result.success(0)
            }

            // Remove songs that are no longer under the selected folders.
            val staleByFolderSelection = existingSongs
                .asSequence()
                .filterNot { song ->
                    selectedFolders.any { folder -> isPathUnderFolder(song.remotePath, folder) }
                }
                .map { it.remotePath }
                .toSet()
            deleteSongsByPathsInBatches(configId, staleByFolderSelection)

            val sardineClient = webDAVClient as? SardineWebDAVClient
                ?: return@withContext Result.failure(Exception("Invalid WebDAV client type"))

            val scannedFilesByPath = linkedMapOf<String, WebDAVFile>()
            val successfulFolders = mutableListOf<String>()
            val failedFolders = mutableListOf<String>()

            selectedFolders.forEach { folder ->
                Log.d(TAG, "Scanning folder: '$folder'")
                runCatching { sardineClient.scanAudioFiles(decryptedConfig, folder) }
                    .onSuccess { files ->
                        successfulFolders += folder
                        files.forEach { file ->
                            if (!scannedFilesByPath.containsKey(file.path)) {
                                scannedFilesByPath[file.path] = file
                            }
                        }
                        Log.d(TAG, "Found ${files.size} files in folder: '$folder'")
                    }
                    .onFailure { error ->
                        failedFolders += folder
                        Log.e(TAG, "Failed to scan folder '$folder'", error)
                    }
            }

            if (successfulFolders.isEmpty()) {
                return@withContext Result.failure(Exception("Sync failed, unable to scan selected folders"))
            }

            val scannedFiles = scannedFilesByPath.values.toList()
            Log.d(TAG, "Total found ${scannedFiles.size} audio files")
            val folderCoverMap = findFolderCoverMap(sardineClient, decryptedConfig, scannedFiles)
            val existingByPath = existingSongs.associateBy { it.remotePath }
            val remotePaths = scannedFilesByPath.keys.toSet()

            // Only delete missing songs in folders that scanned successfully.
            val staleByRemoteDeletion = existingSongs
                .asSequence()
                .map { it.remotePath }
                .filter { path ->
                    successfulFolders.any { folder -> isPathUnderFolder(path, folder) }
                }
                .filterNot { path -> path in remotePaths }
                .toSet()
            deleteSongsByPathsInBatches(configId, staleByRemoteDeletion)

            val songEntities = scannedFiles.mapIndexed { index, file ->
                val existingSong = existingByPath[file.path]
                val parentPath = parentFolderPath(file.path)
                WebDAVSongEntity(
                    id = existingSong?.id ?: 0L,
                    configId = configId,
                    remotePath = file.path,
                    title = extractTitle(file.name),
                    artistName = existingSong?.artistName ?: "Unknown Artist",
                    albumName = existingSong?.albumName ?: "Unknown Album",
                    duration = existingSong?.duration ?: 0,
                    albumArtPath = folderCoverMap[parentPath] ?: existingSong?.albumArtPath,
                    trackNumber = index + 1,
                    year = existingSong?.year ?: 0,
                    fileSize = file.size,
                    contentType = file.contentType ?: existingSong?.contentType ?: "audio/mpeg"
                )
            }

            if (songEntities.isNotEmpty()) {
                webDAVDao.insertSongs(songEntities)
            }

            // Update last sync time
            val updatedConfig = config.copy(lastSynced = System.currentTimeMillis())
            saveConfig(updatedConfig)

            val totalCount = webDAVDao.getSongCount(configId)
            if (failedFolders.isNotEmpty()) {
                Log.w(TAG, "Sync partially completed. Failed folders: $failedFolders")
            }
            Log.d(
                TAG,
                "Sync completed: upserted=${songEntities.size}, deletedBySelection=${staleByFolderSelection.size}, deletedByRemote=${staleByRemoteDeletion.size}, total=$totalCount"
            )
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

    override suspend fun deleteSongsByIds(songIds: List<Long>) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        webDAVDao.deleteSongsByIds(songIds)
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
        val fullAlbumArtUrl = if (config != null && !albumArtPath.isNullOrBlank()) {
            buildFullUrl(config.serverUrl, albumArtPath)
        } else {
            null
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
            webDavConfigId = configId,
            webDavAlbumArtPath = fullAlbumArtUrl
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

    private suspend fun findFolderCoverMap(
        client: SardineWebDAVClient,
        config: WebDAVConfig,
        audioFiles: List<WebDAVFile>
    ): Map<String, String> {
        val folders = audioFiles
            .map { parentFolderPath(it.path) }
            .toSet()
        val result = mutableMapOf<String, String>()
        folders.forEach { folderPath ->
            runCatching {
                val files = client.listFiles(config, folderPath)
                files.firstOrNull { file ->
                    !file.isDirectory && file.name.lowercase() in COVER_FILE_NAMES
                }?.path
            }.onSuccess { coverPath ->
                if (!coverPath.isNullOrBlank()) {
                    result[folderPath] = coverPath
                }
            }.onFailure { error ->
                Log.d(TAG, "Failed to resolve cover for folder '$folderPath': ${error.message}")
            }
        }
        return result
    }

    private fun parentFolderPath(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isBlank()) "/" else parent
    }

    private fun normalizeFolderPath(path: String): String {
        val withLeadingSlash = if (path.startsWith("/")) path else "/$path"
        val normalized = withLeadingSlash.trim().trimEnd('/')
        return if (normalized.isBlank()) "/" else normalized
    }

    private fun isPathUnderFolder(path: String, folderPath: String): Boolean {
        val normalizedFolder = normalizeFolderPath(folderPath)
        val normalizedPath = normalizeFolderPath(path)
        return if (normalizedFolder == "/") {
            true
        } else {
            normalizedPath == normalizedFolder || normalizedPath.startsWith("$normalizedFolder/")
        }
    }

    private suspend fun deleteSongsByPathsInBatches(configId: Long, paths: Set<String>) {
        if (paths.isEmpty()) return
        paths.chunked(DELETE_PATH_BATCH_SIZE).forEach { batch ->
            webDAVDao.deleteSongsByPaths(configId, batch)
        }
    }

    private fun resolvePlainPassword(config: WebDAVConfig): String {
        if (!config.password.startsWith("encrypted://")) {
            return config.password
        }
        val decrypted = WebDAVCryptoUtil.decryptPassword(config.password, config.id)
        return if (decrypted.startsWith("encrypted://")) "" else decrypted
    }
}
