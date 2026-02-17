package code.name.monkey.retromusic.network

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * ExoPlayer DataSource.Factory that adds Bearer Token authentication
 * for streaming from the music server API.
 *
 * Used for authenticated playback from server stream endpoints.
 */
@OptIn(UnstableApi::class)
class ServerDataSourceFactory(
    private val apiToken: String
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(
                mapOf("Authorization" to "Bearer $apiToken")
            )
            setConnectTimeoutMs(30_000)
            setReadTimeoutMs(30_000)
            setAllowCrossProtocolRedirects(true)
        }.createDataSource()
    }
}
