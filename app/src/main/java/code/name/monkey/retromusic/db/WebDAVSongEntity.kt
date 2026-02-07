/*
 * Copyright (c) 2025 Hemanth Savarla.
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

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Entity representing a song from a WebDAV server
 * Stores metadata locally while streaming audio from the server
 */
@Parcelize
@Entity(
    tableName = "webdav_songs",
    indices = [Index(value = ["config_id", "remote_path"], unique = true)]
)
data class WebDAVSongEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "config_id")
    val configId: Long,

    @ColumnInfo(name = "remote_path")
    val remotePath: String, // e.g., /Music/song.mp3

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist_name")
    val artistName: String,

    @ColumnInfo(name = "album_name")
    val albumName: String,

    @ColumnInfo(name = "duration")
    val duration: Long,

    @ColumnInfo(name = "album_art_path")
    val albumArtPath: String? = null, // Remote path to album art

    @ColumnInfo(name = "track_number")
    val trackNumber: Int = 0,

    @ColumnInfo(name = "year")
    val year: Int = 0,

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    @ColumnInfo(name = "content_type")
    val contentType: String = "audio/mpeg",

    @ColumnInfo(name = "remote_last_modified")
    val remoteLastModified: Long = 0L,

    @ColumnInfo(name = "file_fingerprint")
    val fileFingerprint: String = ""
) : Parcelable
