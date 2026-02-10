package code.name.monkey.retromusic.glide.servercover

/**
 * Glide model for loading album cover art from the music server API.
 * The coverUrl points to /api/v1/tracks/{id}/cover on the server.
 */
data class ServerAudioCover(
    val configId: Long,
    val coverUrl: String
)
