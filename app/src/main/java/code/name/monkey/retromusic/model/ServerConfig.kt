package code.name.monkey.retromusic.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Model representing a music server configuration.
 * Used in the UI and repository layers.
 */
@Parcelize
data class ServerConfig(
    val id: Long = 0L,
    val name: String,
    val serverUrl: String,
    val apiToken: String,
    val isEnabled: Boolean = true,
    val lastSynced: Long = 0L
) : Parcelable
