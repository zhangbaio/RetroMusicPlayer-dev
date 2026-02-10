package code.name.monkey.retromusic.repository

import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.model.Song

/**
 * Progress info for server sync operations.
 */
data class ServerSyncProgress(
    val status: String,     // PENDING, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELED
    val processedDirectories: Int?,
    val totalDirectories: Int?,
    val progressPct: Int?,
    val message: String?
)

/**
 * Repository interface for music server API operations.
 * Replaces the WebDAVRepository interface.
 */
interface ServerRepository {

    // ---- Config management ----
    suspend fun getAllConfigs(): List<ServerConfig>
    suspend fun getEnabledConfigs(): List<ServerConfig>
    suspend fun getConfigById(configId: Long): ServerConfig?
    suspend fun saveConfig(config: ServerConfig): Long
    suspend fun deleteConfig(config: ServerConfig)
    suspend fun testConnection(config: ServerConfig): Result<Boolean>

    // ---- Sync: fetch tracks from API and cache locally ----
    suspend fun syncSongs(
        configId: Long,
        onProgress: (suspend (syncedCount: Int, totalCount: Long) -> Unit)? = null
    ): Result<Int>

    // ---- Backend scan management ----
    suspend fun triggerBackendScan(configId: Long, type: String = "INCREMENTAL"): Result<Long>
    suspend fun getScanTaskStatus(configId: Long, taskId: Long): Result<ServerSyncProgress>

    // ---- Song queries (from local cache) ----
    suspend fun getAllSongs(): List<Song>
    suspend fun getSongsByConfig(configId: Long): List<Song>
    suspend fun getSongById(songId: Long): Song?
    suspend fun deleteSongsByConfig(configId: Long)
    suspend fun deleteSongsByIds(songIds: List<Long>)

    // ---- Lyrics ----
    suspend fun getLyrics(serverTrackId: Long, configId: Long): String?
}
