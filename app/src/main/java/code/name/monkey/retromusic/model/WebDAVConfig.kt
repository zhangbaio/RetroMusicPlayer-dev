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
package code.name.monkey.retromusic.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Configuration for a WebDAV server
 */
@Parcelize
data class WebDAVConfig(
    val id: Long = 0L,
    val name: String,
    val serverUrl: String,      // Full URL including scheme (e.g., https://cloud.example.com/remote.php/webdav)
    val username: String,
    val password: String,        // Will be encrypted when stored
    val musicFolders: List<String> = emptyList(),  // Paths to music folders on the WebDAV server
    val isEnabled: Boolean = true,
    val lastSynced: Long = 0L
) : Parcelable
