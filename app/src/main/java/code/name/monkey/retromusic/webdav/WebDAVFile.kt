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
package code.name.monkey.retromusic.webdav

/**
 * Represents a file or directory on a WebDAV server
 */
data class WebDAVFile(
    val name: String,
    val path: String,           // Relative path from server root, e.g., /Music/song.mp3
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long? = null,
    val contentType: String? = null
)
