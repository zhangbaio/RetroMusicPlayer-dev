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

import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import code.name.monkey.retromusic.db.WebDAVConfigEntity
import code.name.monkey.retromusic.db.WebDAVSongEntity
import code.name.monkey.retromusic.db.WebDAVDao
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.WebDAVConfig
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.webdav.SardineWebDAVClient
import code.name.monkey.retromusic.webdav.WebDAVClient
import code.name.monkey.retromusic.webdav.WebDAVCryptoUtil
import code.name.monkey.retromusic.webdav.WebDAVFile
import code.name.monkey.retromusic.webdav.WebDAVMetadataParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest

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
        private const val FOLDER_SCAN_PARALLELISM = 3

        // Keep sync fast for large lossless libraries by capping duration probing aggressively.
        private const val MAX_DURATION_PROBE_FILE_SIZE = 20L * 1024 * 1024 // 20MB
        private const val MAX_DURATION_PROBE_FILES_PER_SYNC = 24
        private const val MAX_DURATION_PROBE_TOTAL_BYTES_PER_SYNC = 180L * 1024 * 1024 // 180MB

        private val DURATION_PROBE_ALLOWED_EXTENSIONS_FOR_SYNC = setOf(
            "mp3", "aac", "m4a", "ogg", "opus", "wma", "mp4a", "m4b"
        )

        private const val MAX_DURATION_PROBE_FILE_SIZE_FOR_BACKFILL = 180L * 1024 * 1024 // 180MB
        private const val MAX_DURATION_PROBE_TOTAL_BYTES_PER_BACKFILL = 700L * 1024 * 1024 // 700MB
        private val DURATION_PROBE_ALLOWED_EXTENSIONS_FOR_BACKFILL = setOf(
            "mp3", "aac", "m4a", "ogg", "opus", "wma", "mp4a", "m4b",
            "flac", "wav", "alac", "aiff", "ape"
        )
    }

    private data class DurationProbeBudget(
        var remainingFiles: Int,
        var remainingBytes: Long
    )

    private data class FolderSyncResult(
        val quickSignature: String?,
        val scanResult: SardineWebDAVClient.ScanAudioResult?,
        val skipped: Boolean
    )

    private data class PendingSongUpsert(
        val file: WebDAVFile,
        val trackNumber: Int,
        val existingSong: WebDAVSongEntity?,
        val resolvedContentType: String,
        val remoteLastModified: Long,
        val resolvedAlbumArtPath: String?,
        val fileFingerprint: String
    )

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
        val previousEntity = if (config.id > 0L) {
            webDAVDao.getConfigById(config.id)
        } else {
            null
        }
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

        if (previousEntity != null) {
            val identityChanged =
                previousEntity.serverUrl != config.serverUrl || previousEntity.username != config.username
            if (identityChanged) {
                PreferenceUtil.clearWebDavSyncedFolders(config.id)
                PreferenceUtil.clearWebDavFolderSignatures(config.id)
                PreferenceUtil.clearWebDavFailedFolders(config.id)
                PreferenceUtil.clearWebDavDirectorySummaries(config.id)
                PreferenceUtil.clearWebDavSyncCheckpoint(config.id)
            }
        }

        id
    }

    override suspend fun deleteConfig(config: WebDAVConfig) = withContext(Dispatchers.IO) {
        webDAVDao.deleteSongsByConfig(config.id)
        webDAVDao.deleteConfigById(config.id)
        WebDAVCryptoUtil.removePassword(config.id)
        PreferenceUtil.clearWebDavSyncedFolders(config.id)
        PreferenceUtil.clearWebDavFolderSignatures(config.id)
        PreferenceUtil.clearWebDavFailedFolders(config.id)
        PreferenceUtil.clearWebDavDirectorySummaries(config.id)
        PreferenceUtil.clearWebDavSyncCheckpoint(config.id)
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

    override suspend fun syncSongs(
        configId: Long,
        onProgress: (suspend (WebDAVSyncProgress) -> Unit)?
    ): Result<Int> = syncSongsInternal(
        configId = configId,
        retryFailedOnly = false,
        onProgress = onProgress
    )

    override suspend fun syncFailedFolders(
        configId: Long,
        onProgress: (suspend (WebDAVSyncProgress) -> Unit)?
    ): Result<Int> = syncSongsInternal(
        configId = configId,
        retryFailedOnly = true,
        onProgress = onProgress
    )

    override suspend fun getPendingFailedFolderCount(configId: Long): Int = withContext(Dispatchers.IO) {
        PreferenceUtil.getWebDavFailedFolders(configId)
            .map(::normalizeFolderPath)
            .distinct()
            .size
    }

    private suspend fun syncSongsInternal(
        configId: Long,
        retryFailedOnly: Boolean,
        onProgress: (suspend (WebDAVSyncProgress) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val config = getConfigById(configId)
                ?: return@withContext Result.failure(Exception("Configuration not found"))

            Log.d(
                TAG,
                "Starting sync for config: ${config.name}, retryFailedOnly=$retryFailedOnly"
            )
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
            val selectedFolderSet = selectedFolders.toSet()
            val previouslySyncedFolders = PreferenceUtil
                .getWebDavSyncedFolders(configId)
                .map(::normalizeFolderPath)
                .toSet()
            val previousFolderSignatures = PreferenceUtil
                .getWebDavFolderSignatures(configId)
                .mapKeys { normalizeFolderPath(it.key) }
            val previousDirectorySummaries = PreferenceUtil
                .getWebDavDirectorySummaries(configId)
                .mapKeys { normalizeFolderPath(it.key) }
                .filterKeys { path ->
                    selectedFolders.any { folder -> isPathUnderFolder(path, folder) }
                }
            val queuedFailedFolders = PreferenceUtil
                .getWebDavFailedFolders(configId)
                .map(::normalizeFolderPath)
                .filter { it in selectedFolderSet }
                .toSet()
            val addedFolders = selectedFolders.filterNot { it in previouslySyncedFolders }
            val addedFolderSet = addedFolders.toSet()
            val removedFolders = previouslySyncedFolders.filterNot { it in selectedFolderSet }
            val isFirstSync = config.lastSynced <= 0L || previouslySyncedFolders.isEmpty()

            if (selectedFolders.isEmpty()) {
                if (existingSongs.isNotEmpty()) {
                    Log.d(TAG, "No folders selected, clearing ${existingSongs.size} local songs for config $configId")
                    webDAVDao.deleteSongsByConfig(configId)
                }
                PreferenceUtil.clearWebDavSyncedFolders(configId)
                PreferenceUtil.clearWebDavFolderSignatures(configId)
                PreferenceUtil.clearWebDavFailedFolders(configId)
                PreferenceUtil.clearWebDavDirectorySummaries(configId)
                PreferenceUtil.clearWebDavSyncCheckpoint(configId)
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
            val existingByPath = existingSongs
                .associateBy { it.remotePath }
                .toMutableMap()
            staleByFolderSelection.forEach(existingByPath::remove)

            val sardineClient = webDAVClient as? SardineWebDAVClient
                ?: return@withContext Result.failure(Exception("Invalid WebDAV client type"))

            val retryFoldersInOrder = selectedFolders.filter { it in queuedFailedFolders }
            val plannedScanFolders = when {
                retryFailedOnly -> retryFoldersInOrder
                isFirstSync -> selectedFolders
                addedFolders.isNotEmpty() -> addedFolders
                removedFolders.isNotEmpty() -> emptyList()
                else -> selectedFolders
            }

            if (retryFailedOnly && plannedScanFolders.isEmpty()) {
                Log.d(TAG, "No failed folders queued for retry, skipping sync")
                return@withContext Result.success(0)
            }
            val checkpointScope = buildSyncCheckpointScope(
                retryFailedOnly = retryFailedOnly,
                folders = plannedScanFolders
            )
            val savedCheckpointScope = PreferenceUtil.getWebDavSyncCheckpointScope(configId)
            if (savedCheckpointScope != checkpointScope) {
                PreferenceUtil.clearWebDavSyncCheckpoint(configId)
                PreferenceUtil.setWebDavSyncCheckpointScope(configId, checkpointScope)
            }
            val restoredCheckpointFolders = if (savedCheckpointScope == checkpointScope) {
                PreferenceUtil.getWebDavSyncCheckpointFolders(configId)
                    .map(::normalizeFolderPath)
                    .filter { it in plannedScanFolders.toSet() }
                    .toMutableSet()
            } else {
                mutableSetOf()
            }
            val plannedFolderSet = plannedScanFolders.toSet()
            restoredCheckpointFolders.removeAll { it !in plannedFolderSet }
            if (restoredCheckpointFolders.isNotEmpty()) {
                PreferenceUtil.setWebDavSyncCheckpointFolders(configId, restoredCheckpointFolders)
                Log.d(
                    TAG,
                    "Resuming sync checkpoint: completed=${restoredCheckpointFolders.size}/${plannedScanFolders.size}"
                )
            }
            val scanFolders = plannedScanFolders.filterNot { it in restoredCheckpointFolders }

            val successfulFolders = mutableSetOf<String>()
            val failedFolders = mutableSetOf<String>()
            val nextFolderSignatures = previousFolderSignatures
                .filterKeys { it in selectedFolderSet }
                .toMutableMap()
            val nextDirectorySummaries = previousDirectorySummaries.toMutableMap()
            var totalSyncedSongs = 0
            var totalDeletedByRemote = 0
            var totalUnchangedSkipped = 0
            var totalQuickSkippedFolders = 0
            var completedFolders = 0
            val durationProbeBudget = DurationProbeBudget(
                remainingFiles = MAX_DURATION_PROBE_FILES_PER_SYNC,
                remainingBytes = MAX_DURATION_PROBE_TOTAL_BYTES_PER_SYNC
            )

            if (scanFolders.isEmpty()) {
                Log.d(TAG, "Skipping remote scan, only folder selection changed (removed=$removedFolders)")
            } else {
                scanFolders.chunked(FOLDER_SCAN_PARALLELISM).forEach { folderBatch ->
                    val batchResults = coroutineScope {
                        folderBatch.map { folder ->
                            async {
                                folder to runCatching {
                                    val quickSignature = runCatching {
                                        sardineClient.buildFolderQuickSignature(decryptedConfig, folder)
                                    }.onFailure { error ->
                                        Log.w(
                                            TAG,
                                            "Quick signature failed for '$folder', fallback to full scan: ${error.message}"
                                        )
                                    }.getOrNull()
                                    val shouldQuickSkip =
                                        quickSignature != null &&
                                        !isFirstSync &&
                                            folder !in addedFolderSet &&
                                            folder in previouslySyncedFolders &&
                                            previousFolderSignatures[folder] == quickSignature
                                    if (shouldQuickSkip) {
                                        FolderSyncResult(
                                            quickSignature = quickSignature,
                                            scanResult = null,
                                            skipped = true
                                        )
                                    } else {
                                        FolderSyncResult(
                                            quickSignature = quickSignature,
                                            scanResult = sardineClient.scanAudioFilesWithMetadata(
                                                decryptedConfig,
                                                folder
                                            ),
                                            skipped = false
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    batchResults.forEach { (folder, result) ->
                        result
                            .onSuccess { folderResult ->
                                successfulFolders += folder
                                folderResult.quickSignature?.let { signature ->
                                    nextFolderSignatures[folder] = signature
                                }
                                if (folderResult.skipped) {
                                    totalQuickSkippedFolders += 1
                                    completedFolders += 1
                                    restoredCheckpointFolders += folder
                                    persistSyncCheckpointProgress(
                                        configId = configId,
                                        scope = checkpointScope,
                                        completedFolders = restoredCheckpointFolders
                                    )
                                    onProgress?.invoke(
                                        WebDAVSyncProgress(
                                            completedFolders = completedFolders,
                                            totalFolders = scanFolders.size,
                                            folderPath = folder,
                                            syncedSongs = 0,
                                            failed = false
                                        )
                                    )
                                    Log.d(
                                        TAG,
                                        "Folder quick-skipped '$folder' by signature"
                                    )
                                    return@onSuccess
                                }
                                val scanResult = folderResult.scanResult
                                    ?: throw IllegalStateException("Scan result missing for folder '$folder'")
                                val filesByPath = linkedMapOf<String, WebDAVFile>()
                                scanResult.audioFiles.forEach { file ->
                                    filesByPath[file.path] = file
                                }
                                val scannedFiles = filesByPath.values.toList()
                                val currentDirectorySummaries =
                                    buildDirectorySummaries(scannedFiles, scanResult.folderCoverMap)
                                nextDirectorySummaries.keys.removeAll { path ->
                                    isPathUnderFolder(path, folder)
                                }
                                nextDirectorySummaries.putAll(currentDirectorySummaries)
                                val unchangedDirectories = currentDirectorySummaries
                                    .filter { (path, summary) ->
                                        previousDirectorySummaries[path] == summary
                                    }
                                    .keys
                                val remotePaths = filesByPath.keys
                                val staleByRemoteDeletion = existingByPath
                                    .keys
                                    .asSequence()
                                    .filter { path -> isPathUnderFolder(path, folder) }
                                    .filterNot { it in remotePaths }
                                    .toSet()
                                deleteSongsByPathsInBatches(configId, staleByRemoteDeletion)
                                staleByRemoteDeletion.forEach(existingByPath::remove)
                                totalDeletedByRemote += staleByRemoteDeletion.size

                                val pendingUpserts = mutableListOf<PendingSongUpsert>()
                                scannedFiles.forEachIndexed { index, file ->
                                    val existingSong = existingByPath[file.path]
                                    val parentPath = parentFolderPath(file.path)
                                    val resolvedContentType =
                                        file.contentType ?: existingSong?.contentType ?: "audio/mpeg"
                                    val remoteLastModified =
                                        file.lastModified ?: existingSong?.remoteLastModified ?: 0L
                                    val resolvedAlbumArtPath =
                                        scanResult.folderCoverMap[parentPath] ?: existingSong?.albumArtPath
                                    val fileFingerprint = buildFileFingerprint(
                                        fileSize = file.size,
                                        remoteLastModified = remoteLastModified,
                                        contentType = resolvedContentType
                                    )
                                    if (existingSong != null && parentPath in unchangedDirectories) {
                                        totalUnchangedSkipped += 1
                                        return@forEachIndexed
                                    }
                                    if (isFileUnchanged(existingSong, fileFingerprint, resolvedAlbumArtPath)) {
                                        totalUnchangedSkipped += 1
                                        return@forEachIndexed
                                    }
                                    pendingUpserts += PendingSongUpsert(
                                        file = file,
                                        trackNumber = index + 1,
                                        existingSong = existingSong,
                                        resolvedContentType = resolvedContentType,
                                        remoteLastModified = remoteLastModified,
                                        resolvedAlbumArtPath = resolvedAlbumArtPath,
                                        fileFingerprint = fileFingerprint
                                    )
                                }

                                if (pendingUpserts.isEmpty()) {
                                    completedFolders += 1
                                    restoredCheckpointFolders += folder
                                    persistSyncCheckpointProgress(
                                        configId = configId,
                                        scope = checkpointScope,
                                        completedFolders = restoredCheckpointFolders
                                    )
                                    onProgress?.invoke(
                                        WebDAVSyncProgress(
                                            completedFolders = completedFolders,
                                            totalFolders = scanFolders.size,
                                            folderPath = folder,
                                            syncedSongs = 0,
                                            failed = false
                                        )
                                    )
                                    Log.d(
                                        TAG,
                                        "Folder synced '$folder': files=${scanResult.audioFiles.size}, upserted=0, unchangedSkipped=$totalUnchangedSkipped, deletedRemote=${staleByRemoteDeletion.size}, covers=${scanResult.folderCoverMap.size}"
                                    )
                                    return@onSuccess
                                }

                                val metadataParser = WebDAVMetadataParser(scannedFiles)
                                val songEntities = pendingUpserts.map { pending ->
                                    val parsedMetadata = metadataParser.parse(pending.file)
                                    val duration = resolveDurationMillis(
                                        file = pending.file,
                                        config = decryptedConfig,
                                        existingDuration = pending.existingSong?.duration ?: 0L,
                                        budget = durationProbeBudget
                                    )
                                    WebDAVSongEntity(
                                        id = pending.existingSong?.id ?: 0L,
                                        configId = configId,
                                        remotePath = pending.file.path,
                                        title = resolveTitle(pending.existingSong?.title, parsedMetadata.title),
                                        artistName = resolveArtistName(
                                            pending.existingSong?.artistName,
                                            parsedMetadata.artistName
                                        ),
                                        albumName = pending.existingSong?.albumName ?: "Unknown Album",
                                        duration = duration,
                                        albumArtPath = pending.resolvedAlbumArtPath,
                                        trackNumber = pending.trackNumber,
                                        year = pending.existingSong?.year ?: 0,
                                        fileSize = pending.file.size,
                                        contentType = pending.resolvedContentType,
                                        remoteLastModified = pending.remoteLastModified,
                                        fileFingerprint = pending.fileFingerprint
                                    )
                                }
                                if (songEntities.isNotEmpty()) {
                                    webDAVDao.insertSongs(songEntities)
                                    songEntities.forEach { existingByPath[it.remotePath] = it }
                                }
                                totalSyncedSongs += songEntities.size
                                completedFolders += 1
                                restoredCheckpointFolders += folder
                                persistSyncCheckpointProgress(
                                    configId = configId,
                                    scope = checkpointScope,
                                    completedFolders = restoredCheckpointFolders
                                )
                                onProgress?.invoke(
                                    WebDAVSyncProgress(
                                        completedFolders = completedFolders,
                                        totalFolders = scanFolders.size,
                                        folderPath = folder,
                                        syncedSongs = songEntities.size,
                                        failed = false
                                    )
                                )
                                Log.d(
                                    TAG,
                                    "Folder synced '$folder': files=${scanResult.audioFiles.size}, upserted=${songEntities.size}, unchangedSkipped=$totalUnchangedSkipped, deletedRemote=${staleByRemoteDeletion.size}, covers=${scanResult.folderCoverMap.size}"
                                )
                            }
                            .onFailure { error ->
                                failedFolders += folder
                                completedFolders += 1
                                onProgress?.invoke(
                                    WebDAVSyncProgress(
                                        completedFolders = completedFolders,
                                        totalFolders = scanFolders.size,
                                        folderPath = folder,
                                        syncedSongs = 0,
                                        failed = true
                                    )
                                )
                                Log.e(TAG, "Failed to scan folder '$folder'", error)
                            }
                    }
                }
            }

            if (scanFolders.isNotEmpty() && successfulFolders.isEmpty()) {
                persistFailedFolderQueue(
                    configId = configId,
                    queuedFailedFolders = queuedFailedFolders,
                    successfulFolders = successfulFolders,
                    failedFolders = failedFolders
                )
                return@withContext Result.failure(Exception("Sync failed, unable to scan selected folders"))
            }

            // Update last sync time
            val updatedConfig = config.copy(lastSynced = System.currentTimeMillis())
            saveConfig(updatedConfig)
            val syncedFolders = if (scanFolders.isEmpty()) {
                selectedFolderSet
            } else {
                selectedFolderSet - failedFolders
            }
            PreferenceUtil.setWebDavSyncedFolders(configId, syncedFolders)
            val retainedFolderSignatures = nextFolderSignatures
                .filterKeys { it in syncedFolders }
            if (retainedFolderSignatures.isEmpty()) {
                PreferenceUtil.clearWebDavFolderSignatures(configId)
            } else {
                PreferenceUtil.setWebDavFolderSignatures(configId, retainedFolderSignatures)
            }
            val retainedDirectorySummaries = nextDirectorySummaries
                .filterKeys { path ->
                    syncedFolders.any { folder -> isPathUnderFolder(path, folder) }
                }
            if (retainedDirectorySummaries.isEmpty()) {
                PreferenceUtil.clearWebDavDirectorySummaries(configId)
            } else {
                PreferenceUtil.setWebDavDirectorySummaries(configId, retainedDirectorySummaries)
            }
            persistFailedFolderQueue(
                configId = configId,
                queuedFailedFolders = queuedFailedFolders,
                successfulFolders = successfulFolders,
                failedFolders = failedFolders
            )
            PreferenceUtil.clearWebDavSyncCheckpoint(configId)

            val totalCount = webDAVDao.getSongCount(configId)
            if (failedFolders.isNotEmpty()) {
                Log.w(TAG, "Sync partially completed. Failed folders: $failedFolders")
            }
            Log.d(
                TAG,
                "Sync completed: upserted=$totalSyncedSongs, unchangedSkipped=$totalUnchangedSkipped, quickSkippedFolders=$totalQuickSkippedFolders, deletedBySelection=${staleByFolderSelection.size}, deletedByRemote=$totalDeletedByRemote, total=$totalCount"
            )
            Result.success(totalSyncedSongs)
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

    override suspend fun backfillDurations(
        configId: Long,
        limit: Int
    ): Result<WebDAVDurationBackfillResult> = withContext(Dispatchers.IO) {
        try {
            val boundedLimit = limit.coerceIn(1, 200)
            val config = getConfigById(configId)
                ?: return@withContext Result.failure(Exception("Configuration not found"))
            val plainPassword = resolvePlainPassword(config)
            if (plainPassword.isBlank()) {
                return@withContext Result.failure(Exception("Password is missing, please re-enter password"))
            }
            val decryptedConfig = config.copy(password = plainPassword)

            val candidates = webDAVDao.getSongsMissingDuration(configId, boundedLimit)
            if (candidates.isEmpty()) {
                return@withContext Result.success(
                    WebDAVDurationBackfillResult(
                        updatedSongs = 0,
                        remainingSongs = 0
                    )
                )
            }

            val budget = DurationProbeBudget(
                remainingFiles = candidates.size,
                remainingBytes = MAX_DURATION_PROBE_TOTAL_BYTES_PER_BACKFILL
            )
            var updated = 0
            candidates.forEach { entity ->
                val duration = resolveDurationMillis(
                    file = WebDAVFile(
                        name = entity.remotePath.substringAfterLast('/'),
                        path = entity.remotePath,
                        isDirectory = false,
                        size = entity.fileSize,
                        lastModified = entity.remoteLastModified.takeIf { it > 0L },
                        contentType = entity.contentType
                    ),
                    config = decryptedConfig,
                    existingDuration = entity.duration,
                    budget = budget,
                    maxFileSize = MAX_DURATION_PROBE_FILE_SIZE_FOR_BACKFILL,
                    allowedExtensions = DURATION_PROBE_ALLOWED_EXTENSIONS_FOR_BACKFILL
                )
                if (duration > 0L) {
                    webDAVDao.updateSongDuration(entity.id, duration)
                    updated += 1
                }
            }
            val remaining = webDAVDao.getSongsMissingDurationCount(configId)
            Result.success(
                WebDAVDurationBackfillResult(
                    updatedSongs = updated,
                    remainingSongs = remaining
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Duration backfill failed for configId=$configId", e)
            Result.failure(e)
        }
    }

    private fun buildFileFingerprint(
        fileSize: Long,
        remoteLastModified: Long,
        contentType: String
    ): String {
        return "$fileSize|$remoteLastModified|${contentType.trim().lowercase()}"
    }

    private fun buildDirectorySummaries(
        scannedFiles: List<WebDAVFile>,
        folderCoverMap: Map<String, String>
    ): Map<String, String> {
        val filesByDirectory = scannedFiles.groupBy { parentFolderPath(it.path) }
        return filesByDirectory.mapValues { (directory, files) ->
            buildDirectorySummaryHash(directory, files, folderCoverMap[directory])
        }
    }

    private fun buildDirectorySummaryHash(
        directory: String,
        files: List<WebDAVFile>,
        coverPath: String?
    ): String {
        val payload = buildString {
            append(directory)
            append('|')
            append(coverPath.orEmpty())
            files.sortedBy { it.path }.forEach { file ->
                append('|')
                append(file.path)
                append(':')
                append(file.size)
                append(':')
                append(file.lastModified ?: 0L)
                append(':')
                append(file.contentType.orEmpty())
            }
        }
        return payload.sha256Hex()
    }

    private fun String.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0f).toString(16))
                append((byte.toInt() and 0x0f).toString(16))
            }
        }
    }

    private fun isFileUnchanged(
        existingSong: WebDAVSongEntity?,
        newFingerprint: String,
        resolvedAlbumArtPath: String?
    ): Boolean {
        if (existingSong == null) return false
        if (existingSong.fileFingerprint.isBlank()) return false
        if (existingSong.fileFingerprint != newFingerprint) return false
        return existingSong.albumArtPath == resolvedAlbumArtPath
    }

    private fun persistFailedFolderQueue(
        configId: Long,
        queuedFailedFolders: Set<String>,
        successfulFolders: Set<String>,
        failedFolders: Set<String>
    ) {
        val normalizedQueued = queuedFailedFolders.map(::normalizeFolderPath).toSet()
        val normalizedSuccessful = successfulFolders.map(::normalizeFolderPath).toSet()
        val normalizedFailed = failedFolders.map(::normalizeFolderPath).toSet()
        val nextQueue = (normalizedQueued - normalizedSuccessful) + normalizedFailed
        if (nextQueue.isEmpty()) {
            PreferenceUtil.clearWebDavFailedFolders(configId)
        } else {
            PreferenceUtil.setWebDavFailedFolders(configId, nextQueue)
        }
    }

    private fun buildSyncCheckpointScope(
        retryFailedOnly: Boolean,
        folders: List<String>
    ): String {
        val normalized = folders.map(::normalizeFolderPath).sorted()
        val payload = buildString {
            append(if (retryFailedOnly) "retry" else "full")
            normalized.forEach { folder ->
                append('|')
                append(folder)
            }
        }
        return payload.sha256Hex()
    }

    private fun persistSyncCheckpointProgress(
        configId: Long,
        scope: String,
        completedFolders: Set<String>
    ) {
        PreferenceUtil.setWebDavSyncCheckpointScope(configId, scope)
        PreferenceUtil.setWebDavSyncCheckpointFolders(
            configId,
            completedFolders.map(::normalizeFolderPath).toSet()
        )
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
        val normalizedArtist = artistName.ifBlank { "Unknown Artist" }
        val normalizedAlbum = albumName.ifBlank { "Unknown Album" }
        val synthesizedArtistId = synthesizeEntityId(
            "artist",
            configId,
            normalizedArtist
        )
        // When album metadata is missing, use parent folder to avoid collapsing all songs into one album.
        val albumIdentity = if (normalizedAlbum.equals("Unknown Album", ignoreCase = true)) {
            "${normalizedAlbum}|${parentFolderPath(remotePath)}|$normalizedArtist"
        } else {
            "${normalizedAlbum}|$normalizedArtist"
        }
        val synthesizedAlbumId = synthesizeEntityId(
            "album",
            configId,
            albumIdentity
        )

        return Song(
            id = id,
            title = title,
            trackNumber = trackNumber,
            year = year,
            duration = duration,
            data = fullUrl, // For WebDAV songs, data contains the full URL
            dateModified = 0,
            albumId = synthesizedAlbumId,
            albumName = albumName,
            artistId = synthesizedArtistId,
            artistName = artistName,
            composer = null,
            albumArtist = null,
            sourceType = SourceType.WEBDAV,
            remotePath = fullUrl,
            webDavConfigId = configId,
            webDavAlbumArtPath = fullAlbumArtUrl
        )
    }

    private fun synthesizeEntityId(kind: String, configId: Long, identity: String): Long {
        // Build deterministic negative IDs to avoid collision with MediaStore positive IDs.
        val key = "webdav|$kind|$configId|${identity.trim().lowercase()}"
        val hash = key.hashCode().toLong()
        val positive = if (hash == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(hash)
        return -positive.coerceAtLeast(1L)
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

    private fun resolveTitle(existingTitle: String?, parsedTitle: String): String {
        val parsed = parsedTitle.trim()
        if (parsed.isNotEmpty()) {
            return parsed
        }
        return existingTitle?.trim().orEmpty()
    }

    private fun resolveArtistName(existingArtist: String?, parsedArtist: String): String {
        val parsed = parsedArtist.trim()
        if (parsed.isNotEmpty()) {
            return parsed
        }
        val existing = existingArtist?.trim().orEmpty()
        return if (existing.isNotEmpty() && !isUnknownArtist(existing)) existing else ""
    }

    private fun isUnknownArtist(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized == Artist.UNKNOWN_ARTIST_DISPLAY_NAME.lowercase() ||
            normalized == "unknown" ||
            normalized == "<unknown>"
    }

    private fun resolveDurationMillis(
        file: WebDAVFile,
        config: WebDAVConfig,
        existingDuration: Long,
        budget: DurationProbeBudget,
        maxFileSize: Long = MAX_DURATION_PROBE_FILE_SIZE,
        allowedExtensions: Set<String> = DURATION_PROBE_ALLOWED_EXTENSIONS_FOR_SYNC
    ): Long {
        if (existingDuration > 0L) {
            return existingDuration
        }
        if (!consumeDurationProbeBudget(file, budget, maxFileSize, allowedExtensions)) {
            return 0L
        }
        val url = buildFullUrl(config.serverUrl, file.path)
        val encodedCredentials = Base64.encodeToString(
            "${config.username}:${config.password}".toByteArray(),
            Base64.NO_WRAP
        )
        val headers = mapOf("Authorization" to "Basic $encodedCredentials")

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, headers)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (error: Exception) {
            Log.d(TAG, "Failed to probe duration for ${file.path}: ${error.message}")
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun consumeDurationProbeBudget(
        file: WebDAVFile,
        budget: DurationProbeBudget,
        maxFileSize: Long,
        allowedExtensions: Set<String>
    ): Boolean {
        if (budget.remainingFiles <= 0 || budget.remainingBytes <= 0L) {
            return false
        }
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension !in allowedExtensions) {
            return false
        }
        if (file.size <= 0L || file.size > maxFileSize) {
            return false
        }
        if (budget.remainingBytes < file.size) {
            return false
        }
        budget.remainingFiles -= 1
        budget.remainingBytes -= file.size
        return true
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
