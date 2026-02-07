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
package code.name.monkey.retromusic.fragments.player.normal

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SNOWFALL
import code.name.monkey.retromusic.adapter.song.PlayingQueueAdapter
import code.name.monkey.retromusic.databinding.FragmentPlayerBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.extensions.setUpCastButton
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.songCoverOptions
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.service.MusicService
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ViewUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import code.name.monkey.retromusic.views.DrawableGradient
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.h6ah4i.android.widget.advrecyclerview.animator.DraggableItemAnimator
import com.h6ah4i.android.widget.advrecyclerview.draggable.RecyclerViewDragDropManager
import com.h6ah4i.android.widget.advrecyclerview.touchguard.RecyclerViewTouchActionGuardManager
import com.h6ah4i.android.widget.advrecyclerview.utils.WrapperAdapterUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerFragment : AbsPlayerFragment(R.layout.fragment_player),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var lastColor: Int = 0
    override val paletteColor: Int
        get() = lastColor

    private lateinit var controlsFragment: PlayerPlaybackControlsFragment
    private var valueAnimator: ValueAnimator? = null

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    // Fullscreen lyrics mode
    private var isFullscreenLyrics = false
    private var isLyricsMode = false  // 歌词模式：点击歌词按钮后进入
    private var isFavorite = false
    private var fullscreenHandler: Handler? = null
    private var fullscreenRunnable: Runnable? = null

    // Inline queue mode
    private var isQueueMode = false
    private var playingQueueAdapter: PlayingQueueAdapter? = null
    private var wrappedAdapter: RecyclerView.Adapter<*>? = null
    private var recyclerViewDragDropManager: RecyclerViewDragDropManager? = null
    private var recyclerViewTouchActionGuardManager: RecyclerViewTouchActionGuardManager? = null
    private var queueLayoutManager: LinearLayoutManager? = null

    companion object {
        private const val FULLSCREEN_DELAY = 3000L // 3 seconds

        fun newInstance(): PlayerFragment {
            return PlayerFragment()
        }
    }


    private fun colorize(i: Int) {
        if (valueAnimator != null) {
            valueAnimator?.cancel()
        }

        valueAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            surfaceColor(),
            i
        )
        valueAnimator?.addUpdateListener { animation ->
            if (isAdded) {
                val drawable = DrawableGradient(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        animation.animatedValue as Int,
                        surfaceColor()
                    ), 0
                )
                binding.colorGradientBackground.background = drawable
            }
        }
        valueAnimator?.setDuration(ViewUtil.RETRO_MUSIC_ANIM_TIME.toLong())?.start()
    }

    override fun onShow() {
        controlsFragment.show()
    }

    override fun onHide() {
        controlsFragment.hide()
    }

    override fun toolbarIconColor() = colorControlNormal()

    override fun onColorChanged(color: MediaNotificationProcessor) {
        controlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)

        ToolbarContentTintHelper.colorizeToolbar(
            binding.playerToolbar,
            colorControlNormal(),
            requireActivity()
        )

        if (PreferenceUtil.isAdaptiveColor) {
            colorize(color.backgroundColor)
        }
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) {
            updateIsFavorite()
            updateHeaderFavoriteIcon()
        }
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        setUpHeader()
        initFullscreenTimer()
        setupTapToExitFullscreen()
        startOrStopSnow(PreferenceUtil.isSnowFalling)
        updateLyricsIcon()

        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        binding.bottomToolbarContainer?.drawAboveSystemBars()
    }

    private fun initFullscreenTimer() {
        fullscreenHandler = Handler(Looper.getMainLooper())
        fullscreenRunnable = Runnable {
            // 只有在歌词模式下且正在播放时才进入全屏
            if (isLyricsMode && MusicPlayerRemote.isPlaying && !isFullscreenLyrics) {
                enterFullscreenLyrics()
            }
        }
    }

    private fun startFullscreenTimer() {
        fullscreenHandler?.removeCallbacks(fullscreenRunnable!!)
        // 只有在歌词模式下且正在播放时才启动计时器
        if (isLyricsMode && MusicPlayerRemote.isPlaying && !isFullscreenLyrics) {
            fullscreenHandler?.postDelayed(fullscreenRunnable!!, FULLSCREEN_DELAY)
        }
    }

    private fun stopFullscreenTimer() {
        fullscreenHandler?.removeCallbacks(fullscreenRunnable!!)
    }

    private fun setUpHeader() {
        binding.headerTitle?.isSelected = true
        binding.headerArtist?.isSelected = true

        binding.headerTitle?.setOnClickListener {
            goToAlbum(requireActivity())
        }
        binding.headerArtist?.setOnClickListener {
            goToArtist(requireActivity())
        }

        binding.headerFavourite?.setOnClickListener {
            toggleFavorite(MusicPlayerRemote.currentSong)
        }

        binding.headerMenu?.setOnClickListener { view ->
            showSongMenu(view)
        }

        updateHeaderInfo()
    }

    private fun updateHeaderInfo() {
        val song = MusicPlayerRemote.currentSong
        binding.headerTitle?.setText(song.title)
        binding.headerArtist?.setText(song.artistName)

        binding.smallAlbumArt?.let { imageView ->
            Glide.with(this)
                .load(RetroGlideExtension.getSongModel(song))
                .songCoverOptions(song)
                .into(imageView)
        }

        updateHeaderFavoriteIcon()
    }

    private fun updateHeaderFavoriteIcon() {
        lifecycleScope.launch(Dispatchers.IO) {
            val favorite = libraryViewModel.isSongFavorite(MusicPlayerRemote.currentSong.id)
            withContext(Dispatchers.Main) {
                isFavorite = favorite
                val icon = if (isFavorite) {
                    R.drawable.ic_favorite
                } else {
                    R.drawable.ic_favorite_border
                }
                binding.headerFavourite?.setImageDrawable(
                    requireContext().getDrawable(icon)
                )
            }
        }
    }

    private fun showSongMenu(view: View) {
        val popupMenu = android.widget.PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_song_detail, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { item ->
            onMenuItemClick(item)
        }
        popupMenu.show()
    }

    private fun enterFullscreenLyrics() {
        if (isFullscreenLyrics || _binding == null) return
        isFullscreenLyrics = true

        // 控制栏淡出动画
        binding.playbackControlsFragment.animate()
            .alpha(0f)
            .translationY(binding.playbackControlsFragment.height.toFloat())
            .setDuration(300L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                binding.playbackControlsFragment.isVisible = false
            }
            .start()

        binding.bottomToolbarContainer?.let { container ->
            container.animate()
                .alpha(0f)
                .translationY(container.height.toFloat())
                .setDuration(300L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    container.isVisible = false
                }
                .start()
        }

        expandLyricsArea()
        updateLyricsIcon()
    }

    private fun exitFullscreenLyrics() {
        if (!isFullscreenLyrics || _binding == null) return
        isFullscreenLyrics = false

        collapseLyricsArea()

        // Show controls immediately
        binding.playbackControlsFragment.alpha = 1f
        binding.playbackControlsFragment.translationY = 0f
        binding.playbackControlsFragment.isVisible = true

        binding.bottomToolbarContainer?.alpha = 1f
        binding.bottomToolbarContainer?.translationY = 0f
        binding.bottomToolbarContainer?.isVisible = true

        updateLyricsIcon()

        // Restart timer to re-enter fullscreen after 3 seconds
        startFullscreenTimer()
    }

    private fun expandLyricsArea() {
        if (_binding == null) return
        val container = binding.playerContainer
        if (container is ConstraintLayout) {
            val constraintSet = ConstraintSet()
            constraintSet.clone(container)
            // 全屏：歌词区域扩展到底部
            constraintSet.connect(
                R.id.playerAlbumCoverFragment,
                ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID,
                ConstraintSet.BOTTOM
            )
            constraintSet.applyTo(container)
        }
    }

    private fun collapseLyricsArea() {
        if (_binding == null) return
        val container = binding.playerContainer
        if (container is ConstraintLayout) {
            val constraintSet = ConstraintSet()
            constraintSet.clone(container)
            // 退出全屏：歌词区域到控制栏上方
            constraintSet.connect(
                R.id.playerAlbumCoverFragment,
                ConstraintSet.BOTTOM,
                R.id.playbackControlsFragment,
                ConstraintSet.TOP
            )
            constraintSet.applyTo(container)
        }
    }

    private fun updateAlbumCoverConstraints(lyricsMode: Boolean) {
        if (_binding == null) return
        val container = binding.playerContainer
        if (container is ConstraintLayout) {
            val constraintSet = ConstraintSet()
            constraintSet.clone(container)
            if (lyricsMode) {
                // 歌词模式：playerAlbumCoverFragment 顶部约束到 songHeaderContainer 底部
                constraintSet.connect(
                    R.id.playerAlbumCoverFragment,
                    ConstraintSet.TOP,
                    R.id.songHeaderContainer,
                    ConstraintSet.BOTTOM
                )
            } else {
                // 封面模式：playerAlbumCoverFragment 顶部约束到 statusBarContainer 底部
                constraintSet.connect(
                    R.id.playerAlbumCoverFragment,
                    ConstraintSet.TOP,
                    R.id.statusBarContainer,
                    ConstraintSet.BOTTOM
                )
            }
            constraintSet.applyTo(container)
        }
    }

    private fun setupTapToExitFullscreen() {
        // Click handling is now done via onLyricsClicked() callback
    }

    override fun onLyricsClicked(): Boolean {
        if (!isLyricsMode) return false
        if (isFullscreenLyrics) {
            // 全屏歌词模式 → 退出全屏，回到播放界面（保持歌词模式，3秒后再次进入全屏）
            exitFullscreenLyrics()
        } else {
            // 非全屏但在歌词模式 → 退出歌词模式，回到封面界面
            exitLyricsMode()
        }
        return true
    }

    override fun onPause() {
        recyclerViewDragDropManager?.cancelDrag()
        super.onPause()
    }

    override fun onDestroyView() {
        stopFullscreenTimer()
        fullscreenHandler = null
        releaseQueueResources()
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        _binding = null
    }

    private fun setUpSubFragments() {
        controlsFragment = whichFragment(R.id.playbackControlsFragment)
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.inflateMenu(R.menu.menu_player)
        //binding.playerToolbar.menu.setUpWithIcons()
        binding.playerToolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.playerToolbar.setOnMenuItemClickListener(this)

        ToolbarContentTintHelper.colorizeToolbar(
            binding.playerToolbar,
            colorControlNormal(),
            requireActivity()
        )

        // Setup bottom toolbar buttons
        binding.actionLyrics?.setOnClickListener {
            toggleLyrics()
        }
        binding.actionCast?.setUpCastButton(requireContext())
        binding.actionQueue?.setOnClickListener {
            toggleQueueMode()
        }
    }

    private fun toggleLyrics() {
        if (isLyricsMode) {
            // 退出歌词模式
            exitLyricsMode()
        } else {
            // 进入歌词模式，启动3秒计时器
            enterLyricsMode()
        }

        if (PreferenceUtil.lyricsScreenOn && isLyricsMode) {
            mainActivity.keepScreenOn(true)
        } else if (!PreferenceUtil.isScreenOnEnabled && !isLyricsMode) {
            mainActivity.keepScreenOn(false)
        }
    }

    private fun enterLyricsMode() {
        // 如果在队列模式下，先退出队列
        if (isQueueMode) {
            exitQueueMode()
        }
        isLyricsMode = true
        // 显示歌曲头部信息（小专辑图+歌名+歌手+收藏+更多）
        binding.songHeaderContainer?.isVisible = true
        // 隐藏控制栏中的歌曲信息（标题、歌手、收藏、更多）
        controlsFragment.setSongInfoVisible(false)
        // 修改 playerAlbumCoverFragment 的顶部约束到 songHeaderContainer
        updateAlbumCoverConstraints(lyricsMode = true)
        // 通知 PlayerAlbumCoverFragment 显示歌词
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setLyricsMode(true)
        updateLyricsIcon()
        // 启动3秒计时器，到时自动进入全屏
        startFullscreenTimer()
    }

    private fun exitLyricsMode() {
        isLyricsMode = false
        stopFullscreenTimer()
        if (isFullscreenLyrics) {
            isFullscreenLyrics = false
            // 恢复控制栏和底部工具栏
            binding.playbackControlsFragment.alpha = 1f
            binding.playbackControlsFragment.translationY = 0f
            binding.playbackControlsFragment.isVisible = true
            binding.bottomToolbarContainer?.alpha = 1f
            binding.bottomToolbarContainer?.translationY = 0f
            binding.bottomToolbarContainer?.isVisible = true
        }
        // 隐藏歌曲头部信息
        binding.songHeaderContainer?.isVisible = false
        // 显示控制栏中的歌曲信息
        controlsFragment.setSongInfoVisible(true)
        // 恢复 playerAlbumCoverFragment 的约束
        updateAlbumCoverConstraints(lyricsMode = false)
        // 通知 PlayerAlbumCoverFragment 显示封面
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment = whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setLyricsMode(false)
        updateLyricsIcon()
    }

    private fun updateLyricsIcon() {
        // 歌词模式下显示填充图标，否则显示轮廓图标
        val icon = if (isLyricsMode) {
            R.drawable.ic_lyrics
        } else {
            R.drawable.ic_lyrics_outline
        }
        binding.actionLyrics?.setImageDrawable(
            requireContext().getDrawable(icon)
        )
    }

    // ========== Queue Mode ==========

    private fun toggleQueueMode() {
        if (isQueueMode) {
            exitQueueMode()
        } else {
            enterQueueMode()
        }
    }

    private fun enterQueueMode() {
        // 如果在歌词模式下，先退出歌词
        if (isLyricsMode) {
            exitLyricsMode()
        }
        isQueueMode = true

        // 显示歌曲头部信息
        binding.songHeaderContainer?.isVisible = true
        // 隐藏专辑封面
        binding.playerAlbumCoverFragment.isVisible = false
        // 显示队列容器
        binding.queueContainer?.isVisible = true
        // 隐藏控制栏中的歌曲信息（标题、歌手、收藏、更多）
        controlsFragment.setSongInfoVisible(false)

        // 设置 RecyclerView
        setupQueueRecyclerView()
        // 更新模式按钮状态
        updateQueueModeButtons()
        // 更新区块副标题
        updateQueueSectionSubtitle()
        // 更新队列图标
        updateQueueIcon()

        // 滚动到当前歌曲
        queueLayoutManager?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)
    }

    private fun exitQueueMode() {
        isQueueMode = false

        // 隐藏歌曲头部信息
        binding.songHeaderContainer?.isVisible = false
        // 显示专辑封面
        binding.playerAlbumCoverFragment.isVisible = true
        // 隐藏队列容器
        binding.queueContainer?.isVisible = false
        // 显示控制栏中的歌曲信息
        controlsFragment.setSongInfoVisible(true)

        // 更新队列图标
        updateQueueIcon()

        // 释放拖拽资源
        releaseQueueResources()
    }

    private fun setupQueueRecyclerView() {
        val recyclerView = binding.queueRecyclerView ?: return

        recyclerViewTouchActionGuardManager = RecyclerViewTouchActionGuardManager()
        recyclerViewDragDropManager = RecyclerViewDragDropManager()

        playingQueueAdapter = PlayingQueueAdapter(
            requireActivity(),
            MusicPlayerRemote.playingQueue.toMutableList(),
            MusicPlayerRemote.position,
            R.layout.item_queue
        )
        wrappedAdapter = recyclerViewDragDropManager?.createWrappedAdapter(playingQueueAdapter!!)

        queueLayoutManager = LinearLayoutManager(requireContext())

        recyclerView.apply {
            layoutManager = queueLayoutManager
            adapter = wrappedAdapter
            itemAnimator = DraggableItemAnimator()
            recyclerViewTouchActionGuardManager?.attachRecyclerView(this)
            recyclerViewDragDropManager?.attachRecyclerView(this)
        }
    }

    private fun updateQueueModeButtons() {
        // Shuffle 按钮
        val shuffleMode = MusicPlayerRemote.shuffleMode
        val isShuffleOn = shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE
        binding.queueShuffleButton?.let { btn ->
            btn.text = getString(R.string.shuffle)
            btn.alpha = if (isShuffleOn) 1.0f else 0.5f
            btn.setOnClickListener {
                MusicPlayerRemote.toggleShuffleMode()
            }
        }

        // Repeat 按钮
        val repeatMode = MusicPlayerRemote.repeatMode
        binding.queueRepeatButton?.let { btn ->
            when (repeatMode) {
                MusicService.REPEAT_MODE_NONE -> {
                    btn.setIconResource(R.drawable.ic_repeat)
                    btn.text = getString(R.string.action_cycle_repeat)
                    btn.alpha = 0.5f
                }
                MusicService.REPEAT_MODE_ALL -> {
                    btn.setIconResource(R.drawable.ic_repeat)
                    btn.text = getString(R.string.action_cycle_repeat)
                    btn.alpha = 1.0f
                }
                MusicService.REPEAT_MODE_THIS -> {
                    btn.setIconResource(R.drawable.ic_repeat_one)
                    btn.text = getString(R.string.action_cycle_repeat)
                    btn.alpha = 1.0f
                }
            }
            btn.setOnClickListener {
                MusicPlayerRemote.cycleRepeatMode()
            }
        }

        // Autoplay 按钮
        binding.queueAutoplayButton?.let { btn ->
            btn.text = getString(R.string.action_toggle_autoplay)
            btn.alpha = 0.5f
            btn.setOnClickListener {
                // TODO: implement autoplay toggle
            }
        }
    }

    private fun updateQueueSectionSubtitle() {
        val queue = MusicPlayerRemote.playingQueue
        val position = MusicPlayerRemote.position
        val remaining = queue.size - position - 1
        val duration = MusicPlayerRemote.getQueueDurationMillis(position)
        binding.queueSectionSubtitle?.text = MusicUtil.buildInfoString(
            resources.getQuantityString(R.plurals.albumSongs, remaining, remaining),
            MusicUtil.getReadableDurationString(duration)
        )
    }

    private fun updateQueueIcon() {
        val icon = if (isQueueMode) {
            R.drawable.ic_queue_music
        } else {
            R.drawable.ic_queue_music
        }
        binding.actionQueue?.setImageDrawable(
            requireContext().getDrawable(icon)
        )
        // 用 alpha 来表示激活状态
        binding.actionQueue?.alpha = if (isQueueMode) 1.0f else 0.7f
    }

    private fun updateInlineQueue() {
        if (!isQueueMode) return
        playingQueueAdapter?.swapDataSet(
            MusicPlayerRemote.playingQueue,
            MusicPlayerRemote.position
        )
        updateQueueSectionSubtitle()
    }

    private fun updateInlineQueuePosition() {
        if (!isQueueMode) return
        playingQueueAdapter?.setCurrent(MusicPlayerRemote.position)
        binding.queueRecyclerView?.stopScroll()
        queueLayoutManager?.scrollToPositionWithOffset(MusicPlayerRemote.position + 1, 0)
        updateQueueSectionSubtitle()
    }

    private fun releaseQueueResources() {
        recyclerViewDragDropManager?.cancelDrag()
        recyclerViewDragDropManager?.release()
        recyclerViewDragDropManager = null

        recyclerViewTouchActionGuardManager = null

        if (wrappedAdapter != null) {
            WrapperAdapterUtils.releaseAll(wrappedAdapter)
            wrappedAdapter = null
        }
        playingQueueAdapter = null
        queueLayoutManager = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == SNOWFALL) {
            startOrStopSnow(PreferenceUtil.isSnowFalling)
        }
    }

    private fun startOrStopSnow(isSnowFalling: Boolean) {
        if (_binding == null) return
        if (isSnowFalling && !surfaceColor().isColorLight) {
            binding.snowfallView.isVisible = true
            binding.snowfallView.restartFalling()
        } else {
            binding.snowfallView.isVisible = false
            binding.snowfallView.stopFalling()
        }
    }

    override fun onServiceConnected() {
        updateIsFavorite()
        updateHeaderInfo()
        // 只有在歌词模式下才启动计时器
        if (isLyricsMode && MusicPlayerRemote.isPlaying) {
            startFullscreenTimer()
        }
        if (isQueueMode) {
            updateInlineQueue()
            updateQueueModeButtons()
        }
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
        updateHeaderInfo()
        if (isQueueMode) {
            updateInlineQueuePosition()
        }
    }

    override fun onQueueChanged() {
        super.onQueueChanged()
        if (isQueueMode) {
            updateInlineQueue()
        }
    }

    override fun onShuffleModeChanged() {
        super.onShuffleModeChanged()
        if (isQueueMode) {
            updateQueueModeButtons()
            updateInlineQueue()
        }
    }

    override fun onRepeatModeChanged() {
        super.onRepeatModeChanged()
        if (isQueueMode) {
            updateQueueModeButtons()
        }
    }

    override fun onPlayStateChanged() {
        super.onPlayStateChanged()
        // 只有在歌词模式下才处理计时器
        if (isLyricsMode) {
            if (MusicPlayerRemote.isPlaying) {
                startFullscreenTimer()
            } else {
                stopFullscreenTimer()
            }
        }
    }

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private fun goToAlbum(activity: android.app.Activity) {
        code.name.monkey.retromusic.fragments.base.goToAlbum(activity)
    }

    private fun goToArtist(activity: android.app.Activity) {
        code.name.monkey.retromusic.fragments.base.goToArtist(activity)
    }
}
