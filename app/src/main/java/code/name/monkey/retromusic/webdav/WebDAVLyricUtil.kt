package code.name.monkey.retromusic.webdav

import android.net.Uri
import android.util.Base64
import android.util.Log
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.lyrics.AbsSynchronizedLyrics
import code.name.monkey.retromusic.repository.WebDAVRepository
import code.name.monkey.retromusic.util.LyricUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Utility for loading lyrics from WebDAV songs.
 *
 * Loading strategy:
 * 1. Check local .lrc file by title-artist (handled by LyricUtil, instant)
 * 2. Download .lrc sidecar file from WebDAV server (small, fast)
 * 3. Download audio file to temp, extract embedded lyrics (large, cached)
 *
 * After step 2 or 3, results are saved as local .lrc files for future cache.
 */
object WebDAVLyricUtil : KoinComponent {

    private const val TAG = "WebDAVLyricUtil"
    private const val MAX_AUDIO_DOWNLOAD_SIZE = 100L * 1024 * 1024 // 100MB limit
    private val SIDECAR_EXTENSIONS = listOf("lrc", "LRC", "txt", "TXT")
    private val COMMON_LYRIC_FILE_NAMES = listOf("lyrics.lrc", "lyrics.LRC", "lyrics.txt", "lyrics.TXT")

    private val webDAVRepository: WebDAVRepository by inject()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isWebDAVSong(song: Song): Boolean {
        return song.sourceType == SourceType.WEBDAV
    }

    /**
     * Try to load synced lyrics for a WebDAV song.
     * Returns the lyrics string, or null if not found.
     * Results are cached as local .lrc files.
     */
    suspend fun getSyncedLyrics(song: Song): String? {
        if (!isWebDAVSong(song)) return null

        // 1. Already cached locally by title-artist?
        val localLrc = LyricUtil.getSyncedLyricsFile(song)
        if (localLrc != null) {
            return LyricUtil.getStringFromLrc(localLrc).ifEmpty { null }
        }

        val configId = song.webDavConfigId ?: return null
        val config = webDAVRepository.getConfigById(configId) ?: return null
        val password = WebDAVCryptoUtil.decryptPassword(config.password, config.id)
        val credentials = Base64.encodeToString(
            "${config.username}:$password".toByteArray(), Base64.NO_WRAP
        )

        // 2. Try downloading sidecar lyrics from WebDAV
        val remoteLrc = downloadSidecarLyrics(song.data, credentials)
        if (!remoteLrc.isNullOrBlank() && AbsSynchronizedLyrics.isSynchronized(remoteLrc)) {
            // Cache locally for future use
            LyricUtil.writeLrcToLoc(song.title, song.artistName, remoteLrc)
            return remoteLrc
        }

        // 3. Download audio file and extract embedded lyrics
        val embeddedLyrics = downloadAndExtractLyrics(song.data, credentials)
        if (!embeddedLyrics.isNullOrBlank()) {
            // Cache as local .lrc for future use
            LyricUtil.writeLrcToLoc(song.title, song.artistName, embeddedLyrics)
            return embeddedLyrics
        }

        return null
    }

    /**
     * Try to load normal (non-synced) lyrics for a WebDAV song.
     */
    suspend fun getNormalLyrics(song: Song): String? {
        if (!isWebDAVSong(song)) return null

        val configId = song.webDavConfigId ?: return null
        val config = webDAVRepository.getConfigById(configId) ?: return null
        val password = WebDAVCryptoUtil.decryptPassword(config.password, config.id)
        val credentials = Base64.encodeToString(
            "${config.username}:$password".toByteArray(), Base64.NO_WRAP
        )

        val sidecarLyrics = downloadSidecarLyrics(song.data, credentials)
        if (!sidecarLyrics.isNullOrBlank()) {
            return sidecarLyrics
        }

        return downloadAndExtractAllLyrics(song.data, credentials)
    }

    private fun downloadSidecarLyrics(audioUrl: String, credentials: String): String? {
        val sidecarUrls = buildSidecarLyricUrls(audioUrl)
        for (sidecarUrl in sidecarUrls) {
            val sidecarLyrics = downloadText(sidecarUrl, credentials)
            if (!sidecarLyrics.isNullOrBlank()) {
                return sidecarLyrics
            }
        }
        return null
    }

    private fun buildSidecarLyricUrls(audioUrl: String): List<String> {
        val uri = Uri.parse(audioUrl)
        val audioPath = uri.path ?: return emptyList()
        val parentPath = audioPath.substringBeforeLast('/', "")
        val audioFileName = audioPath.substringAfterLast('/')
        val baseName = audioFileName.substringBeforeLast('.', audioFileName)
        val lyricNames = SIDECAR_EXTENSIONS.map { "$baseName.$it" } + COMMON_LYRIC_FILE_NAMES

        return lyricNames.distinct().mapNotNull { lyricFileName ->
            siblingUrl(uri, parentPath, lyricFileName)
        }
    }

    private fun siblingUrl(uri: Uri, parentPath: String, fileName: String): String? {
        return runCatching {
            val targetPath = if (parentPath.isBlank()) {
                "/$fileName"
            } else {
                "${parentPath.trimEnd('/')}/$fileName"
            }
            uri.buildUpon().path(targetPath).build().toString()
        }.getOrNull()
    }

    private fun downloadText(url: String, credentials: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Basic $credentials")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to download .lrc from $url: ${e.message}")
            null
        }
    }

    private fun downloadAndExtractLyrics(audioUrl: String, credentials: String): String? {
        return extractFromDownloadedAudio(audioUrl, credentials) { tag ->
            val lyrics = tag.getFirst(FieldKey.LYRICS)
            if (code.name.monkey.retromusic.model.lyrics.AbsSynchronizedLyrics.isSynchronized(lyrics)) {
                lyrics
            } else {
                null
            }
        }
    }

    private fun downloadAndExtractAllLyrics(audioUrl: String, credentials: String): String? {
        return extractFromDownloadedAudio(audioUrl, credentials) { tag ->
            val lyrics = tag.getFirst(FieldKey.LYRICS)
            if (!lyrics.isNullOrBlank()) lyrics else null
        }
    }

    private fun extractFromDownloadedAudio(
        audioUrl: String,
        credentials: String,
        extractor: (org.jaudiotagger.tag.Tag) -> String?
    ): String? {
        var tempFile: File? = null
        try {
            val request = Request.Builder()
                .url(audioUrl)
                .header("Authorization", "Basic $credentials")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val contentLength = response.body?.contentLength() ?: -1
            if (contentLength > MAX_AUDIO_DOWNLOAD_SIZE) {
                Log.d(TAG, "Audio file too large to download for lyrics: $contentLength bytes")
                return null
            }

            val extension = audioUrl.substringAfterLast(".", "tmp")
            tempFile = File.createTempFile("webdav_lyrics_", ".$extension")
            FileOutputStream(tempFile).use { fos ->
                response.body?.byteStream()?.copyTo(fos)
            }

            val audioFile = AudioFileIO.read(tempFile)
            return extractor(audioFile.tagOrCreateDefault)
        } catch (e: Exception) {
            Log.d(TAG, "Failed to extract lyrics from $audioUrl: ${e.message}")
            return null
        } finally {
            tempFile?.delete()
        }
    }
}
