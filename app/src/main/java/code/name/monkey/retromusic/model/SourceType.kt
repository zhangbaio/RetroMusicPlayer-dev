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

/**
 * Represents the source type of a song.
 */
enum class SourceType {
    /** Local music file from device storage */
    LOCAL,

    /** @deprecated Kept for database migration compatibility. Use SERVER instead. */
    WEBDAV,

    /** Music file streamed from a music server API */
    SERVER
}
