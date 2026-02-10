package code.name.monkey.retromusic.db

import androidx.room.*

@Dao
interface ServerDao {
    // ---- Config operations ----
    @Query("SELECT * FROM server_configs WHERE is_enabled = 1 ORDER BY name")
    suspend fun getEnabledConfigs(): List<ServerConfigEntity>

    @Query("SELECT * FROM server_configs ORDER BY name")
    suspend fun getAllConfigs(): List<ServerConfigEntity>

    @Query("SELECT * FROM server_configs WHERE id = :configId")
    suspend fun getConfigById(configId: Long): ServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ServerConfigEntity): Long

    @Update
    suspend fun updateConfig(config: ServerConfigEntity)

    @Query("DELETE FROM server_configs WHERE id = :configId")
    suspend fun deleteConfigById(configId: Long)

    // ---- Song operations ----
    @Query("SELECT * FROM server_songs WHERE config_id = :configId ORDER BY artist_name, album_name, track_number")
    suspend fun getSongsByConfig(configId: Long): List<ServerSongEntity>

    @Query("SELECT * FROM server_songs ORDER BY artist_name, album_name, track_number")
    suspend fun getAllSongs(): List<ServerSongEntity>

    @Query("SELECT * FROM server_songs WHERE id = :songId")
    suspend fun getSongById(songId: Long): ServerSongEntity?

    @Query("SELECT * FROM server_songs WHERE server_track_id = :trackId AND config_id = :configId")
    suspend fun getSongByTrackId(trackId: Long, configId: Long): ServerSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<ServerSongEntity>)

    @Query("DELETE FROM server_songs WHERE config_id = :configId")
    suspend fun deleteSongsByConfig(configId: Long)

    @Query("DELETE FROM server_songs WHERE id IN (:songIds)")
    suspend fun deleteSongsByIds(songIds: List<Long>)

    @Query("SELECT COUNT(*) FROM server_songs WHERE config_id = :configId")
    suspend fun getSongCountByConfig(configId: Long): Int

    @Query("SELECT COUNT(*) FROM server_songs")
    suspend fun getTotalSongCount(): Int

    @Query("DELETE FROM server_songs WHERE config_id = :configId AND server_track_id NOT IN (:keepTrackIds)")
    suspend fun deleteRemovedSongs(configId: Long, keepTrackIds: List<Long>)
}
