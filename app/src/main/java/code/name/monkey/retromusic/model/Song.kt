/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// update equals and hashcode if fields changes
@Parcelize
open class Song(
    open val id: Long,
    open val title: String,
    open val trackNumber: Int,
    open val year: Int,
    open val duration: Long,
    open val data: String,
    open val dateModified: Long,
    open val albumId: Long,
    open val albumName: String,
    open val artistId: Long,
    open val artistName: String,
    open val composer: String?,
    open val albumArtist: String?,
    // Server/remote support fields - defaults ensure backward compatibility
    open val sourceType: SourceType = SourceType.LOCAL,
    open val remotePath: String? = null,  // Full URL for server songs
    open val webDavConfigId: Long? = null,  // Server config ID (DB column name kept for migration compat)
    open val webDavAlbumArtPath: String? = null  // Server cover URL (DB column name kept for migration compat)
) : Parcelable {

    val displayArtistName: String
        get() {
            val normalized = artistName.trim()
            if (normalized.isEmpty()) {
                return ""
            }
            if (normalized == Artist.UNKNOWN_ARTIST_DISPLAY_NAME) {
                return ""
            }
            val lower = normalized.lowercase()
            if (lower == "unknown" || lower == "<unknown>") {
                return ""
            }
            return artistName
        }

    val displayTitle: String
        get() {
            val normalizedTitle = title.trim()
            if (normalizedTitle.isNotEmpty()) {
                return title
            }
            val candidatePath = (remotePath ?: data).trim()
            if (candidatePath.isEmpty()) {
                return ""
            }
            val fileName = candidatePath
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            if (fileName.isEmpty()) {
                return ""
            }
            val decoded = Uri.decode(fileName)
            val stripped = decoded.substringBeforeLast('.', decoded)
                .replace('_', ' ')
                .replace('+', ' ')
                .trim()
            return if (stripped.isNotEmpty()) stripped else decoded
        }


    // need to override manually because is open and cannot be a data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Song

        if (id != other.id) return false
        if (title != other.title) return false
        if (trackNumber != other.trackNumber) return false
        if (year != other.year) return false
        if (duration != other.duration) return false
        if (data != other.data) return false
        if (dateModified != other.dateModified) return false
        if (albumId != other.albumId) return false
        if (albumName != other.albumName) return false
        if (artistId != other.artistId) return false
        if (artistName != other.artistName) return false
        if (composer != other.composer) return false
        if (albumArtist != other.albumArtist) return false
        if (sourceType != other.sourceType) return false
        if (remotePath != other.remotePath) return false
        if (webDavConfigId != other.webDavConfigId) return false
        if (webDavAlbumArtPath != other.webDavAlbumArtPath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + trackNumber
        result = 31 * result + year
        result = 31 * result + duration.hashCode()
        result = 31 * result + data.hashCode()
        result = 31 * result + dateModified.hashCode()
        result = 31 * result + albumId.hashCode()
        result = 31 * result + albumName.hashCode()
        result = 31 * result + artistId.hashCode()
        result = 31 * result + artistName.hashCode()
        result = 31 * result + (composer?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + (remotePath?.hashCode() ?: 0)
        result = 31 * result + (webDavConfigId?.hashCode() ?: 0)
        result = 31 * result + (webDavAlbumArtPath?.hashCode() ?: 0)
        return result
    }


    companion object {

        @JvmStatic
        val emptySong = Song(
            id = -1,
            title = "",
            trackNumber = -1,
            year = -1,
            duration = -1,
            data = "",
            dateModified = -1,
            albumId = -1,
            albumName = "",
            artistId = -1,
            artistName = "",
            composer = "",
            albumArtist = ""
        )
    }
}
