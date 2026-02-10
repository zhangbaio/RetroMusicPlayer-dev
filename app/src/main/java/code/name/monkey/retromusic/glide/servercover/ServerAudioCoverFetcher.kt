package code.name.monkey.retromusic.glide.servercover

import code.name.monkey.retromusic.repository.ServerRepository
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.koin.java.KoinJavaComponent
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Glide DataFetcher that loads cover art from the music server API
 * using Bearer Token authentication.
 */
class ServerAudioCoverFetcher(
    private val model: ServerAudioCover
) : DataFetcher<InputStream> {

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    private var stream: InputStream? = null
    private var responseBody: ResponseBody? = null

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        runCatching {
            val repository: ServerRepository = KoinJavaComponent.get(ServerRepository::class.java)
            val config = runBlocking { repository.getConfigById(model.configId) }
                ?: throw FileNotFoundException("Server config not found: ${model.configId}")

            val request = Request.Builder()
                .url(model.coverUrl)
                .header("Authorization", "Bearer ${config.apiToken}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw FileNotFoundException("Cover art not found at ${model.coverUrl}, status=${response.code}")
            }

            val body = response.body
            responseBody = body
            body.byteStream()
        }.onSuccess { inputStream ->
            stream = inputStream
            callback.onDataReady(inputStream)
        }.onFailure { error ->
            callback.onLoadFailed(error as? Exception ?: Exception(error))
        }
    }

    override fun cleanup() {
        try {
            stream?.close()
        } catch (_: IOException) {
        }
        responseBody?.close()
        stream = null
        responseBody = null
    }

    override fun cancel() {
        // No-op
    }

    override fun getDataClass(): Class<InputStream> = InputStream::class.java

    override fun getDataSource(): DataSource = DataSource.REMOTE
}
