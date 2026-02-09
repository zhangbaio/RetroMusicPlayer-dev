package code.name.monkey.retromusic.webdav

/**
 * Parses song title/artist metadata from WebDAV file paths and names.
 */
class WebDAVMetadataParser(audioFiles: List<WebDAVFile>) {

    data class ParsedMetadata(
        val title: String,
        val artistName: String,
        val albumName: String,
    )

    private enum class DirectoryPattern {
        ARTIST_FIRST,
        ARTIST_LAST
    }

    private data class DirectoryVotes(
        var artistFirst: Int = 0,
        var artistLast: Int = 0
    )

    private val directoryPatterns: Map<String, DirectoryPattern> = buildDirectoryPatterns(audioFiles)
    private val tokenFrequencyByDirectory: Map<String, Map<String, Int>> =
        buildTokenFrequencyByDirectory(audioFiles)

    fun parse(file: WebDAVFile): ParsedMetadata {
        val baseName = file.name.substringBeforeLast('.', file.name)
        val fallbackTitle = cleanSegment(baseName)
        val segments = splitSegments(baseName)
        val folderArtistCandidates = folderArtistCandidates(file.path)

        var resolvedTitle = fallbackTitle
        var resolvedArtist = ""

        if (segments.size == 2) {
            val first = segments[0]
            val second = segments[1]
            val firstNormalized = normalize(first)
            val secondNormalized = normalize(second)
            val folderKeys = folderArtistCandidates.map(::normalize).toSet()
            val firstMatchedFolder = firstNormalized.isNotEmpty() && firstNormalized in folderKeys
            val secondMatchedFolder = secondNormalized.isNotEmpty() && secondNormalized in folderKeys

            if (firstMatchedFolder.xor(secondMatchedFolder)) {
                if (firstMatchedFolder) {
                    resolvedTitle = second
                    resolvedArtist = first
                } else {
                    resolvedTitle = first
                    resolvedArtist = second
                }
                val album = resolveAlbumName(file.path, resolvedArtist)
                return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
            }

            when (directoryPatterns[parentFolderPath(file.path)]) {
                DirectoryPattern.ARTIST_FIRST -> {
                    resolvedTitle = second
                    resolvedArtist = first
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }

                DirectoryPattern.ARTIST_LAST -> {
                    resolvedTitle = first
                    resolvedArtist = second
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }

                null -> Unit
            }

            when (inferArtistByTokenFrequency(file.path, first, second)) {
                first -> {
                    resolvedTitle = second
                    resolvedArtist = first
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }
                second -> {
                    resolvedTitle = first
                    resolvedArtist = second
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }
                null -> Unit
            }

            when (inferArtistByFolderAlbumPattern(file.path, first, second)) {
                first -> {
                    resolvedTitle = second
                    resolvedArtist = first
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }
                second -> {
                    resolvedTitle = first
                    resolvedArtist = second
                    val album = resolveAlbumName(file.path, resolvedArtist)
                    return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
                }
                null -> Unit
            }
        }

        val folderArtist = folderArtistCandidates.firstOrNull()
        if (!folderArtist.isNullOrBlank()) {
            resolvedTitle = fallbackTitle
            resolvedArtist = folderArtist
            val album = resolveAlbumName(file.path, resolvedArtist)
            return ParsedMetadata(title = resolvedTitle, artistName = resolvedArtist, albumName = album)
        }

        val fallbackAlbum = resolveAlbumName(file.path, artistName = "")
        return ParsedMetadata(title = fallbackTitle, artistName = "", albumName = fallbackAlbum)
    }

    private fun buildDirectoryPatterns(audioFiles: List<WebDAVFile>): Map<String, DirectoryPattern> {
        val votesByDirectory = mutableMapOf<String, DirectoryVotes>()
        audioFiles.forEach { file ->
            val baseName = file.name.substringBeforeLast('.', file.name)
            val segments = splitSegments(baseName)
            if (segments.size != 2) {
                return@forEach
            }
            val folderArtistCandidates = folderArtistCandidates(file.path)
            if (folderArtistCandidates.isEmpty()) {
                return@forEach
            }

            val firstNormalized = normalize(segments[0])
            val secondNormalized = normalize(segments[1])
            val folderKeys = folderArtistCandidates.map(::normalize).toSet()
            val firstMatchedFolder = firstNormalized.isNotEmpty() && firstNormalized in folderKeys
            val secondMatchedFolder = secondNormalized.isNotEmpty() && secondNormalized in folderKeys
            if (!firstMatchedFolder.xor(secondMatchedFolder)) {
                return@forEach
            }

            val directory = parentFolderPath(file.path)
            val votes = votesByDirectory.getOrPut(directory) { DirectoryVotes() }
            if (firstMatchedFolder) {
                votes.artistFirst += 1
            } else {
                votes.artistLast += 1
            }
        }

        return votesByDirectory.mapNotNull { (directory, votes) ->
            when {
                votes.artistFirst > votes.artistLast ->
                    directory to DirectoryPattern.ARTIST_FIRST

                votes.artistLast > votes.artistFirst ->
                    directory to DirectoryPattern.ARTIST_LAST

                else -> null
            }
        }.toMap()
    }

    private fun buildTokenFrequencyByDirectory(audioFiles: List<WebDAVFile>): Map<String, Map<String, Int>> {
        val frequencies = mutableMapOf<String, MutableMap<String, Int>>()
        audioFiles.forEach { file ->
            val baseName = file.name.substringBeforeLast('.', file.name)
            val segments = splitSegments(baseName)
            if (segments.size != 2) {
                return@forEach
            }
            val directory = parentFolderPath(file.path)
            val tokens = frequencies.getOrPut(directory) { mutableMapOf() }
            segments.forEach { segment ->
                val key = normalize(segment)
                if (key.isBlank()) return@forEach
                tokens[key] = (tokens[key] ?: 0) + 1
            }
        }
        return frequencies
    }

