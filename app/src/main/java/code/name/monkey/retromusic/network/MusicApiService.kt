package code.name.monkey.retromusic.network

import retrofit2.http.*

interface MusicApiService {
    @GET("/api/v1/tracks")
    suspend fun getTracks(
        @Query("pageNo") pageNo: Int = 1,
        @Query("pageSize") pageSize: Int = 200,
        @Query("keyword") keyword: String? = null,
        @Query("artist") artist: String? = null,
        @Query("album") album: String? = null,
        @Query("genre") genre: String? = null,
        @Query("year") year: Int? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortOrder") sortOrder: String? = null
    ): MusicApiResponse<PagedResult<TrackResponse>>

    @GET("/api/v1/tracks/search")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 100
    ): MusicApiResponse<List<TrackResponse>>

    @GET("/api/v1/tracks/{id}")
    suspend fun getTrack(
        @Path("id") id: Long
    ): MusicApiResponse<TrackDetailResponse>

    @GET("/api/v1/tracks/{id}/lyric")
    suspend fun getLyrics(
        @Path("id") id: Long
    ): MusicApiResponse<String>

    @GET("/api/v1/playlists")
    suspend fun getPlaylists(): MusicApiResponse<List<PlaylistApiResponse>>

    @POST("/api/v1/playlists")
    suspend fun createPlaylist(
        @Body request: CreatePlaylistApiRequest
    ): MusicApiResponse<PlaylistApiResponse>

    @POST("/api/v1/playlists/{id}/tracks")
    suspend fun addTracksToPlaylist(
        @Path("id") id: Long,
        @Body request: TrackIdsApiRequest
    ): MusicApiResponse<AddPlaylistTracksApiResponse>

    @POST("/api/v1/playlists/{id}/tracks/remove")
    suspend fun removeTracksFromPlaylist(
        @Path("id") id: Long,
        @Body request: TrackIdsApiRequest
    ): MusicApiResponse<PlaylistTrackOperationApiResponse>

    @GET("/api/v1/tracks/{id}/favorite")
    suspend fun getFavoriteStatus(@Path("id") id: Long): MusicApiResponse<FavoriteStatusApiResponse>

    @PUT("/api/v1/tracks/{id}/favorite")
    suspend fun favoriteTrack(@Path("id") id: Long): MusicApiResponse<FavoriteStatusApiResponse>

    @DELETE("/api/v1/tracks/{id}/favorite")
    suspend fun unfavoriteTrack(@Path("id") id: Long): MusicApiResponse<FavoriteStatusApiResponse>

    @POST("/api/v1/scan/tasks")
    suspend fun createScanTask(
        @Body request: ScanTaskRequest
    ): MusicApiResponse<ScanTaskResponse>

    @GET("/api/v1/scan/tasks/{id}")
    suspend fun getScanTaskStatus(
        @Path("id") id: Long
    ): MusicApiResponse<ScanTaskResponse>
}
