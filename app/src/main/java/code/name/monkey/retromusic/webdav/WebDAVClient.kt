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

import code.name.monkey.retromusic.model.WebDAVConfig

/**
 * WebDAV client interface for operations on WebDAV servers
 */
interface WebDAVClient {
    /**
     * Test connection to the WebDAV server
     * @return true if connection successful, false otherwise
     */
    suspend fun testConnection(config: WebDAVConfig): Boolean

    /**
     * List files and directories at the given path
     * @param config WebDAV server configuration
     * @param path Relative path to list (e.g., "/Music" or "/")
     * @return List of WebDAVFile objects
     */
    suspend fun listFiles(config: WebDAVConfig, path: String = "/"): List<WebDAVFile>

    /**
     * Get information about a specific file
     * @param config WebDAV server configuration
     * @param path Path to the file
     * @return WebDAVFile object or null if not found
     */
    suspend fun getFileInfo(config: WebDAVConfig, path: String): WebDAVFile?

    /**
     * Build the full URL for a given path
     * @param config WebDAV server configuration
     * @param path Relative path
     * @return Full HTTP/HTTPS URL
     */
    fun buildUrl(config: WebDAVConfig, path: String): String
}
