package code.name.monkey.retromusic.repository

import android.util.Log
import code.name.monkey.retromusic.db.ServerConfigEntity
import code.name.monkey.retromusic.db.ServerDao
import code.name.monkey.retromusic.db.ServerSongEntity
import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.network.MusicApiService
import code.name.monkey.retromusic.network.ScanTaskRequest
import code.name.monkey.retromusic.network.TrackDetailResponse
import code.name.monkey.retromusic.network.CreatePlaylistApiRequest
import code.name.monkey.retromusic.network.TrackIdsApiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implementation of ServerRepository that fetches music data from the backend REST API
 * and caches it locally in Room database.
 */
class RealServerRepository(
    private val serverDao: ServerDao,
    private val apiServiceProvider: suspend (ServerConfig) -> MusicApiService
) : ServerRepository {

    companion object {
        private const val TAG = "RealServerRepository"
        private const val SYNC_PAGE_SIZE = 200
    }

    // ---- Config management ----

    override suspend fun getAllConfigs(): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getAllConfigs().map { it.toModel() }
    }

    override suspend fun getEnabledConfigs(): List<ServerConfig> = withContext(Dispatchers.IO) {
        serverDao.getEnabledConfigs().map { it.toModel() }
    }

    override suspend fun getConfigById(configId: Long): ServerConfig? = withContext(Dispatchers.IO) {
        serverDao.getConfigById(configId)?.toModel()
    }

    override suspend fun saveConfig(config: ServerConfig): Long = withContext(Dispatchers.IO) {
        val entity = ServerConfigEntity(
            id = config.id,
            name = config.name,
            serverUrl = config.serverUrl.trimEnd('/'),
            apiToken = config.apiToken,
            isEnabled = config.isEnabled,
            lastSynced = config.lastSynced
        )
        serverDao.insertConfig(entity)
    }

    override suspend fun deleteConfig(config: ServerConfig) = withContext(Dispatchers.IO) {
        serverDao.deleteSongsByConfig(config.id)
        serverDao.deleteConfigById(config.id)
    }

    override suspend fun testConnection(config: ServerConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val apiService = apiServiceProvider(config)
            val response = apiService.getTracks(pageNo = 1, pageSize = 1)
            if (response.isSuccess) {
                Result.success(true)
            } else {
                Result.failure(Exception("Server returned error: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Result.failure(e)
        }
    }

    // ---- Sync ----

    override suspend fun syncSongs(
        configId: Long,
        onProgress: (suspend (syncedCount: Int, totalCount: Long) -> Unit)?
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val config = serverDao.getConfigById(configId)?.toModel()
                ?: return@withContext Result.failure(Exception("Config not found: $configId"))

            val apiService = apiServiceProvider(config)
            var pageNo = 1
            var totalSynced = 0
            var totalCount = 0L
            val allTrackIds = mutableListOf<Long>()
            var useAggregatedEndpoint = true

            // Paginate through all tracks
            while (true) {
                val response = try {
                    if (useAggregatedEndpoint) {
                        apiService.getAggregatedTracks(
                            pageNo = pageNo,
                            pageSize = SYNC_PAGE_SIZE,
                            sortBy = "updated_at",
                            sortOrder = "DESC"
                        )
                    } else {
                        apiService.getTracks(pageNo = pageNo, pageSize = SYNC_PAGE_SIZE)
                    }
                } catch (e: Exception) {
                    if (useAggregatedEndpoint && pageNo == 1) {
                        Log.w(TAG, "Aggregated tracks endpoint unavailable, fallback to /api/v1/tracks", e)
                        useAggregatedEndpoint = false
                        apiService.getTracks(pageNo = pageNo, pageSize = SYNC_PAGE_SIZE)
                    } else {
                        throw e
                    }
                }
                if (!response.isSuccess || response.data == null) {
                    if (totalSynced == 0) {
                        return@withContext Result.failure(Exception("API error: ${response.message}"))
                    }
                    break
                }

                val pagedResult = response.data
                totalCount = pagedResult.total

                if (pagedResult.records.isEmpty()) break

                // Fetch full details for each track and convert to entities
                val entities = mutableListOf<ServerSongEntity>()
                for (track in pagedResult.records) {
                    try {
                        val detailResponse = apiService.getTrack(track.id)
                        if (detailResponse.isSuccess && detailResponse.data != null) {
                            entities.add(detailResponse.data.toEntity(configId))
                            allTrackIds.add(track.id)
                        }
                    } catch (e: Exception) {
                        // Log but continue - don't fail the whole sync for one track
                        Log.w(TAG, "Failed to fetch track detail for id=${track.id}", e)
                        // Use lightweight data from list response as fallback
                        entities.add(track.toEntity(configId))
                        allTrackIds.add(track.id)
                    }
                }

                // Batch upsert
                if (entities.isNotEmpty()) {
                    serverDao.insertSongs(entities)
                }

                totalSynced += entities.size
                onProgress?.invoke(totalSynced, totalCount)

                if (pagedResult.records.size < SYNC_PAGE_SIZE) break
                pageNo++
            }

            // Remove tracks that are no longer on the server
            if (allTrackIds.isNotEmpty()) {
                // Process in batches to avoid SQLite variable limit
                allTrackIds.chunked(500).forEach { batch ->
                    serverDao.deleteRemovedSongs(configId, batch)
                }
            }

            // Update last synced timestamp
            serverDao.getConfigById(configId)?.let { configEntity ->
                serverDao.insertConfig(configEntity.copy(lastSynced = System.currentTimeMillis()))
            }

            Log.i(TAG, "Sync completed: $totalSynced songs synced")
            Result.success(totalSynced)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchAggregatedSongsLive(): Result<List<Song>> = withContext(Dispatchers.IO) {
        val enabledConfigs = serverDao.getEnabledConfigs()
        if (enabledConfigs.isEmpty()) {
            return@withContext Result.success(emptyList())
        }

        val mergedSongs = mutableListOf<Song>()
        val dedupKeys = LinkedHashSet<String>()
        var lastError: Exception? = null

        for (config in enabledConfigs) {
            val configResult = fetchAggregatedSongsByConfig(config)
            configResult.onSuccess { songs ->
                songs.forEach { song ->
                    val dedupKey = "${song.webDavConfigId}:${song.data}"
                    if (dedupKeys.add(dedupKey)) {
                        mergedSongs.add(song)
                    }
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to fetch aggregate songs, configId=${config.id}", throwable)
                lastError = if (throwable is Exception) throwable else Exception(throwable)
            }
        }

        if (mergedSongs.isNotEmpty()) {
            return@withContext Result.success(mergedSongs)
        }

        return@withContext if (lastError != null) {
            Result.failure(lastError!!)
        } else {
            Result.success(emptyList())
        }
    }

    // ---- Backend scan management ----

    override suspend fun triggerBackendScan(configId: Long, type: String): Result<Long> =
        withContext(Dispatchers.IO) {
            try {
                val config = serverDao.getConfigById(configId)?.toModel()
                    ?: return@withContext Result.failure(Exception("Config not found"))
                val apiService = apiServiceProvider(config)
                val response = apiService.createScanTask(ScanTaskRequest(taskType = type, configId = configId))
                if (response.isSuccess && response.data != null) {
                    Result.success(response.data.taskId)
                } else {
                    Result.failure(Exception("Scan task creation failed: ${response.message}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger backend scan", e)
                Result.failure(e)
            }
        }

    override suspend fun getScanTaskStatus(configId: Long, taskId: Long): Result<ServerSyncProgress> =
        withContext(Dispatchers.IO) {
            try {
                val config = serverDao.getConfigById(configId)?.toModel()
                    ?: return@withContext Result.failure(Exception("Config not found"))
                val apiService = apiServiceProvider(config)
                val response = apiService.getScanTaskStatus(taskId)
                if (response.isSuccess && response.data != null) {
                    val task = response.data
                    Result.success(
                        ServerSyncProgress(
                            status = task.status,
                            processedDirectories = task.processedDirectories,
                            totalDirectories = task.totalDirectories,
                            progressPct = task.progressPct,
                            message = task.errorSummary
                        )
                    )
                } else {
                    Result.failure(Exception("Failed to get scan status: ${response.message}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get scan task status", e)
                Result.failure(e)
            }
        }

    // ---- Playlist & favorite management ----

    override suspend fun listPlaylists(configId: Long): Result<List<ServerPlaylist>> =
        withContext(Dispatchers.IO) {
            val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = apiService.getPlaylists()
                if (!response.isSuccess || response.data == null) {
                    return@withContext Result.failure(Exception("Failed to list playlists: ${response.message}"))
                }
                Result.success(
                    response.data.map {
                        ServerPlaylist(
                            id = it.id,
                            name = it.name,
                            playlistType = it.playlistType,
                            systemCode = it.systemCode
                        )
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list playlists", e)
                Result.failure(e)
            }
        }

    override suspend fun createPlaylist(configId: Long, name: String): Result<ServerPlaylist> =
        withContext(Dispatchers.IO) {
            val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = apiService.createPlaylist(CreatePlaylistApiRequest(name.trim()))
                if (!response.isSuccess || response.data == null) {
                    return@withContext Result.failure(Exception("Failed to create playlist: ${response.message}"))
                }
                val created = response.data
                Result.success(
                    ServerPlaylist(
                        id = created.id,
                        name = created.name,
                        playlistType = created.playlistType,
                        systemCode = created.systemCode
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create playlist", e)
                Result.failure(e)
            }
        }

    override suspend fun addTracksToPlaylist(
        configId: Long,
        playlistId: Long,
        trackIds: List<Long>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
        try {
            val normalized = trackIds.filter { it > 0 }.distinct()
            if (normalized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("trackIds is empty"))
            }
            val response = apiService.addTracksToPlaylist(playlistId, TrackIdsApiRequest(normalized))
            if (!response.isSuccess || response.data == null) {
                return@withContext Result.failure(Exception("Failed to add tracks to playlist: ${response.message}"))
            }
            Result.success(response.data.addedCount ?: 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add tracks to playlist", e)
            Result.failure(e)
        }
    }

    override suspend fun removeTracksFromPlaylist(
        configId: Long,
        playlistId: Long,
        trackIds: List<Long>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
        try {
            val normalized = trackIds.filter { it > 0 }.distinct()
            if (normalized.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("trackIds is empty"))
            }
            val response = apiService.removeTracksFromPlaylist(playlistId, TrackIdsApiRequest(normalized))
            if (!response.isSuccess || response.data == null) {
                return@withContext Result.failure(Exception("Failed to remove tracks from playlist: ${response.message}"))
            }
            Result.success(response.data.affectedCount ?: 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove tracks from playlist", e)
            Result.failure(e)
        }
    }

    override suspend fun getFavoriteStatus(configId: Long, serverTrackId: Long): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = apiService.getFavoriteStatus(serverTrackId)
                if (!response.isSuccess || response.data == null) {
                    return@withContext Result.failure(Exception("Failed to get favorite status: ${response.message}"))
                }
                Result.success(response.data.favorite)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get favorite status", e)
                Result.failure(e)
            }
        }

    override suspend fun setFavorite(configId: Long, serverTrackId: Long, favorite: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val apiService = resolveApiService(configId).getOrElse { return@withContext Result.failure(it) }
            try {
                val response = if (favorite) apiService.favoriteTrack(serverTrackId) else apiService.unfavoriteTrack(serverTrackId)
                if (!response.isSuccess || response.data == null) {
                    return@withContext Result.failure(Exception("Failed to set favorite status: ${response.message}"))
                }
                Result.success(response.data.favorite)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set favorite status", e)
                Result.failure(e)
            }
        }

    // ---- Song queries ----

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val configs = serverDao.getAllConfigs().associateBy { it.id }
        serverDao.getAllSongs().mapNotNull { entity ->
            val config = configs[entity.configId] ?: return@mapNotNull null
            entity.toModel(config)
        }
    }

    override suspend fun getSongsByConfig(configId: Long): List<Song> = withContext(Dispatchers.IO) {
        val config = serverDao.getConfigById(configId) ?: return@withContext emptyList()
        serverDao.getSongsByConfig(configId).map { it.toModel(config) }
    }

    override suspend fun getSongById(songId: Long): Song? = withContext(Dispatchers.IO) {
        val entity = serverDao.getSongById(songId) ?: return@withContext null
        val config = serverDao.getConfigById(entity.configId) ?: return@withContext null
        entity.toModel(config)
    }

    override suspend fun deleteSongsByConfig(configId: Long) = withContext(Dispatchers.IO) {
        serverDao.deleteSongsByConfig(configId)
    }

    override suspend fun deleteSongsByIds(songIds: List<Long>) = withContext(Dispatchers.IO) {
        serverDao.deleteSongsByIds(songIds)
    }

    // ---- Lyrics ----

    override suspend fun getLyrics(serverTrackId: Long, configId: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                val config = serverDao.getConfigById(configId)?.toModel() ?: return@withContext null
                val apiService = apiServiceProvider(config)
                val response = apiService.getLyrics(serverTrackId)
                if (response.isSuccess) response.data else null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch lyrics for track $serverTrackId", e)
                null
            }
        }

    // ---- Conversion helpers ----

    private suspend fun fetchAggregatedSongsByConfig(config: ServerConfigEntity): Result<List<Song>> {
        return try {
            val apiService = apiServiceProvider(config.toModel())
            val collectedSongs = mutableListOf<Song>()
            var pageNo = 1
            var useAggregatedEndpoint = true

            while (true) {
                val response = try {
                    if (useAggregatedEndpoint) {
                        apiService.getAggregatedTracks(
                            pageNo = pageNo,
                            pageSize = SYNC_PAGE_SIZE,
                            sortBy = "updated_at",
                            sortOrder = "DESC"
                        )
                    } else {
                        apiService.getTracks(pageNo = pageNo, pageSize = SYNC_PAGE_SIZE)
                    }
                } catch (e: Exception) {
                    if (useAggregatedEndpoint && pageNo == 1) {
                        Log.w(TAG, "Aggregated endpoint unavailable for configId=${config.id}, fallback to /api/v1/tracks", e)
                        useAggregatedEndpoint = false
                        apiService.getTracks(pageNo = pageNo, pageSize = SYNC_PAGE_SIZE)
                    } else {
                        throw e
                    }
                }

                if (!response.isSuccess || response.data == null) {
                    if (pageNo == 1) {
                        return Result.failure(Exception("API error: ${response.message}"))
                    }
                    break
                }

                val records = response.data.records
                if (records.isEmpty()) {
                    break
                }

                records.forEach { track ->
                    collectedSongs.add(track.toEntity(config.id).toModel(config))
                }

                if (records.size < SYNC_PAGE_SIZE) {
                    break
                }
                pageNo++
            }

            Result.success(collectedSongs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ServerConfigEntity.toModel(): ServerConfig {
        return ServerConfig(
            id = id,
            name = name,
            serverUrl = serverUrl,
            apiToken = apiToken,
            isEnabled = isEnabled,
            lastSynced = lastSynced
        )
    }

    private fun ServerSongEntity.toModel(config: ServerConfigEntity): Song {
        val serverUrl = config.serverUrl.trimEnd('/')
        val streamUrl = "$serverUrl/api/v1/tracks/$serverTrackId/stream"
        val coverUrl = "$serverUrl/api/v1/tracks/$serverTrackId/cover"
        // Note: Glide handles 404 gracefully by showing default album art

        val normalizedArtist = artistName.ifBlank { "Unknown Artist" }
        val normalizedAlbum = albumName.ifBlank { "Unknown Album" }

        val synthesizedArtistId = synthesizeEntityId("artist", configId, normalizedArtist)

        // When album metadata is missing, use source path to distinguish albums
        val albumIdentity = if (normalizedAlbum.equals("Unknown Album", ignoreCase = true)) {
            "${normalizedAlbum}|${parentFolderPath(sourcePath)}|$normalizedArtist"
        } else {
            "${normalizedAlbum}|$normalizedArtist"
        }
        val synthesizedAlbumId = synthesizeEntityId("album", configId, albumIdentity)

        return Song(
            id = synthesizeEntityId("song", configId, serverTrackId.toString()),
            title = title,
            trackNumber = trackNumber,
            year = year,
            duration = duration,
            data = streamUrl,
            dateModified = 0,
            albumId = synthesizedAlbumId,
            albumName = albumName,
            artistId = synthesizedArtistId,
            artistName = artistName,
            composer = null,
            albumArtist = albumArtist,
            sourceType = SourceType.SERVER,
            remotePath = streamUrl,
            webDavConfigId = configId,
            webDavAlbumArtPath = coverUrl
        )
    }

    /**
     * Convert a TrackDetailResponse from the API to a ServerSongEntity for local caching.
     */
    private fun TrackDetailResponse.toEntity(configId: Long): ServerSongEntity {
        return ServerSongEntity(
            configId = configId,
            serverTrackId = id,
            title = title,
            artistName = artist,
            albumName = album,
            albumArtist = albumArtist,
            duration = ((durationSec ?: 0.0) * 1000).toLong(),
            trackNumber = trackNo ?: 0,
            discNumber = discNo ?: 0,
            year = year ?: 0,
            genre = genre,
            hasCover = (hasCover ?: 0) == 1,
            hasLyric = (hasLyric ?: 0) == 1,
            sourcePath = sourcePath,
            mimeType = mimeType ?: "audio/mpeg",
            sourceSize = sourceSize ?: 0,
            updatedAt = updatedAt ?: ""
        )
    }

    /**
     * Fallback conversion from lightweight TrackResponse (when detail fetch fails).
     */
    private fun code.name.monkey.retromusic.network.TrackResponse.toEntity(configId: Long): ServerSongEntity {
        return ServerSongEntity(
            configId = configId,
            serverTrackId = id,
            title = title,
            artistName = artist,
            albumName = album,
            duration = ((durationSec ?: 0.0) * 1000).toLong(),
            hasLyric = (hasLyric ?: 0) == 1,
            sourcePath = sourcePath
        )
    }

    /**
     * Build deterministic negative IDs to avoid collision with MediaStore positive IDs.
     */
    private fun synthesizeEntityId(kind: String, configId: Long, identity: String): Long {
        val key = "server|$kind|$configId|${identity.trim().lowercase()}"
        val hash = key.hashCode().toLong()
        val positive = if (hash == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(hash)
        return -positive.coerceAtLeast(1L)
    }

    /**
     * Extract parent folder path from a relative file path.
     */
    private fun parentFolderPath(path: String): String {
        val cleaned = path.trimEnd('/')
        val lastSlash = cleaned.lastIndexOf('/')
        return if (lastSlash > 0) cleaned.substring(0, lastSlash) else ""
    }

    private suspend fun resolveApiService(configId: Long): Result<MusicApiService> {
        val config = serverDao.getConfigById(configId)?.toModel()
            ?: return Result.failure(IllegalArgumentException("Config not found: $configId"))
        return try {
            Result.success(apiServiceProvider(config))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create API service", e)
            Result.failure(e)
        }
    }
}
