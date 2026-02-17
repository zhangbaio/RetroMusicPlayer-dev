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
package code.name.monkey.retromusic.fragments.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.ViewPager
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import code.name.monkey.retromusic.LYRICS_TYPE
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter.AlbumCoverFragment
import code.name.monkey.retromusic.databinding.FragmentPlayerAlbumCoverBinding
import code.name.monkey.retromusic.extensions.isColorLight
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.fragments.NowPlayingScreen.*
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.fragments.base.goToLyrics
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.CoverLrcView
import code.name.monkey.retromusic.model.lyrics.Lyrics
import code.name.monkey.retromusic.transform.CarousalPagerTransformer
import code.name.monkey.retromusic.transform.ParallaxPagerTransformer
import code.name.monkey.retromusic.util.CoverLyricsType
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.repository.ServerRepository
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerAlbumCoverFragment : AbsMusicServiceFragment(R.layout.fragment_player_album_cover),
    ViewPager.OnPageChangeListener, MusicProgressViewUpdateHelper.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlayerAlbumCoverBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var currentPosition: Int = 0
    val viewPager get() = binding.viewPager

    private val colorReceiver = object : AlbumCoverFragment.ColorReceiver {
        override fun onColorReady(
            color: MediaNotificationProcessor,
            dominantColor: Int?,
            request: Int
        ) {
            if (currentPosition == request) {
                notifyColorChange(color, dominantColor)
            }
        }
    }
    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null

    private val lrcView: CoverLrcView get() = binding.lyricsView

    var lyrics: Lyrics? = null

    // 是否处于歌词模式（由外部 PlayerFragment 控制）
    private var isInLyricsMode = false
    private var isLyricsModeTransitionRunning = false

    /**
     * 设置是否显示歌词模式
     * @param showLyrics true 显示歌词，false 显示专辑封面
     */
    fun setLyricsMode(showLyrics: Boolean) {
        isInLyricsMode = showLyrics
        val nps = PreferenceUtil.nowPlayingScreen
        if (nps == Normal) {
            if (showLyrics) {
                animateNormalLyricsMode(showLyrics = true)
                progressViewUpdateHelper?.start()
            } else {
                animateNormalLyricsMode(showLyrics = false)
                progressViewUpdateHelper?.stop()
            }
        }
    }

    private fun animateNormalLyricsMode(showLyrics: Boolean) {
        if (_binding == null) return
        val showView = if (showLyrics) binding.lyricsView else binding.viewPager
        val hideView = if (showLyrics) binding.viewPager else binding.lyricsView

        if (showView.isVisible && !hideView.isVisible) {
            return
        }

        val interpolator = FastOutSlowInInterpolator()
        val offset = resources.displayMetrics.density * LYRICS_SWITCH_TRANSLATION_DP

        if (isLyricsModeTransitionRunning) {
            showView.animate().cancel()
            hideView.animate().cancel()
        }

        showView.animate().cancel()
        hideView.animate().cancel()

        binding.coverLyrics.isVisible = false
        showView.alpha = 0f
        showView.translationY = offset
        showView.isVisible = true
        isLyricsModeTransitionRunning = true

        hideView.animate()
            .alpha(0f)
            .translationY(-offset / 2f)
            .setDuration(LYRICS_SWITCH_OUT_DURATION)
            .setInterpolator(interpolator)
            .withEndAction {
                hideView.isVisible = false
                hideView.alpha = 1f
                hideView.translationY = 0f
            }
            .start()

        showView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(LYRICS_SWITCH_IN_DURATION)
            .setInterpolator(interpolator)
            .withEndAction {
                isLyricsModeTransitionRunning = false
            }
            .start()
    }

    fun removeSlideEffect() {
        val transformer = ParallaxPagerTransformer(R.id.player_image)
        transformer.setSpeed(0.3f)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewPager.setPageTransformer(false, transformer)
            }
        }
    }

    private fun updateLyrics() {
        val song = MusicPlayerRemote.currentSong
        lifecycleScope.launch(Dispatchers.IO) {
            val lrcFile = LyricUtil.getSyncedLyricsFile(song)
            if (lrcFile != null) {
                binding.lyricsView.loadLrc(lrcFile)
            } else {
                val embeddedLyrics = LyricUtil.getEmbeddedSyncedLyrics(song.data)
                if (embeddedLyrics != null) {
                    binding.lyricsView.loadLrc(embeddedLyrics)
                } else if (song.sourceType == SourceType.SERVER || song.sourceType == SourceType.WEBDAV) {
                    val serverLyrics = loadServerLyrics(song)
                    if (serverLyrics != null) {
                        binding.lyricsView.loadLrc(serverLyrics)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.lyricsView.reset()
                            binding.lyricsView.setLabel(context?.getString(R.string.no_lyrics_found))
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.lyricsView.reset()
                        binding.lyricsView.setLabel(context?.getString(R.string.no_lyrics_found))
                    }
                }
            }
        }
    }

    private suspend fun loadServerLyrics(song: Song): String? {
        return try {
            val configId = song.webDavConfigId ?: return null
            val trackIdStr = song.remotePath
                ?.let { Regex("/api/v1/tracks/(\\d+)/stream(?:-proxy)?").find(it) }
                ?.groupValues?.getOrNull(1)
            val serverTrackId = trackIdStr?.toLongOrNull() ?: return null
            val serverRepository = GlobalContext.get().get<ServerRepository>()
            serverRepository.getLyrics(serverTrackId, configId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerAlbumCoverBinding.bind(view)
        setupViewPager()
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        maybeInitLyrics()
        lrcView.apply {
            val white = android.graphics.Color.WHITE
            setCurrentColor(white)
            setNormalColor(white)
            setTimelineTextColor(white)
            setTimelineColor(white)
            setTimeTextColor(white)
            setDraggable(true) { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
                true
            }
            setOnClickListener {
                if (callbacks?.onLyricsClicked() != true) {
                    goToLyrics(requireActivity())
                }
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.addOnPageChangeListener(this)
        val nps = PreferenceUtil.nowPlayingScreen

        if (nps == Full || nps == Classic || nps == Fit || nps == Gradient) {
            binding.viewPager.offscreenPageLimit = 2
        } else if (PreferenceUtil.isCarouselEffect) {
            val metrics = resources.displayMetrics
            val ratio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
            binding.viewPager.clipToPadding = false
            val padding =
                if (ratio >= 1.777f) {
                    40
                } else {
                    100
                }
            binding.viewPager.setPadding(padding, 0, padding, 0)
            binding.viewPager.pageMargin = 0
            binding.viewPager.setPageTransformer(false, CarousalPagerTransformer(requireContext()))
        } else {
            binding.viewPager.offscreenPageLimit = 2
            binding.viewPager.setPageTransformer(
                true,
                PreferenceUtil.albumCoverTransform
            )
        }
    }

    override fun onResume() {
        super.onResume()
        maybeInitLyrics()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        binding.viewPager.removeOnPageChangeListener(this)
        progressViewUpdateHelper?.stop()
        isLyricsModeTransitionRunning = false
        _binding = null
    }

    override fun onServiceConnected() {
        updatePlayingQueue()
        updateLyrics()
    }

    override fun onPlayingMetaChanged() {
        if (viewPager.currentItem != MusicPlayerRemote.position) {
            viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        }
        updateLyrics()
    }

    override fun onQueueChanged() {
        updatePlayingQueue()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SHOW_LYRICS -> {
                if (PreferenceUtil.showLyrics) {
                    maybeInitLyrics()
                } else {
                    showLyrics(false)
                    progressViewUpdateHelper?.stop()
                }
            }
            LYRICS_TYPE -> {
                maybeInitLyrics()
            }
        }
    }

    private fun setLRCViewColors(@ColorInt primaryColor: Int, @ColorInt secondaryColor: Int) {
        val white = android.graphics.Color.WHITE
        lrcView.apply {
            setCurrentColor(white)
            setTimeTextColor(white)
            setTimelineColor(white)
            setNormalColor(white)
            setTimelineTextColor(white)
        }
    }

    private fun showLyrics(visible: Boolean) {
        val nps = PreferenceUtil.nowPlayingScreen
        // For Normal player, respect the lyrics mode state
        if (nps == Normal) {
            if (isInLyricsMode) {
                binding.viewPager.isVisible = false
                binding.coverLyrics.isVisible = false
                binding.lyricsView.isVisible = true
            } else {
                binding.viewPager.isVisible = true
                binding.coverLyrics.isVisible = false
                binding.lyricsView.isVisible = false
            }
            return
        }

        binding.viewPager.isVisible = true
        val lyrics: View = if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
            binding.coverLyrics.isVisible = false
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, if (visible) 0F else 1F).start()
            lrcView
        } else {
            binding.lyricsView.isVisible = false
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, 1F).start()
            binding.coverLyrics
        }

        if (visible) {
            // When showing, make visible first with alpha 0, then animate to alpha 1
            lyrics.alpha = 0f
            lyrics.isVisible = true
            ObjectAnimator.ofFloat(lyrics, View.ALPHA, 1F).start()
        } else {
            // When hiding, animate to alpha 0, then make invisible
            ObjectAnimator.ofFloat(lyrics, View.ALPHA, 0F).apply {
                doOnEnd {
                    lyrics.isVisible = false
                }
                start()
            }
        }
    }

    private fun maybeInitLyrics() {
        val nps = PreferenceUtil.nowPlayingScreen
        // For Normal player, respect the lyrics mode state
        if (nps == Normal) {
            if (isInLyricsMode) {
                // 歌词模式：隐藏 ViewPager，显示歌词
                binding.viewPager.isVisible = false
                binding.lyricsView.isVisible = true
                binding.coverLyrics.isVisible = false
                progressViewUpdateHelper?.start()
            } else {
                // 封面模式：显示 ViewPager，隐藏歌词
                binding.viewPager.isVisible = true
                binding.lyricsView.isVisible = false
                binding.coverLyrics.isVisible = false
                progressViewUpdateHelper?.stop()
            }
            return
        }
        // Don't show lyrics container for below conditions
        if (lyricViewNpsList.contains(nps) && PreferenceUtil.showLyrics) {
            showLyrics(true)
            if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
                progressViewUpdateHelper?.start()
            }
        } else {
            showLyrics(false)
            progressViewUpdateHelper?.stop()
        }
    }

    private fun updatePlayingQueue() {
        val nps = PreferenceUtil.nowPlayingScreen

        val adapter = binding.viewPager.adapter
        if (adapter is AlbumCoverPagerAdapter) {
            adapter.updateData(MusicPlayerRemote.playingQueue)
        } else {
            binding.viewPager.adapter = AlbumCoverPagerAdapter(
                parentFragmentManager,
                MusicPlayerRemote.playingQueue
            )
        }

        // For Normal player, show/hide viewPager based on lyrics mode
        if (nps == Normal) {
            binding.viewPager.isVisible = !isInLyricsMode
        }

        binding.viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        onPageSelected(MusicPlayerRemote.position)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        currentPosition = position
        if (binding.viewPager.adapter != null) {
            (binding.viewPager.adapter as AlbumCoverPagerAdapter).receiveColor(
                colorReceiver,
                position
            )
        }
        if (position != MusicPlayerRemote.position) {
            MusicPlayerRemote.playSongAt(position)
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
    }


    private fun notifyColorChange(color: MediaNotificationProcessor, dominantColor: Int?) {
        dominantColor?.let {
            callbacks?.onAdaptiveCoverColorChanged(it)
        }
        callbacks?.onColorChanged(color)
        val primaryColor = MaterialValueHelper.getPrimaryTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )
        val secondaryColor = MaterialValueHelper.getSecondaryDisabledTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )

        when (PreferenceUtil.nowPlayingScreen) {
            Flat, Normal, Material -> if (PreferenceUtil.isAdaptiveColor) {
                setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            } else {
                setLRCViewColors(primaryColor, secondaryColor)
            }
            Color, Classic -> setLRCViewColors(color.primaryTextColor, color.secondaryTextColor)
            Blur -> setLRCViewColors(android.graphics.Color.WHITE, ColorUtil.withAlpha(android.graphics.Color.WHITE, 0.5f))
            else -> setLRCViewColors(primaryColor, secondaryColor)
        }
    }

    fun setCallbacks(listener: Callbacks) {
        callbacks = listener
    }

    interface Callbacks {

        fun onColorChanged(color: MediaNotificationProcessor)

        fun onAdaptiveCoverColorChanged(@ColorInt dominantColor: Int) {}

        fun onFavoriteToggled()

        /**
         * Called when lyrics view is clicked.
         * @return true if handled, false to use default behavior (go to lyrics editor)
         */
        fun onLyricsClicked(): Boolean = false
    }

    companion object {
        val TAG: String = PlayerAlbumCoverFragment::class.java.simpleName
        private const val LYRICS_SWITCH_OUT_DURATION = 140L
        private const val LYRICS_SWITCH_IN_DURATION = 220L
        private const val LYRICS_SWITCH_TRANSLATION_DP = 8f
    }

    private val lyricViewNpsList =
        listOf(Blur, Classic, Color, Flat, Material, MD3, Normal, Plain, Simple)
}
