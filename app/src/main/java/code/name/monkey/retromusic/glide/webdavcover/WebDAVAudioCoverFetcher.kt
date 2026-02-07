package code.name.monkey.retromusic.glide.webdavcover

import code.name.monkey.retromusic.repository.WebDAVRepository
import code.name.monkey.retromusic.webdav.WebDAVCryptoUtil
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.koin.java.KoinJavaComponent
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class WebDAVAudioCoverFetcher(
    private val model: WebDAVAudioCover
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
            val repository: WebDAVRepository = KoinJavaComponent.get(WebDAVRepository::class.java)
            val config = runBlocking { repository.getConfigById(model.configId) }
                ?: throw FileNotFoundException("WebDAV config not found: ${model.configId}")
            val password = WebDAVCryptoUtil.decryptPassword(config.password, config.id)
            val authHeader = Credentials.basic(config.username, password)

            fetchFirstAvailableCover(model.coverUrls, authHeader)
                ?: throw FileNotFoundException("No available cover in ${model.coverUrls}")
        }.onSuccess { inputStream ->
            stream = inputStream
            callback.onDataReady(inputStream)
        }.onFailure { error ->
            callback.onLoadFailed(error as? Exception ?: Exception(error))
        }
    }

    private fun fetchFirstAvailableCover(urls: List<String>, authHeader: String): InputStream? {
        for (url in urls) {
            var response: Response? = null
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .build()
                response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val body = response.body
                responseBody = body
                return body.byteStream()
            } catch (_: Exception) {
                response?.close()
            }
        }
        return null
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
