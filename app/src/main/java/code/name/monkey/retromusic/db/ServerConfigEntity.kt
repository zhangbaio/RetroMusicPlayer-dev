package code.name.monkey.retromusic.db

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Entity representing a music server configuration
 */
@Parcelize
@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "server_url")
    val serverUrl: String,

    @ColumnInfo(name = "api_token")
    val apiToken: String,

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = 0L
) : Parcelable
