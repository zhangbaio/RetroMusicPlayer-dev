/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.db

import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.model.Song

fun List<HistoryEntity>.fromHistoryToSongs(): List<Song> {
    return map {
        it.toSong()
    }
}

fun List<SongEntity>.toSongs(): List<Song> {
    return map {
        it.toSong()
    }
}

fun Song.toHistoryEntity(timePlayed: Long): HistoryEntity {
    return HistoryEntity(
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = sourceType.name,
        remotePath = remotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath,
        timePlayed = timePlayed
    )
}

fun Song.toSongEntity(playListId: Long): SongEntity {
    return SongEntity(
        playlistCreatorId = playListId,
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = sourceType.name,
        remotePath = remotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath
    )
}

fun SongEntity.toSong(): Song {
    val resolvedSourceType = resolveSourceType(sourceType, data)
    val resolvedRemotePath = resolveRemotePath(resolvedSourceType, remotePath, data)
    return Song(
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = resolvedSourceType,
        remotePath = resolvedRemotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath
    )
}

fun PlayCountEntity.toSong(): Song {
    val resolvedSourceType = resolveSourceType(sourceType, data)
    val resolvedRemotePath = resolveRemotePath(resolvedSourceType, remotePath, data)
    return Song(
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = resolvedSourceType,
        remotePath = resolvedRemotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath
    )
}

fun HistoryEntity.toSong(): Song {
    val resolvedSourceType = resolveSourceType(sourceType, data)
    val resolvedRemotePath = resolveRemotePath(resolvedSourceType, remotePath, data)
    return Song(
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = resolvedSourceType,
        remotePath = resolvedRemotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath
    )
}

fun Song.toPlayCount(): PlayCountEntity {
    return PlayCountEntity(
        id = id,
        title = title,
        trackNumber = trackNumber,
        year = year,
        duration = duration,
        data = data,
        dateModified = dateModified,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        composer = composer,
        albumArtist = albumArtist,
        sourceType = sourceType.name,
        remotePath = remotePath,
        webDavConfigId = webDavConfigId,
        webDavAlbumArtPath = webDavAlbumArtPath,
        timePlayed = System.currentTimeMillis(),
        playCount = 1
    )
}

private fun resolveSourceType(rawValue: String, data: String): SourceType {
    val parsed = runCatching { SourceType.valueOf(rawValue) }.getOrNull()
    if (parsed != null) {
        // Map legacy WEBDAV to SERVER
        return if (parsed == SourceType.WEBDAV) SourceType.SERVER else parsed
    }
    return if (data.startsWith("http://") || data.startsWith("https://")) {
        SourceType.SERVER
    } else {
        SourceType.LOCAL
    }
}

private fun resolveRemotePath(sourceType: SourceType, remotePath: String?, data: String): String? {
    if (!remotePath.isNullOrBlank()) {
        return remotePath
    }
    return if ((sourceType == SourceType.SERVER || sourceType == SourceType.WEBDAV)
        && (data.startsWith("http://") || data.startsWith("https://"))
    ) {
        data
    } else {
        null
    }
}

fun List<Song>.toSongsEntity(playlistEntity: PlaylistEntity): List<SongEntity> {
    return map {
        it.toSongEntity(playlistEntity.playListId)
    }
}

fun List<Song>.toSongsEntity(playlistId: Long): List<SongEntity> {
    return map {
        it.toSongEntity(playlistId)
    }
}
