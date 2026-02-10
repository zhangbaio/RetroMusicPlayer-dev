package code.name.monkey.retromusic.extensions

import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat.QueueItem
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil

val Song.uri
    get() = when {
        sourceType == SourceType.SERVER || sourceType == SourceType.WEBDAV
                || data.startsWith("http://") || data.startsWith("https://") ->
            Uri.parse(remotePath ?: data)
        else -> MusicUtil.getSongFileUri(songId = id)
    }

val Song.albumArtUri
    get() = when {
        sourceType == SourceType.SERVER || sourceType == SourceType.WEBDAV
                || data.startsWith("http://") || data.startsWith("https://") ->
            (webDavAlbumArtPath ?: remotePath ?: data).let(Uri::parse)
        else -> MusicUtil.getMediaStoreAlbumCoverUri(albumId)
    }

fun ArrayList<Song>.toMediaSessionQueue(): List<QueueItem> {
    return map { song ->
        val mediaDescription = MediaDescriptionCompat.Builder()
            .setMediaId(song.id.toString())
            .setTitle(song.displayTitle)
            .setSubtitle(song.artistName)
            .setIconUri(song.albumArtUri)
            .build()
        QueueItem(mediaDescription, song.hashCode().toLong())
    }
}
