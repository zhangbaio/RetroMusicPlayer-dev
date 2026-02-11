package code.name.monkey.retromusic.network

import com.google.gson.annotations.SerializedName

/** Generic API response wrapper */
data class MusicApiResponse<T>(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: T?
) {
    val isSuccess: Boolean get() = code == "0"
}

/** Paginated result */
data class PagedResult<T>(
    @SerializedName("records") val records: List<T>,
    @SerializedName("total") val total: Long,
    @SerializedName("pageNo") val pageNo: Int,
    @SerializedName("pageSize") val pageSize: Int
)

/** Track list item (lightweight) */
data class TrackResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String,
    @SerializedName("sourcePath") val sourcePath: String,
    @SerializedName("durationSec") val durationSec: Double?,
    @SerializedName("hasLyric") val hasLyric: Int?
)

/** Track detail (full metadata) */
data class TrackDetailResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("sourceConfigId") val sourceConfigId: Long?,
    @SerializedName("sourcePath") val sourcePath: String,
    @SerializedName("sourceSize") val sourceSize: Long?,
    @SerializedName("mimeType") val mimeType: String?,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String,
    @SerializedName("albumArtist") val albumArtist: String?,
    @SerializedName("trackNo") val trackNo: Int?,
    @SerializedName("discNo") val discNo: Int?,
    @SerializedName("year") val year: Int?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("durationSec") val durationSec: Double?,
    @SerializedName("bitrate") val bitrate: Int?,
    @SerializedName("sampleRate") val sampleRate: Int?,
    @SerializedName("channels") val channels: Int?,
    @SerializedName("hasCover") val hasCover: Int?,
    @SerializedName("coverArtUrl") val coverArtUrl: String?,
    @SerializedName("hasLyric") val hasLyric: Int?,
    @SerializedName("lyricPath") val lyricPath: String?,
    @SerializedName("updatedAt") val updatedAt: String?
)

/** Scan task request */
data class ScanTaskRequest(
    @SerializedName("taskType") val taskType: String,
    @SerializedName("configId") val configId: Long? = null
)

/** Scan task response */
data class ScanTaskResponse(
    @SerializedName("taskId") val taskId: Long,
    @SerializedName("taskType") val taskType: String?,
    @SerializedName("status") val status: String,
    @SerializedName("configId") val configId: Long?,
    @SerializedName("totalFiles") val totalFiles: Int?,
    @SerializedName("audioFiles") val audioFiles: Int?,
    @SerializedName("addedCount") val addedCount: Int?,
    @SerializedName("updatedCount") val updatedCount: Int?,
    @SerializedName("deletedCount") val deletedCount: Int?,
    @SerializedName("failedCount") val failedCount: Int?,
    @SerializedName("processedDirectories") val processedDirectories: Int?,
    @SerializedName("totalDirectories") val totalDirectories: Int?,
    @SerializedName("progressPct") val progressPct: Int?,
    @SerializedName("errorSummary") val errorSummary: String?
)

/** Playlist summary */
data class PlaylistApiResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("playlistType") val playlistType: String?,
    @SerializedName("systemCode") val systemCode: String?,
    @SerializedName("sortNo") val sortNo: Int?,
    @SerializedName("trackCount") val trackCount: Int?
)

/** Create playlist request */
data class CreatePlaylistApiRequest(
    @SerializedName("name") val name: String
)

/** Generic track id list request */
data class TrackIdsApiRequest(
    @SerializedName("trackIds") val trackIds: List<Long>
)

/** Add tracks to playlist response */
data class AddPlaylistTracksApiResponse(
    @SerializedName("playlistId") val playlistId: Long?,
    @SerializedName("requestedCount") val requestedCount: Int?,
    @SerializedName("addedCount") val addedCount: Int?,
    @SerializedName("duplicateCount") val duplicateCount: Int?,
    @SerializedName("trackCount") val trackCount: Int?
)

/** Remove tracks from playlist response */
data class PlaylistTrackOperationApiResponse(
    @SerializedName("playlistId") val playlistId: Long?,
    @SerializedName("requestedCount") val requestedCount: Int?,
    @SerializedName("affectedCount") val affectedCount: Int?,
    @SerializedName("notFoundCount") val notFoundCount: Int?,
    @SerializedName("trackCount") val trackCount: Int?
)

/** Favorite status response */
data class FavoriteStatusApiResponse(
    @SerializedName("trackId") val trackId: Long?,
    @SerializedName("favorite") val favorite: Boolean
)
