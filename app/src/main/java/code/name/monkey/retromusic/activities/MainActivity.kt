/*
 * Copyright (c) 2020 Hemanth Savarla.
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
package code.name.monkey.retromusic.activities

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.lifecycleScope
import androidx.navigation.contains
import androidx.navigation.ui.setupWithNavController
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsCastActivity
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.SearchQueryHelper.getSongs
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.model.CategoryInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.PlaylistSongsLoader
import code.name.monkey.retromusic.repository.ServerRepository
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.FileUtil
import code.name.monkey.retromusic.util.AppRater
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.getExternalStoragePublicDirectory
import code.name.monkey.retromusic.util.logE
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.get
import java.io.File
import java.io.FileFilter
import java.util.LinkedHashSet
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AbsCastActivity() {
    companion object {
        const val TAG = "MainActivity"
        const val EXPAND_PANEL = "expand_panel"
        private const val SERVER_VALIDATION_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val LOCAL_SCAN_CHUNK_SIZE = 200
        private const val LOCAL_SCAN_CHUNK_TIMEOUT_MS = 45_000L
    }

    private val bootstrapAudioFileFilter = FileFilter { file ->
        !file.isHidden && (
            file.isDirectory ||
                FileUtil.fileIsMimeType(file, "audio/*", MimeTypeMap.getSingleton()) ||
                FileUtil.fileIsMimeType(file, "application/opus", MimeTypeMap.getSingleton()) ||
                FileUtil.fileIsMimeType(file, "application/ogg", MimeTypeMap.getSingleton())
            )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTaskDescriptionColorAuto()
        hideStatusBar()
        updateTabs()
        AppRater.appLaunched(this)

        setupNavigationController()
        maybeRunServerValidation()

        WhatsNewFragment.showChangeLog(this)
    }

    private fun maybeBootstrapLocalSongsOnFirstInstall() {
        if (!hasPermissions()) return

        val installTime = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).firstInstallTime
        }.getOrElse { error ->
            Log.w(TAG, "Unable to resolve install time, skip local songs bootstrap", error)
            0L
        }
        if (installTime <= 0L) return
        if (PreferenceUtil.localSongsBootstrapInstallTime == installTime) return

        PreferenceUtil.localSongsBootstrapInstallTime = installTime
        PreferenceUtil.isLocalSongsBootstrapRunning = true
        libraryViewModel.forceReload(ReloadType.Songs)

        lifecycleScope.launch(IO) {
            val scannedCount = runCatching {
                reindexLocalSongsFromFilesystem()
            }.onFailure { error ->
                Log.w(TAG, "Local songs bootstrap scan failed", error)
            }.getOrDefault(0)

            PreferenceUtil.isLocalSongsBootstrapRunning = false

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                libraryViewModel.forceReload(ReloadType.Songs)
                libraryViewModel.forceReload(ReloadType.Albums)
                libraryViewModel.forceReload(ReloadType.Artists)
                libraryViewModel.forceReload(ReloadType.HomeSections)
                libraryViewModel.forceReload(ReloadType.Suggestions)
            }
            Log.i(TAG, "Local songs bootstrap finished, scanned files=$scannedCount")
        }
    }

    private suspend fun reindexLocalSongsFromFilesystem(): Int {
        val roots = collectLocalSongScanRoots()
        if (roots.isEmpty()) return 0

        val audioPaths = LinkedHashSet<String>()
        roots.forEach { root ->
            if (root.isDirectory) {
                FileUtil.listFilesDeep(root, bootstrapAudioFileFilter).forEach { file ->
                    audioPaths.add(FileUtil.safeGetCanonicalPath(file))
                }
            } else if (bootstrapAudioFileFilter.accept(root)) {
                audioPaths.add(FileUtil.safeGetCanonicalPath(root))
            }
        }
        if (audioPaths.isEmpty()) return 0

        var scanned = 0
        audioPaths.toList().chunked(LOCAL_SCAN_CHUNK_SIZE).forEach { chunk ->
            scanned += withTimeoutOrNull(LOCAL_SCAN_CHUNK_TIMEOUT_MS) {
                scanAudioPathsChunk(chunk)
            } ?: 0
        }
        return scanned
    }

    private fun collectLocalSongScanRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots.add(getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC))
        roots.add(PreferenceUtil.startDirectory)
        if (PreferenceUtil.saveLastDirectory) {
            roots.add(PreferenceUtil.lastDirectory)
        }
        return roots
            .map { FileUtil.safeGetCanonicalFile(it) }
            .filter { root ->
                root.exists() &&
                    root.canRead() &&
                    root.absolutePath !in setOf("/", "/storage", "/storage/emulated")
            }
            .distinctBy { it.absolutePath }
    }

    private suspend fun scanAudioPathsChunk(paths: List<String>): Int =
        suspendCancellableCoroutine { continuation ->
            if (paths.isEmpty()) {
                continuation.resume(0)
                return@suspendCancellableCoroutine
            }
            var scanned = 0
            MediaScannerConnection.scanFile(
                applicationContext,
                paths.toTypedArray(),
                null
            ) { _, _ ->
                scanned += 1
                if (scanned >= paths.size && continuation.isActive) {
                    continuation.resume(scanned)
                }
            }
        }

    private fun maybeRunServerValidation() {
        val now = System.currentTimeMillis()
        val lastRunAt = PreferenceUtil.lastWebDavValidationAt
        if (now - lastRunAt < SERVER_VALIDATION_INTERVAL_MS) {
            return
        }

        PreferenceUtil.lastWebDavValidationAt = now
        lifecycleScope.launch(IO) {
            runCatching {
                val serverRepository: ServerRepository = get()
                val enabledConfigs = serverRepository.getEnabledConfigs()
                if (enabledConfigs.isEmpty()) {
                    return@runCatching
                }
                enabledConfigs.forEach { config ->
                    val result = serverRepository.syncSongs(config.id)
                    if (result.isFailure) {
                        Log.w(
                            TAG,
                            "Server periodic validation failed for configId=${config.id}: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }
            }.onFailure { error ->
                Log.e(TAG, "Server periodic validation crashed", error)
            }
        }
    }

    private fun setupNavigationController() {
        val navController = findNavController(R.id.fragment_container)
        val navInflater = navController.navInflater
        val navGraph = navInflater.inflate(R.navigation.main_graph)
        if (PreferenceUtil.lastTab == R.id.action_search) {
            PreferenceUtil.lastTab = R.id.action_search_tab
        }
        if (PreferenceUtil.lastTab == R.id.action_song) {
            PreferenceUtil.lastTab = R.id.action_web
        }

        val categoryInfo: CategoryInfo = PreferenceUtil.libraryCategory.first { it.visible }
        if (categoryInfo.visible) {
            if (!navGraph.contains(PreferenceUtil.lastTab)) PreferenceUtil.lastTab =
                categoryInfo.category.id
            navGraph.setStartDestination(
                if (PreferenceUtil.rememberLastTab) {
                    PreferenceUtil.lastTab.let {
                        if (it == 0) {
                            categoryInfo.category.id
                        } else {
                            it
                        }
                    }
                } else categoryInfo.category.id
            )
        }
        navController.graph = navGraph
        navigationView.setupWithNavController(navController)
        navigationView.setOnItemSelectedListener { item ->
            navigateToTabRoot(navController, item.itemId)
        }
        // Scroll Fragment to top
        navigationView.setOnItemReselectedListener { item ->
            val currentDestinationId = navController.currentDestination?.id
            // When current destination is not bound to bottom navigation (e.g. artist opened from Database),
            // selected tab can be stale. In this case, tapping tab should navigate to the tab root.
            if (currentDestinationId != item.itemId) {
                navigateToTabRoot(navController, item.itemId)
                return@setOnItemReselectedListener
            }
            if (item.itemId == R.id.action_database) {
                // Keep Database behavior consistent: always show its root page when tab is tapped.
                navigateToTabRoot(navController, item.itemId)
                return@setOnItemReselectedListener
            }
            currentFragment(R.id.fragment_container).apply {
                if (this is IScrollHelper) {
                    scrollToTop()
                }
            }
        }
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == navGraph.startDestinationId) {
                currentFragment(R.id.fragment_container)?.enterTransition = null
            }
            when (destination.id) {
                R.id.action_home,
                R.id.action_song,
                R.id.action_web,
                R.id.action_database,
                R.id.action_album,
                R.id.action_artist,
                R.id.action_folder,
                R.id.action_playlist,
                R.id.action_genre,
                R.id.action_search_tab,
                R.id.albumDetailsFragment,
                R.id.artistDetailsFragment,
                R.id.albumArtistDetailsFragment,
                R.id.playlistDetailsFragment -> {
                    // Save the last tab
                    if (PreferenceUtil.rememberLastTab) {
                        saveTab(destination.id)
                    }
                    // Show Bottom Navigation Bar
                    setBottomNavVisibility(visible = true, animate = true)
                }
                R.id.playing_queue_fragment -> {
                    setBottomNavVisibility(visible = false, hideBottomSheet = true)
                }
                else -> setBottomNavVisibility(
                    visible = false,
                    animate = true
                ) // Hide Bottom Navigation Bar
            }
        }
    }

    private fun navigateToTabRoot(
        navController: androidx.navigation.NavController,
        tabId: Int
    ): Boolean {
        return runCatching {
            navController.popBackStack(tabId, false)
            if (navController.currentDestination?.id != tabId) {
                navController.navigate(tabId)
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "navigateToTabRoot failed for tabId=$tabId", error)
            false
        }
    }

    private fun saveTab(id: Int) {
        if (PreferenceUtil.libraryCategory.firstOrNull { it.category.id == id }?.visible == true) {
            PreferenceUtil.lastTab = id
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val expand = intent?.extra<Boolean>(EXPAND_PANEL)?.value ?: false
        if (expand && PreferenceUtil.isExpandPanel) {
            fromNotification = true
            slidingPanel.bringToFront()
            expandPanel()
            intent?.removeExtra(EXPAND_PANEL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        intent ?: return
        handlePlaybackIntent(intent)
    }

    @Suppress("deprecation")
    private fun handlePlaybackIntent(intent: Intent) {
        lifecycleScope.launch(IO) {
            val uri: Uri? = intent.data
            val mimeType: String? = intent.type
            var handled = false
            if (intent.action != null &&
                intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
            ) {
                val songs: List<Song> = getSongs(intent.extras!!)
                if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) {
                    MusicPlayerRemote.openAndShuffleQueue(songs, true)
                } else {
                    MusicPlayerRemote.openQueue(songs, 0, true)
                }
                handled = true
            }
            if (uri != null && uri.toString().isNotEmpty()) {
                MusicPlayerRemote.playFromUri(this@MainActivity, uri)
                handled = true
            } else if (MediaStore.Audio.Playlists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "playlistId", "playlist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = PlaylistSongsLoader.getPlaylistSongList(get(), id)
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            } else if (MediaStore.Audio.Albums.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "albumId", "album")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs = libraryViewModel.albumById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            } else if (MediaStore.Audio.Artists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "artistId", "artist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = libraryViewModel.artistById(id).songs
                    MusicPlayerRemote.openQueue(
                        songs,
                        position,
                        true
                    )
                    handled = true
                }
            }
            if (handled) {
                setIntent(Intent())
            }
        }
    }

    private fun parseLongFromIntent(
        intent: Intent,
        longKey: String,
        stringKey: String,
    ): Long {
        var id = intent.getLongExtra(longKey, -1)
        if (id < 0) {
            val idString = intent.getStringExtra(stringKey)
            if (idString != null) {
                try {
                    id = idString.toLong()
                } catch (e: NumberFormatException) {
                    logE(e)
                }
            }
        }
        return id
    }
}
