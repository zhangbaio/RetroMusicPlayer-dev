package code.name.monkey.retromusic.webdav

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.repository.WebDAVRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object WebDAVSongDownloadUtil {

    private const val DOWNLOAD_SUB_DIRECTORY = "RetroMusic/WebDAV"
    private const val MAX_REDIRECTS = 8

    suspend fun downloadSong(
        context: Context,
        song: Song,
        repository: WebDAVRepository
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val songUrl = resolveSongUrl(song)
                ?: throw IllegalArgumentException("Not a WebDAV song")
            val config = resolveConfig(song, songUrl, repository)
                ?: throw IllegalStateException("WebDAV config not found")
            val plainPassword = resolvePlainPassword(config.password, config.id)
            val fileName = buildFileName(song, songUrl)
            val authHeader = buildAuthHeader(config.username, plainPassword)
            val authScopeUrl = runCatching { URL(config.serverUrl) }.getOrNull()
            val (resolvedUrl, connection) = openDownloadConnection(
                originalUrl = songUrl,
                authHeader = authHeader,
                authScope = authScopeUrl
            )

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP $responseCode")
                }

                val mimeType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    .orEmpty()
                    .ifBlank { guessMimeType(resolvedUrl, fileName) }

                connection.inputStream.use { input ->
                    saveToLocal(context, input, fileName, mimeType)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openDownloadConnection(
        originalUrl: String,
        authHeader: String,
        authScope: URL?
    ): Pair<String, HttpURLConnection> {
        var currentUrl = originalUrl
        var redirects = 0

        while (true) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 120_000
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("Accept", "*/*")
                if (shouldAttachAuth(url, authScope)) {
                    setRequestProperty("Authorization", authHeader)
                }
            }

            val code = connection.responseCode
            if (code in 200..299) {
                return currentUrl to connection
            }

            if (code !in REDIRECT_CODES) {
                return currentUrl to connection
            }

            val location = connection.getHeaderField("Location")
            if (location.isNullOrBlank()) {
                return currentUrl to connection
            }

            val nextUrl = URL(url, location).toString()
            connection.inputStream?.closeQuietly()
            connection.errorStream?.closeQuietly()
            connection.disconnect()

            redirects += 1
            if (redirects > MAX_REDIRECTS) {
                throw IllegalStateException("Too many redirects")
            }
            currentUrl = nextUrl
        }
    }

    private fun shouldAttachAuth(requestUrl: URL, authScope: URL?): Boolean {
        if (authScope == null) return true
        return requestUrl.protocol.equals(authScope.protocol, ignoreCase = true) &&
            requestUrl.host.equals(authScope.host, ignoreCase = true) &&
            resolvePort(requestUrl) == resolvePort(authScope)
    }

    private fun resolvePort(url: URL): Int {
        return if (url.port != -1) url.port else url.defaultPort
    }

    private fun InputStream.closeQuietly() {
        runCatching { close() }
    }

    private fun resolveSongUrl(song: Song): String? {
        val remote = song.remotePath?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (!remote.isNullOrBlank()) {
            return remote
        }
        val data = song.data.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (!data.isNullOrBlank()) {
            return data
        }
        return if (song.sourceType == SourceType.WEBDAV) song.remotePath else null
    }

    private suspend fun resolveConfig(
        song: Song,
        songUrl: String,
        repository: WebDAVRepository
    ) = song.webDavConfigId?.let { repository.getConfigById(it) }
        ?: repository.getEnabledConfigs().firstOrNull { config ->
            songUrl.startsWith(config.serverUrl.trimEnd('/'), ignoreCase = true)
        }

    private fun resolvePlainPassword(rawPassword: String, configId: Long): String {
        if (!rawPassword.startsWith("encrypted://")) {
            return rawPassword
        }
        return WebDAVCryptoUtil.decryptPassword(rawPassword, configId)
    }

    private fun buildAuthHeader(username: String, password: String): String {
        val raw = "$username:$password"
        val encoded = android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun buildFileName(song: Song, songUrl: String): String {
        val urlSegment = Uri.parse(songUrl).lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            .orEmpty()
        val decoded = runCatching { URLDecoder.decode(urlSegment, "UTF-8") }.getOrNull().orEmpty()
        val baseName = (if (decoded.isNotBlank()) decoded else song.title)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "webdav_song" }

        if (baseName.contains('.')) {
            return baseName
        }
        val urlExt = songUrl.substringAfterLast('.', "")
            .substringBefore('?')
            .lowercase()
            .takeIf { it.isNotBlank() }
        return if (urlExt != null) "$baseName.$urlExt" else "$baseName.mp3"
    }

    private fun guessMimeType(songUrl: String, fileName: String): String {
        val extFromName = fileName.substringAfterLast('.', "").lowercase()
        val extFromUrl = songUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
        val ext = extFromName.ifBlank { extFromUrl }
        val mimeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mimeFromExt ?: "audio/mpeg"
    }

    private val REDIRECT_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308
    )

    private fun saveToLocal(
        context: Context,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, input, fileName, mimeType)
        } else {
            saveToPublicMusic(context, input, fileName, mimeType)
        }
    }

    private fun saveToMediaStore(
        context: Context,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_MUSIC}/$DOWNLOAD_SUB_DIRECTORY"
        val displayName = ensureUniqueDisplayName(resolver, collection, relativePath, fileName)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Failed to create destination")

        try {
            var copiedSize = 0L
            resolver.openOutputStream(uri)?.use { output ->
                copiedSize = copyStream(input, output)
            } ?: throw IllegalStateException("Failed to open destination stream")

            val completeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.SIZE, copiedSize)
            }
            resolver.update(uri, completeValues, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun saveToPublicMusic(
        context: Context,
        input: InputStream,
        fileName: String,
        mimeType: String
    ): Uri {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val dir = File(root, DOWNLOAD_SUB_DIRECTORY)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val target = uniqueFile(dir, fileName)
        FileOutputStream(target).use { output ->
            copyStream(input, output)
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null
        )
        return Uri.fromFile(target)
    }

    private fun copyStream(input: InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }

    private fun uniqueFile(dir: File, desiredName: String): File {
        var file = File(dir, desiredName)
        if (!file.exists()) {
            return file
        }
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (file.exists()) {
            val candidate = if (ext == null) "$base($index)" else "$base($index).$ext"
            file = File(dir, candidate)
            index += 1
        }
        return file
    }

    private fun ensureUniqueDisplayName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        desiredName: String
    ): String {
        var candidate = desiredName
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (displayNameExists(resolver, collection, relativePath, candidate)) {
            candidate = if (ext == null) "$base($index)" else "$base($index).$ext"
            index += 1
        }
        return candidate
    }

    private fun displayNameExists(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        displayName: String
    ): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(relativePath, displayName)
        resolver.query(collection, projection, selection, args, null).use { cursor ->
            return cursor != null && cursor.moveToFirst()
        }
    }
}
