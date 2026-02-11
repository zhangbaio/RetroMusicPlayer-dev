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
package code.name.monkey.retromusic.fragments.search

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.*
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.SearchAdapter
import code.name.monkey.retromusic.databinding.FragmentSearchBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.network.AlbumSearchApiResponse
import code.name.monkey.retromusic.network.ArtistSearchApiResponse
import code.name.monkey.retromusic.network.MusicApiService
import code.name.monkey.retromusic.network.SearchClassifyApiResponse
import code.name.monkey.retromusic.network.TrackResponse
import code.name.monkey.retromusic.network.provideMusicApiOkHttp
import code.name.monkey.retromusic.network.provideMusicApiRetrofit
import code.name.monkey.retromusic.network.provideMusicApiService
import code.name.monkey.retromusic.repository.ServerRepository
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.koin.android.ext.android.inject
import java.util.*
import kotlin.math.abs

class SearchFragment : AbsMainActivityFragment(R.layout.fragment_search),
    ChipGroup.OnCheckedStateChangeListener {

    companion object {
        const val QUERY = "query"
    }

    private enum class SearchMode {
        SONG,
        ARTIST
    }

    private enum class SearchTab {
        SONG_ALL,
        SONG_MINE,
        ARTISTS,
        ALBUMS,
        ARTIST_SONGS
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val serverRepository: ServerRepository by inject()

    private lateinit var searchAdapter: SearchAdapter
    private var query: String? = null

    private var job: Job? = null
    private var suppressChipCallback = false
    private var activeMode: SearchMode = SearchMode.SONG
    private var activeTab: SearchTab = SearchTab.SONG_ALL
    private var inferredArtist: String? = null
    private var pinnedArtist: String? = null

    private var cachedConfig: ServerConfig? = null
    private var cachedApiService: MusicApiService? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough().addTarget(view)
        reenterTransition = MaterialFadeThrough().addTarget(view)
        _binding = FragmentSearchBinding.bind(view)
        mainActivity.setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupChips()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.voiceSearch.setOnClickListener { startMicSearch() }
        binding.clearText.setOnClickListener {
            binding.searchView.clearText()
            query = null
            inferredArtist = null
            pinnedArtist = null
            searchAdapter.swapDataSet(emptyList())
        }
        binding.searchView.apply {
            doAfterTextChanged {
                if (!it.isNullOrEmpty()) {
                    search(it.toString())
                } else {
                    TransitionManager.beginDelayedTransition(binding.appBarLayout)
                    binding.voiceSearch.isVisible = true
                    binding.clearText.isGone = true
                }
            }
            focusAndShowKeyboard()
        }
        binding.keyboardPopup.apply {
            accentColor()
            setOnClickListener {
                binding.searchView.focusAndShowKeyboard()
            }
        }
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(QUERY)
        } else {
            query = arguments?.getString(QUERY)
        }
        query?.takeIf { it.isNotBlank() }?.let { initialQuery ->
            binding.searchView.setText(initialQuery)
            binding.searchView.setSelection(initialQuery.length)
        }

        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.keyboardPopup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it
            }
        }
        KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) {
            if (it) {
                binding.keyboardPopup.isGone = true
            } else {
                binding.keyboardPopup.show()
            }
        }
        binding.appBarLayout.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun setupChips() {
        val chips = binding.searchFilterGroup.children.map { it as Chip }
        if (!PreferenceUtil.materialYou) {
            val states = arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            )

            val colors = intArrayOf(
                android.R.color.transparent,
                accentColor().addAlpha(0.5F)
            )

            chips.forEach {
                it.chipBackgroundColor = ColorStateList(states, colors)
            }
        }

        binding.searchFilterGroup.setOnCheckedStateChangeListener(this)
        renderTabsForMode(SearchMode.SONG)
    }

    private fun setupRecyclerView() {
        searchAdapter = SearchAdapter(
            requireActivity(),
            emptyList(),
            onArtistClick = { artist ->
                inferredArtist = artist.name
                pinnedArtist = artist.name
                if (activeMode == SearchMode.ARTIST) {
                    selectTab(SearchTab.ARTIST_SONGS)
                    query?.takeIf { it.isNotBlank() }?.let { q ->
                        search(q)
                    }
                }
            },
            onAlbumClick = { album ->
                inferredArtist = album.artistName
                pinnedArtist = album.artistName
                if (activeMode == SearchMode.ARTIST) {
                    selectTab(SearchTab.ARTIST_SONGS)
                    query?.takeIf { it.isNotBlank() }?.let { q ->
                        search(q)
                    }
                }
            }
        )
        searchAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                binding.empty.isVisible = searchAdapter.itemCount < 1
            }
        })
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        binding.keyboardPopup.shrink()
                    } else if (dy < 0) {
                        binding.keyboardPopup.extend()
                    }
                }
            })
        }
    }

    private fun search(inputQuery: String) {
        val normalizedQuery = inputQuery.trim()
        val previousQuery = query
        if (previousQuery != normalizedQuery) {
            pinnedArtist = null
        }
        query = normalizedQuery
        TransitionManager.beginDelayedTransition(binding.appBarLayout)
        binding.voiceSearch.isGone = normalizedQuery.isNotEmpty()
        binding.clearText.isVisible = normalizedQuery.isNotEmpty()

        if (normalizedQuery.isBlank()) {
            inferredArtist = null
            pinnedArtist = null
            searchAdapter.swapDataSet(emptyList())
            return
        }

        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            val searchContext = withContext(Dispatchers.IO) {
                runCatching {
                    val (config, api) = resolveApiServiceOrThrow()
                    val classifyResp = api.classifySearch(normalizedQuery)
                    if (!classifyResp.isSuccess || classifyResp.data == null) {
                        throw IllegalStateException(classifyResp.message)
                    }
                    Triple(config, api, classifyResp.data)
                }
            }

            if (!isActive || normalizedQuery != query) {
                return@launch
            }

            val (config, api, classify) = searchContext.getOrElse { error ->
                searchAdapter.swapDataSet(emptyList())
                showToast(error.message ?: getString(R.string.search_server_error))
                return@launch
            }

            applySearchClassification(classify, normalizedQuery)
            val results = withContext(Dispatchers.IO) {
                fetchTabResults(config, api, normalizedQuery)
            }

            if (!isActive || normalizedQuery != query) {
                return@launch
            }

            results.onSuccess { data ->
                searchAdapter.swapDataSet(data)
            }.onFailure { error ->
                searchAdapter.swapDataSet(emptyList())
                showToast(error.message ?: getString(R.string.search_server_error))
            }
        }
    }

    private fun applySearchClassification(classify: SearchClassifyApiResponse, currentQuery: String) {
        val hasPinnedArtist = pinnedArtist?.takeIf { it.isNotBlank() } != null
        val nextMode = if (hasPinnedArtist || classify.mode.equals("ARTIST", ignoreCase = true)) {
            SearchMode.ARTIST
        } else {
            SearchMode.SONG
        }
        activeMode = nextMode
        inferredArtist = if (nextMode == SearchMode.ARTIST) {
            pinnedArtist?.takeIf { it.isNotBlank() }
                ?: classify.normalizedArtist?.takeIf { it.isNotBlank() }
                ?: currentQuery
        } else {
            pinnedArtist = null
            null
        }
        renderTabsForMode(nextMode)
    }

    private fun renderTabsForMode(mode: SearchMode) {
        suppressChipCallback = true
        val chips = listOf(
            binding.chipAudio,
            binding.chipArtists,
            binding.chipAlbums,
            binding.chipAlbumArtists,
            binding.chipGenres,
            binding.chipPlaylists
        )
        chips.forEach { it.isGone = true }

        if (mode == SearchMode.SONG) {
            binding.chipAudio.isVisible = true
            binding.chipArtists.isVisible = true
            binding.chipAudio.text = getString(R.string.search_tab_all_songs)
            binding.chipArtists.text = getString(R.string.search_tab_my_songs)
            if (activeTab != SearchTab.SONG_MINE) {
                activeTab = SearchTab.SONG_ALL
            }
            binding.searchFilterGroup.check(
                if (activeTab == SearchTab.SONG_MINE) R.id.chip_artists else R.id.chip_audio
            )
        } else {
            binding.chipAudio.isVisible = true
            binding.chipArtists.isVisible = true
            binding.chipAlbums.isVisible = true
            binding.chipAudio.text = getString(R.string.artists)
            binding.chipArtists.text = getString(R.string.albums)
            binding.chipAlbums.text = getString(R.string.songs)
            if (activeTab !in setOf(SearchTab.ARTISTS, SearchTab.ALBUMS, SearchTab.ARTIST_SONGS)) {
                activeTab = SearchTab.ARTISTS
            }
            val checkedId = when (activeTab) {
                SearchTab.ALBUMS -> R.id.chip_artists
                SearchTab.ARTIST_SONGS -> R.id.chip_albums
                else -> R.id.chip_audio
            }
            binding.searchFilterGroup.check(checkedId)
        }
        suppressChipCallback = false
    }

    private fun selectTab(tab: SearchTab) {
        activeTab = tab
        suppressChipCallback = true
        val checkedId = when (tab) {
            SearchTab.SONG_ALL, SearchTab.ARTISTS -> R.id.chip_audio
            SearchTab.SONG_MINE, SearchTab.ALBUMS -> R.id.chip_artists
            SearchTab.ARTIST_SONGS -> R.id.chip_albums
        }
        binding.searchFilterGroup.check(checkedId)
        suppressChipCallback = false
    }

    private fun resolveTabByChipId(checkedId: Int): SearchTab {
        return if (activeMode == SearchMode.SONG) {
            if (checkedId == R.id.chip_artists) SearchTab.SONG_MINE else SearchTab.SONG_ALL
        } else {
            when (checkedId) {
                R.id.chip_artists -> SearchTab.ALBUMS
                R.id.chip_albums -> SearchTab.ARTIST_SONGS
                else -> SearchTab.ARTISTS
            }
        }
    }

    private suspend fun fetchTabResults(
        config: ServerConfig,
        api: MusicApiService,
        keyword: String
    ): Result<List<Any>> = runCatching {
        val pageNo = 1
        val pageSize = 100

        when (activeTab) {
            SearchTab.SONG_ALL -> {
                val response = api.searchSongs(keyword, scope = "all", pageNo = pageNo, pageSize = pageSize)
                ensureSuccess(response.isSuccess, response.message)
                response.data?.records.orEmpty().map { toSong(it, config) }
            }

            SearchTab.SONG_MINE -> {
                val response = api.searchSongs(keyword, scope = "mine", pageNo = pageNo, pageSize = pageSize)
                ensureSuccess(response.isSuccess, response.message)
                response.data?.records.orEmpty().map { toSong(it, config) }
            }

            SearchTab.ARTISTS -> {
                val response = api.searchArtists(keyword, pageNo = pageNo, pageSize = pageSize)
                ensureSuccess(response.isSuccess, response.message)
                response.data?.records.orEmpty().map { toArtist(it, config) }
            }

            SearchTab.ALBUMS -> {
                val targetArtist = inferredArtist?.takeIf { it.isNotBlank() }
                val response = api.searchAlbums(
                    artist = targetArtist,
                    query = keyword,
                    pageNo = pageNo,
                    pageSize = pageSize
                )
                ensureSuccess(response.isSuccess, response.message)
                response.data?.records.orEmpty().map { toAlbum(it, config) }
            }

            SearchTab.ARTIST_SONGS -> {
                val targetArtist = inferredArtist?.takeIf { it.isNotBlank() } ?: keyword
                val response = api.searchArtistTracks(
                    artist = targetArtist,
                    query = keyword,
                    pageNo = pageNo,
                    pageSize = pageSize
                )
                ensureSuccess(response.isSuccess, response.message)
                response.data?.records.orEmpty().map { toSong(it, config) }
            }
        }
    }

    private suspend fun resolveApiServiceOrThrow(): Pair<ServerConfig, MusicApiService> {
        val enabled = serverRepository.getEnabledConfigs()
        val config = enabled.firstOrNull()
            ?: serverRepository.getAllConfigs().firstOrNull()
            ?: throw IllegalStateException(getString(R.string.search_server_not_configured))

        val cached = cachedApiService
        val cachedCfg = cachedConfig
        if (cached != null && cachedCfg != null && cachedCfg.id == config.id) {
            return config to cached
        }

        val okHttp = provideMusicApiOkHttp(config.serverUrl, config.apiToken)
        val retrofit = provideMusicApiRetrofit(config.serverUrl, okHttp)
        val api = provideMusicApiService(retrofit)
        cachedConfig = config
        cachedApiService = api
        return config to api
    }

    private fun toSong(track: TrackResponse, config: ServerConfig): Song {
        val safeArtist = track.artist.ifBlank { Artist.UNKNOWN_ARTIST_DISPLAY_NAME }
        val safeAlbum = track.album.ifBlank { "Unknown Album" }
        val safeTitle = track.title.ifBlank { "Unknown Title" }

        val streamUrl = "${config.serverUrl.trimEnd('/')}/api/v1/tracks/${track.id}/stream"
        val coverUrl = "${config.serverUrl.trimEnd('/')}/api/v1/tracks/${track.id}/cover"

        val artistId = synthesizeEntityId("artist", config.id, safeArtist)
        val albumId = synthesizeEntityId("album", config.id, "$safeAlbum|$safeArtist")

        return Song(
            id = synthesizeEntityId("song", config.id, track.id.toString()),
            title = safeTitle,
            trackNumber = 0,
            year = 0,
            duration = ((track.durationSec ?: 0.0) * 1000).toLong(),
            data = streamUrl,
            dateModified = 0,
            albumId = albumId,
            albumName = safeAlbum,
            artistId = artistId,
            artistName = safeArtist,
            composer = null,
            albumArtist = null,
            sourceType = SourceType.SERVER,
            remotePath = streamUrl,
            webDavConfigId = config.id,
            webDavAlbumArtPath = coverUrl
        )
    }

    private fun toArtist(item: ArtistSearchApiResponse, config: ServerConfig): Artist {
        val artistName = item.artist.ifBlank { Artist.UNKNOWN_ARTIST_DISPLAY_NAME }
        val coverTrackId = item.coverTrackId ?: 0L
        val sampleSong = buildVirtualSong(config, coverTrackId, artistName, artistName, artistName)
        val album = Album(sampleSong.albumId, listOf(sampleSong))
        return Artist(artistName, listOf(album), isAlbumArtist = false)
    }

    private fun toAlbum(item: AlbumSearchApiResponse, config: ServerConfig): Album {
        val artistName = item.artist.ifBlank { Artist.UNKNOWN_ARTIST_DISPLAY_NAME }
        val albumName = item.album.ifBlank { "Unknown Album" }
        val coverTrackId = item.coverTrackId ?: 0L
        val sampleSong = buildVirtualSong(config, coverTrackId, albumName, artistName, albumName)
        return Album(sampleSong.albumId, listOf(sampleSong))
    }

    private fun buildVirtualSong(
        config: ServerConfig,
        coverTrackId: Long,
        title: String,
        artist: String,
        album: String
    ): Song {
        val trackKey = if (coverTrackId > 0L) coverTrackId.toString() else "virtual:$artist|$album|$title"
        val streamTrackId = if (coverTrackId > 0L) coverTrackId else 0L
        val streamUrl = "${config.serverUrl.trimEnd('/')}/api/v1/tracks/$streamTrackId/stream"
        val coverUrl = "${config.serverUrl.trimEnd('/')}/api/v1/tracks/$streamTrackId/cover"
        val artistId = synthesizeEntityId("artist", config.id, artist)
        val albumId = synthesizeEntityId("album", config.id, "$album|$artist")
        return Song(
            id = synthesizeEntityId("song", config.id, trackKey),
            title = title,
            trackNumber = 0,
            year = 0,
            duration = 0L,
            data = streamUrl,
            dateModified = 0,
            albumId = albumId,
            albumName = album,
            artistId = artistId,
            artistName = artist,
            composer = null,
            albumArtist = artist,
            sourceType = SourceType.SERVER,
            remotePath = streamUrl,
            webDavConfigId = config.id,
            webDavAlbumArtPath = coverUrl
        )
    }

    private fun synthesizeEntityId(kind: String, configId: Long, identity: String): Long {
        val key = "server|$kind|$configId|${identity.trim().lowercase()}"
        val hash = key.hashCode().toLong()
        val positive = if (hash == Long.MIN_VALUE) Long.MAX_VALUE else abs(hash)
        return -positive.coerceAtLeast(1L)
    }

    private fun ensureSuccess(success: Boolean, message: String?) {
        if (!success) {
            throw IllegalStateException(message ?: getString(R.string.search_server_error))
        }
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
            }
        }
    }

    private fun startMicSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        try {
            speechInputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            showToast(getString(R.string.speech_not_supported))
        }
    }

    private val speechInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spokenText: String? =
                    result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                binding.searchView.setText(spokenText)
            }
        }

    override fun onResume() {
        super.onResume()
        checkForMargins()
    }

    override fun onDestroyView() {
        hideKeyboard(view)
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard(view)
    }

    private fun hideKeyboard(view: View?) {
        if (view != null) {
            val imm =
                requireContext().getSystemService<InputMethodManager>()
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onCheckedChanged(group: ChipGroup, checkedIds: MutableList<Int>) {
        if (suppressChipCallback) {
            return
        }
        val checkedId = checkedIds.firstOrNull() ?: return
        activeTab = resolveTabByChipId(checkedId)
        query?.takeIf { it.isNotBlank() }?.let { q ->
            search(q)
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem) = false
}

enum class Filter {
    SONGS,
    ARTISTS,
    ALBUMS,
    ALBUM_ARTISTS,
    GENRES,
    PLAYLISTS,
    NO_FILTER
}

fun TextInputEditText.clearText() {
    text = null
}
