package code.name.monkey.retromusic.extensions

import android.net.Uri
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat.QueueItem
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.MusicUtil

val Song.uri get() = when (sourceType) {
    SourceType.LOCAL -> MusicUtil.getSongFileUri(songId = id)
    SourceType.WEBDAV -> Uri.parse(remotePath)
}

val Song.albumArtUri get() = when (sourceType) {
    SourceType.LOCAL -> MusicUtil.getMediaStoreAlbumCoverUri(albumId)
    SourceType.WEBDAV -> remotePath?.let { Uri.parse(it) }
}

fun ArrayList<Song>.toMediaSessionQueue(): List<QueueItem> {
    return map { song ->
        val mediaDescription = MediaDescriptionCompat.Builder()
            .setMediaId(song.id.toString())
            .setTitle(song.title)
            .setSubtitle(song.artistName)
            .setIconUri(song.albumArtUri)
            .build()
        QueueItem(mediaDescription, song.hashCode().toLong())
    }
}