    private fun inferArtistByTokenFrequency(path: String, first: String, second: String): String? {
        val directory = parentFolderPath(path)
        val frequencies = tokenFrequencyByDirectory[directory] ?: return null
        val firstKey = normalize(first)
        val secondKey = normalize(second)
        if (firstKey.isBlank() || secondKey.isBlank()) return null
        val firstCount = frequencies[firstKey] ?: 0
        val secondCount = frequencies[secondKey] ?: 0
        return when {
            firstCount >= TOKEN_FREQUENCY_THRESHOLD && firstCount > secondCount -> first
            secondCount >= TOKEN_FREQUENCY_THRESHOLD && secondCount > firstCount -> second
            else -> null
        }
    }

    private fun inferArtistByFolderAlbumPattern(path: String, first: String, second: String): String? {
        val firstKey = normalize(first)
        val secondKey = normalize(second)
        if (firstKey.isBlank() || secondKey.isBlank()) return null

        folderAlbumCandidates(path).forEach { candidate ->
            val segments = splitSegments(candidate)
            if (segments.size != 2) return@forEach
            val left = normalize(segments[0])
            val right = normalize(segments[1])
            if (firstKey == left || firstKey == right) {
                return first
            }
            if (secondKey == left || secondKey == right) {
                return second
            }
        }
        return null
    }

    private fun splitSegments(fileBaseName: String): List<String> {
        return fileBaseName
            .split(SEGMENT_SEPARATOR_REGEX)
            .map(::cleanSegment)
            .filter { it.isNotEmpty() }
    }

    private fun folderArtistCandidates(path: String): List<String> {
        val parent = folderName(parentFolderPath(path))
        val grandParent = folderName(parentFolderPath(parentFolderPath(path)))
        return listOf(parent, grandParent)
            .map(::cleanSegment)
            .filter(::looksLikeArtistCandidate)
            .distinct()
    }

    private fun folderAlbumCandidates(path: String): List<String> {
        val parent = folderName(parentFolderPath(path))
        val grandParent = folderName(parentFolderPath(parentFolderPath(path)))
        return listOf(parent, grandParent)
            .map(::cleanSegment)
            .filter(::looksLikeAlbumCandidate)
            .distinct()
    }

    private fun resolveAlbumName(path: String, artistName: String): String {
        val normalizedArtist = normalize(artistName)
        val candidates = folderAlbumCandidates(path)
        candidates.forEach { candidate ->
            val segments = splitSegments(candidate)
            if (segments.size == 2) {
                val first = segments[0]
                val second = segments[1]
                val firstNormalized = normalize(first)
                val secondNormalized = normalize(second)
                if (normalizedArtist.isNotBlank()) {
                    if (firstNormalized == normalizedArtist && looksLikeAlbumCandidate(second)) {
                        return second
                    }
                    if (secondNormalized == normalizedArtist && looksLikeAlbumCandidate(first)) {
                        return first
                    }
                }
            }
            if (normalizedArtist.isNotBlank() && normalize(candidate) == normalizedArtist) {
                return@forEach
            }
            return candidate
        }
        return ""
    }

    private fun folderName(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfterLast('/', "")
    }

    private fun cleanSegment(value: String): String {
        return value
            .replace('_', ' ')
            .replace('.', ' ')
            .trim()
            .replace(MULTI_SPACE_REGEX, " ")
    }

    private fun normalize(value: String): String = cleanSegment(value).lowercase()

    private fun looksLikeArtistCandidate(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        if (splitSegments(value).size >= 2) return false
        if (normalized in GENERIC_FOLDER_NAMES) return false
        if (normalized.matches(NUMERIC_ONLY_REGEX)) return false
        if (normalized.matches(YEAR_ONLY_REGEX)) return false
        if (normalized.matches(DISC_FOLDER_REGEX)) return false
        if (normalized.length > 48) return false
        return true
    }

    private fun looksLikeAlbumCandidate(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = normalize(value)
        if (normalized.isBlank()) return false
        if (normalized in GENERIC_FOLDER_NAMES) return false
        if (normalized.matches(NUMERIC_ONLY_REGEX)) return false
        if (normalized.matches(YEAR_ONLY_REGEX)) return false
        if (normalized.matches(DISC_FOLDER_REGEX)) return false
        if (normalized.length > 80) return false
        return true
    }

    private fun parentFolderPath(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isBlank()) "/" else parent
    }

    companion object {
        private const val TOKEN_FREQUENCY_THRESHOLD = 2
        private val SEGMENT_SEPARATOR_REGEX = Regex("\\s*[-_–—·]+\\s*")
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val NUMERIC_ONLY_REGEX = Regex("^\\d+$")
        private val YEAR_ONLY_REGEX = Regex("^\\d{4}$")
        private val DISC_FOLDER_REGEX = Regex("^(disc|cd)\\s*\\d+$")

        private val GENERIC_FOLDER_NAMES = setOf(
            "music",
            "songs",
            "song",
            "audio",
            "audios",
            "download",
            "downloads",
            "cloud",
            "webdav",
            "album",
            "albums",
            "track",
            "tracks",
            "artist",
            "artists",
            "playlist",
            "playlists",
            "unknown",
            "unknown artist",
            "various artists",
            "cover",
            "lyrics",
            "lrc",
            "tmp",
            "temp"
        )
    }
}
