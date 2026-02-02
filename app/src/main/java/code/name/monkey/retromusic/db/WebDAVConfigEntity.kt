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
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Entity representing a WebDAV server configuration
 */
@Parcelize
@Entity(tableName = "webdav_configs")
data class WebDAVConfigEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "server_url")
    val serverUrl: String,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "password")
    val password: String, // Stored encrypted

    @ColumnInfo(name = "music_folders")
    val musicFolders: String = "", // Comma-separated folder paths

    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "last_synced")
    val lastSynced: Long = 0L
) : Parcelable {

    /** Get music folders as a list */
    fun getMusicFoldersList(): List<String> {
        return if (musicFolders.isBlank()) emptyList()
        else musicFolders.split(",").map { it.trim() }
    }

    companion object {
        /** Convert list of folders to comma-separated string */
        fun foldersToString(folders: List<String>): String {
            return folders.joinToString(",")
        }
    }
}
