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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URLDecoder
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections

/**
 * WebDAV client implementation using the Sardine library
 */
class SardineWebDAVClient : WebDAVClient {

    data class ScanAudioResult(
        val audioFiles: List<WebDAVFile>,
        val folderCoverMap: Map<String, String>
    )

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
        val resources = listResourcesWithRetry(config, sardine, path)

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
        private const val DIRECTORY_SCAN_PARALLELISM = 2
        private const val LIST_RETRY_COUNT = 3
        private const val LIST_RETRY_DELAY_MS = 800L

        // Audio file extensions to scan for
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "m4a", "ogg", "wav", "aac", "opus", "wma", "m4b", "mp4a"
        )

        private val COVER_FILE_NAMES = setOf(
            "cover.jpg", "album.jpg", "folder.jpg",
            "cover.png", "album.png", "folder.png",
            "cover.webp", "album.webp", "folder.webp"
        )
    }

    /**
     * Recursively scan directory for audio files
     */
    suspend fun scanAudioFiles(config: WebDAVConfig, path: String = "/"): List<WebDAVFile> =
        scanAudioFilesWithMetadata(config, path).audioFiles

    suspend fun scanAudioFilesWithMetadata(
        config: WebDAVConfig,
        path: String = "/"
    ): ScanAudioResult =
        withContext(Dispatchers.IO) {
            val parallelAttempt = runCatching {
                val visitedPaths = Collections.synchronizedSet(mutableSetOf<String>())
                val semaphore = Semaphore(DIRECTORY_SCAN_PARALLELISM)
                val sardine = createSardine(config)
                scanDirectoryRecursive(
                    config = config,
                    path = path,
                    visitedPaths = visitedPaths,
                    depth = 0,
                    semaphore = semaphore,
                    sardine = sardine
                )
            }
            parallelAttempt.getOrElse { error ->
                if (!shouldFallbackToSerialScan(error)) {
                    throw error
                }
                Log.w(
                    TAG,
                    "Parallel WebDAV scan failed at '$path', fallback to serial scan: ${error.message}"
                )
                val visitedPaths = Collections.synchronizedSet(mutableSetOf<String>())
                val sardine = createSardine(config)
                scanDirectoryRecursive(
                    config = config,
                    path = path,
                    visitedPaths = visitedPaths,
                    depth = 0,
                    semaphore = Semaphore(1),
                    sardine = sardine
                )
            }
        }

    suspend fun buildFolderQuickSignature(
        config: WebDAVConfig,
        path: String
    ): String = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(path)
        val files = listFiles(config, normalized).sortedBy { it.path }
        val signaturePayload = buildString {
            append(normalized)
            files.forEach { file ->
                append('|')
                append(file.name)
                append(':')
                append(if (file.isDirectory) 'd' else 'f')
                append(':')
                append(file.size)
                append(':')
                append(file.lastModified ?: 0L)
            }
        }
        signaturePayload.sha256Hex()
    }

    private suspend fun scanDirectoryRecursive(
        config: WebDAVConfig,
        path: String,
        visitedPaths: MutableSet<String>,
        depth: Int,
        semaphore: Semaphore,
        sardine: OkHttpSardine
    ): ScanAudioResult = coroutineScope {
        // Normalize path for comparison
        val normalizedPath = normalizeFolderPath(path)

        // Prevent infinite loops from symlinks or circular references
        if (!visitedPaths.add(normalizedPath)) {
            Log.d(TAG, "Skipping already visited path: $path")
            return@coroutineScope ScanAudioResult(emptyList(), emptyMap())
        }

        // Prevent excessive recursion depth
        if (depth > MAX_RECURSION_DEPTH) {
            Log.w(TAG, "Max recursion depth reached at: $path")
            return@coroutineScope ScanAudioResult(emptyList(), emptyMap())
        }

        Log.d(TAG, "Scanning directory depth=$depth path='$path'")

        val files = semaphore.withPermit {
            listFilesWithSardine(config, sardine, path)
        }
        Log.d(TAG, "Listed ${files.size} items in '$path'")

        val audioFiles = mutableListOf<WebDAVFile>()
        val folderCoverMap = mutableMapOf<String, String>()
        val childTasks = mutableListOf<Deferred<ScanAudioResult>>()
        var folderCoverPath: String? = null
        for (file in files) {
            if (file.isDirectory) {
                childTasks += async {
                    runCatching {
                        scanDirectoryRecursive(
                            config = config,
                            path = file.path,
                            visitedPaths = visitedPaths,
                            depth = depth + 1,
                            semaphore = semaphore,
                            sardine = sardine
                        )
                    }.getOrElse { error ->
                        Log.w(TAG, "Skip sub-folder due to scan error '${file.path}': ${error.message}")
                        ScanAudioResult(emptyList(), emptyMap())
                    }
                }
            } else {
                val isAudio = isAudioFile(file)
                if (isAudio) {
                    audioFiles.add(file)
                }
                if (folderCoverPath == null && isCoverFile(file)) {
                    folderCoverPath = file.path
                }
            }
        }
        if (!folderCoverPath.isNullOrBlank()) {
            folderCoverMap[normalizedPath] = folderCoverPath
        }
        childTasks.awaitAll().forEach { child ->
            audioFiles += child.audioFiles
            folderCoverMap.putAll(child.folderCoverMap)
        }
        return@coroutineScope ScanAudioResult(
            audioFiles = audioFiles,
            folderCoverMap = folderCoverMap
        )
    }

    private suspend fun listFilesWithSardine(
        config: WebDAVConfig,
        sardine: OkHttpSardine,
        path: String
    ): List<WebDAVFile> {
        val resources = listResourcesWithRetry(config, sardine, path)
        return resources.drop(1).map { resource ->
            resourceToWebDAVFile(resource, path)
        }
    }

    private suspend fun listResourcesWithRetry(
        config: WebDAVConfig,
        sardine: OkHttpSardine,
        path: String
    ): List<DavResource> {
        val url = buildUrl(config, path)
        var lastError: Exception? = null
        repeat(LIST_RETRY_COUNT) { attempt ->
            try {
                Log.d(TAG, "Listing files at: $url (attempt ${attempt + 1}/$LIST_RETRY_COUNT)")
                val resources = sardine.list(url)
                Log.d(TAG, "Found ${resources.size} resources")
                return resources
            } catch (error: Exception) {
                lastError = error
                val retryable = error is SocketTimeoutException || error is IOException
                val hasNext = attempt < LIST_RETRY_COUNT - 1
                if (!retryable || !hasNext) {
                    throw error
                }
                Log.w(TAG, "List failed, retrying path='$path': ${error.message}")
                delay(LIST_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Unknown list failure at path: $path")
    }

    private fun shouldFallbackToSerialScan(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SocketTimeoutException || current is IOException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isCoverFile(file: WebDAVFile): Boolean {
        if (file.isDirectory) return false
        return file.name.lowercase() in COVER_FILE_NAMES
    }

    private fun normalizeFolderPath(path: String): String {
        val withLeadingSlash = if (path.startsWith("/")) path else "/$path"
        val normalized = withLeadingSlash.trim().trimEnd('/')
        return if (normalized.isBlank()) "/" else normalized
    }

    private fun isAudioFile(file: WebDAVFile): Boolean {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        return extension in AUDIO_EXTENSIONS
    }

    private fun String.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0f).toString(16))
                append((byte.toInt() and 0x0f).toString(16))
            }
        }
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
