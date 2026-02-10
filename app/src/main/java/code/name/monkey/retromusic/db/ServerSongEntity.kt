package code.name.monkey.retromusic.db

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Entity representing a song fetched from the music server API.
 * Stores metadata locally as cache while streaming audio from the server.
 */
@Parcelize
@Entity(
    tableName = "server_songs",
    indices = [Index(value = ["config_id", "server_track_id"], unique = true)]
)
data class ServerSongEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "config_id")
    val configId: Long,

    @ColumnInfo(name = "server_track_id")
    val serverTrackId: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "album_name")
    val albumName: String,

    @ColumnInfo(name = "album_artist")
    val albumArtist: String? = null,

    @ColumnInfo(name = "duration")
    val duration: Long,  // in milliseconds

    @ColumnInfo(name = "track_number")
    val trackNumber: Int = 0,

    @ColumnInfo(name = "disc_number")
    val discNumber: Int = 0,

    @ColumnInfo(name = "year")
    val year: Int = 0,

    @ColumnInfo(name = "genre")
    val genre: String? = null,

    @ColumnInfo(name = "has_cover")
    val hasCover: Boolean = false,

    @ColumnInfo(name = "has_lyric")
    val hasLyric: Boolean = false,

    @ColumnInfo(name = "source_path")
    val sourcePath: String = "",

    @ColumnInfo(name = "mime_type")
    val mimeType: String = "audio/mpeg",

    @ColumnInfo(name = "source_size")
    val sourceSize: Long = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String = ""
) : Parcelable
