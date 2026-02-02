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

import android.util.Base64
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Custom DataSource.Factory for WebDAV that adds Basic Authentication headers
 *
 * @param username WebDAV username
 * @param password WebDAV password
 */
class WebDAVDataSourceFactory(
    private val username: String,
    private val password: String
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return WebDAVHttpDataSource(username, password)
    }

    /**
     * HTTP DataSource with Basic Authentication support for WebDAV
     * Includes retry logic with exponential backoff for rate limiting (HTTP 429)
     */
    class WebDAVHttpDataSource(
        private val username: String,
        private val password: String
    ) : DataSource {

        companion object {
            private const val TAG = "WebDAVHttpDataSource"
            private const val MAX_RETRIES = 3
            private const val INITIAL_BACKOFF_MS = 5000L  // Start with 5 seconds for 115 cloud
            private const val MAX_BACKOFF_MS = 60000L    // Max 60 seconds
        }

        private val defaultHttpDataSource: DefaultHttpDataSource by lazy {
            DefaultHttpDataSource.Factory().apply {
                setDefaultRequestProperties(mapOf(
                    "Authorization" to encodeBasicAuth(username, password)
                ))
                setConnectTimeoutMs(30000)
                setReadTimeoutMs(30000)
                // Enable redirects - 115 cloud and other WebDAV servers may use 302 redirects
                setAllowCrossProtocolRedirects(true)
            }.createDataSource()
        }

        override fun addTransferListener(transferListener: TransferListener) {
            defaultHttpDataSource.addTransferListener(transferListener)
        }

        @Throws(IOException::class)
        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            var lastException: IOException? = null
            var retryCount = 0
            var backoffMs = INITIAL_BACKOFF_MS

            while (retryCount <= MAX_RETRIES) {
                try {
                    if (retryCount > 0) {
                        Log.d(TAG, "Retry attempt $retryCount after ${backoffMs}ms delay")
                        Thread.sleep(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                    return defaultHttpDataSource.open(dataSpec)
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    lastException = e
                    Log.w(TAG, "HTTP error ${e.responseCode}, headers: ${e.headerFields}")
                    if (e.responseCode == 429) {
                        Log.w(TAG, "Rate limited (429), will retry. Attempt: $retryCount")
                        retryCount++
                        // Check for Retry-After header
                        val retryAfter = e.headerFields["Retry-After"]?.firstOrNull()
                        if (retryAfter != null) {
                            try {
                                val retryAfterMs = retryAfter.toLong() * 1000
                                backoffMs = retryAfterMs.coerceAtMost(MAX_BACKOFF_MS)
                                Log.d(TAG, "Server requested Retry-After: ${retryAfter}s")
                            } catch (_: NumberFormatException) {
                                // Ignore if Retry-After is not a number
                            }
                        }
                    } else if (e.responseCode in 301..308) {
                        // Redirect errors - log the Location header for debugging
                        val location = e.headerFields["Location"]?.firstOrNull()
                        Log.e(TAG, "Redirect (${e.responseCode}) not followed. Location: $location")
                        // Don't retry redirects - this means cross-protocol redirect is disabled or failed
                        throw e
                    } else if (e.responseCode in 500..599) {
                        // Server errors, retry
                        Log.w(TAG, "Server error (${e.responseCode}), will retry. Attempt: $retryCount")
                        retryCount++
                    } else {
                        // Other errors, don't retry
                        throw e
                    }
                } catch (e: IOException) {
                    lastException = e
                    Log.w(TAG, "IO error, will retry. Attempt: $retryCount", e)
                    retryCount++
                }
            }

            Log.e(TAG, "All retry attempts failed after $MAX_RETRIES retries")
            throw lastException ?: IOException("Failed to open data source after retries")
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return defaultHttpDataSource.read(buffer, offset, length)
        }

        override fun close() {
            defaultHttpDataSource.close()
        }

        override fun getUri(): android.net.Uri? {
            return defaultHttpDataSource.uri
        }
    }

    companion object {
        /**
         * Encode username and password as Basic Auth
         */
        fun encodeBasicAuth(username: String, password: String): String {
            val credentials = "$username:$password"
            val encoded = Base64.encodeToString(
                credentials.toByteArray(),
                Base64.NO_WRAP
            )
            return "Basic $encoded"
        }
    }
}
