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

    @POST("/api/v1/scan/tasks")
    suspend fun createScanTask(
        @Body request: ScanTaskRequest
    ): MusicApiResponse<ScanTaskResponse>

    @GET("/api/v1/scan/tasks/{id}")
    suspend fun getScanTaskStatus(
        @Path("id") id: Long
    ): MusicApiResponse<ScanTaskResponse>
}
