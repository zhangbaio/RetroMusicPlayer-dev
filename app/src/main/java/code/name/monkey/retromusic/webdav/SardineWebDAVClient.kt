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

import android.util.Log
import code.name.monkey.retromusic.model.WebDAVConfig
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * WebDAV client implementation using the Sardine library
 */
class SardineWebDAVClient : WebDAVClient {

    override suspend fun testConnection(config: WebDAVConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val sardine = createSardine(config)
            val url = buildUrl(config, "/")
            Log.d(TAG, "Testing connection to: $url")

            val exists = sardine.exists(url)
            Log.d(TAG, "Connection test result: $exists")
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}", e)
            // Try to list instead of exists - some servers don't support exists well
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, "/")
                val resources = sardine.list(url)
                Log.d(TAG, "Fallback list succeeded with ${resources.size} items")
                resources.isNotEmpty()
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback list also failed: ${e2.message}", e2)
                false
            }
        }
    }

    override suspend fun listFiles(
        config: WebDAVConfig,
        path: String
    ): List<WebDAVFile> = withContext(Dispatchers.IO) {
        val sardine = createSardine(config)
        val url = buildUrl(config, path)
        Log.d(TAG, "Listing files at: $url")

        val resources = sardine.list(url)
        Log.d(TAG, "Found ${resources.size} resources")

        // Log each resource for debugging
        resources.forEachIndexed { index, res ->
            Log.d(TAG, "Resource[$index]: name=${res.name}, path=${res.path}, href=${res.href}, isDir=${res.isDirectory}")
        }

        // Skip the first item which is the directory itself
        resources.drop(1).map { resource ->
            resourceToWebDAVFile(resource, path)
        }
    }

    override suspend fun getFileInfo(
        config: WebDAVConfig,
        path: String
    ): WebDAVFile? = withContext(Dispatchers.IO) {
        try {
            val sardine = createSardine(config)
            val url = buildUrl(config, path)
            val resources = sardine.list(url)

            // The first resource is the file/directory itself
            if (resources.isNotEmpty()) {
                resourceToWebDAVFile(resources[0], path)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file info: ${e.message}", e)
            null
        }
    }

    override fun buildUrl(config: WebDAVConfig, path: String): String {
        val cleanBaseUrl = config.serverUrl.trimEnd('/')
        val cleanPath = path.trim()

        return when {
            cleanPath.startsWith("http://") || cleanPath.startsWith("https://") -> {
                cleanPath
            }
            cleanPath.isEmpty() || cleanPath == "/" -> {
                "$cleanBaseUrl/"
            }
            else -> {
                val pathWithSlash = if (cleanPath.startsWith("/")) cleanPath else "/$cleanPath"
                "$cleanBaseUrl$pathWithSlash"
            }
        }
    }

    companion object {
        private const val TAG = "SardineWebDAVClient"
        private const val MAX_RECURSION_DEPTH = 20

        // Audio file extensions to scan for
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "ogg", "wav", "aac", "opus", "wma", "m4b", "mp4a"
        )
    }

    /**
     * Recursively scan directory for audio files
     */
    suspend fun scanAudioFiles(config: WebDAVConfig, path: String = "/"): List<WebDAVFile> =
        withContext(Dispatchers.IO) {
            val audioFiles = mutableListOf<WebDAVFile>()
            val visitedPaths = mutableSetOf<String>()
            scanDirectoryRecursive(config, path, audioFiles, visitedPaths, 0)
            audioFiles
        }

    private suspend fun scanDirectoryRecursive(
        config: WebDAVConfig,
        path: String,
        audioFiles: MutableList<WebDAVFile>,
        visitedPaths: MutableSet<String>,
        depth: Int
    ) {
        // Normalize path for comparison
        val normalizedPath = path.trimEnd('/')

        // Prevent infinite loops from symlinks or circular references
        if (normalizedPath in visitedPaths) {
            Log.d(TAG, "Skipping already visited path: $path")
            return
        }

        // Prevent excessive recursion depth
        if (depth > MAX_RECURSION_DEPTH) {
            Log.w(TAG, "Max recursion depth reached at: $path")
            return
        }

        visitedPaths.add(normalizedPath)
        Log.d(TAG, "Scanning directory at depth $depth: '$path'")

        val files = listFiles(config, path)
        Log.d(TAG, "Listed ${files.size} items in '$path'")

        for (file in files) {
            if (file.isDirectory) {
                Log.d(TAG, "Found directory: ${file.name} at ${file.path}")
                // Recursively scan subdirectories
                scanDirectoryRecursive(config, file.path, audioFiles, visitedPaths, depth + 1)
            } else {
                val isAudio = isAudioFile(file)
                Log.d(TAG, "Found file: ${file.name}, isAudio=$isAudio")
                if (isAudio) {
                    audioFiles.add(file)
                }
            }
        }
    }

    private fun isAudioFile(file: WebDAVFile): Boolean {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    private fun createSardine(config: WebDAVConfig): OkHttpSardine {
        val sardine = OkHttpSardine()
        sardine.setCredentials(config.username, config.password)
        return sardine
    }

    private fun resourceToWebDAVFile(resource: DavResource, currentPath: String): WebDAVFile {
        // Get the name from resource
        val name = resource.name
            ?: resource.href?.toString()?.substringAfterLast('/')?.trimEnd('/')
            ?: ""

        // Decode URL-encoded name (e.g., %20 -> space)
        val decodedName = try {
            URLDecoder.decode(name, "UTF-8")
        } catch (e: Exception) {
            name
        }

        // Build relative path based on current path and name
        val relativePath = if (currentPath == "/" || currentPath.isEmpty()) {
            "/$decodedName"
        } else {
            "${currentPath.trimEnd('/')}/$decodedName"
        }

        // Add trailing slash for directories
        val finalPath = if (resource.isDirectory && !relativePath.endsWith("/")) {
            "$relativePath/"
        } else {
            relativePath
        }

        Log.d(TAG, "Mapping resource: name=$decodedName, currentPath=$currentPath, finalPath=$finalPath")

        return WebDAVFile(
            name = decodedName,
            path = finalPath,
            isDirectory = resource.isDirectory,
            size = resource.contentLength ?: 0,
            lastModified = resource.modified?.time,
            contentType = resource.contentType
        )
    }
}
