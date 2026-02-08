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

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentPlayerPlaybackControlsBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsPlayerControlsFragment
import code.name.monkey.retromusic.fragments.base.goToArtist
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.google.android.material.slider.Slider

class PlayerPlaybackControlsFragment :
    AbsPlayerControlsFragment(R.layout.fragment_player_playback_controls) {

    private var _binding: FragmentPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!
    private val progressSliderLayoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        alignToProgressTrack()
    }

    private var isFavorite = false

    override val progressSlider: Slider
        get() = binding.progressSlider

    override val shuffleButton: ImageButton
        get() = binding.shuffleButton

    override val repeatButton: ImageButton
        get() = binding.repeatButton

    override val nextButton: ImageButton
        get() = binding.nextButton

    override val previousButton: ImageButton
        get() = binding.previousButton

    override val songTotalTime: TextView
        get() = binding.songTotalTime

    override val songCurrentProgress: TextView
        get() = binding.songCurrentProgress

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerPlaybackControlsBinding.bind(view)

        setUpPlayPauseFab()
        setUpSongInfo()
        applyWhiteTextColors()
        applyWhiteIconColors()
        binding.progressSlider.addOnLayoutChangeListener(progressSliderLayoutChangeListener)
        binding.root.post { alignToProgressTrack() }
    }

    private fun setUpSongInfo() {
        binding.title.isSelected = true
        binding.text.isSelected = true
        binding.text.setOnClickListener {
            goToArtist(requireActivity())
        }

        binding.songFavourite.setOnClickListener {
            // 调用父 Fragment 的 toggleFavorite 方法
            (parentFragment as? PlayerFragment)?.onFavoriteToggled()
        }

        binding.songMenu.setOnClickListener { view ->
            showSongMenu(view)
        }
    }

    fun updateFavoriteIcon(isFav: Boolean) {
        isFavorite = isFav
        val icon = if (isFavorite) {
            R.drawable.ic_favorite
        } else {
            R.drawable.ic_favorite_border
        }
        binding.songFavourite.setImageDrawable(
            requireContext().getDrawable(icon)
        )
        binding.songFavourite.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun showSongMenu(view: View) {
        val popupMenu = PopupMenu(requireContext(), view)
        popupMenu.menuInflater.inflate(R.menu.menu_song_detail, popupMenu.menu)
        popupMenu.setOnMenuItemClickListener { item ->
            (parentFragment as? PlayerFragment)?.onMenuItemClick(item) ?: false
        }
        popupMenu.show()
    }

    fun updateSongInfo() {
        val song = MusicPlayerRemote.currentSong
        binding.title.text = song.displayTitle
        binding.text.text = song.displayArtistName
    }

    fun setSongInfoVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.title.visibility = visibility
        binding.text.visibility = visibility
        binding.songFavourite.visibility = visibility
        binding.songMenu.visibility = visibility
    }

    override fun setColor(color: MediaNotificationProcessor) {
        val white = Color.WHITE
        lastPlaybackControlsColor = white
        lastDisabledPlaybackControlsColor = white

        // Tint playback control icons
        binding.playPauseButton.setColorFilter(lastPlaybackControlsColor)
        binding.previousButton.setColorFilter(lastPlaybackControlsColor)
        binding.nextButton.setColorFilter(lastPlaybackControlsColor)
        binding.progressSlider.applyColor(white)
        applyWhiteIconColors()
        applyWhiteTextColors()
        volumeFragment?.setTintable(white)
        updateRepeatState()
        updateShuffleState()
        updatePrevNextColor()
    }

    private fun applyWhiteIconColors() {
        val whiteTint = ColorStateList.valueOf(Color.WHITE)
        binding.songFavourite.imageTintList = whiteTint
        binding.songMenu.imageTintList = whiteTint
    }

    private fun applyWhiteTextColors() {
        binding.title.setTextColor(Color.WHITE)
        binding.text.setTextColor(Color.WHITE)
        binding.songInfo.setTextColor(Color.WHITE)
        binding.songCurrentProgress.setTextColor(Color.WHITE)
        binding.songTotalTime.setTextColor(Color.WHITE)
    }

    override fun onServiceConnected() {
        updatePlayPauseDrawableState()
        updateRepeatState()
        updateShuffleState()
        updateSongInfo()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateSongInfo()
    }

    override fun onPlayStateChanged() {
        updatePlayPauseDrawableState()
    }

    override fun onRepeatModeChanged() {
        updateRepeatState()
    }

    override fun onShuffleModeChanged() {
        updateShuffleState()
    }

    private fun setUpPlayPauseFab() {
        binding.playPauseButton.setOnClickListener {
            if (MusicPlayerRemote.isPlaying) {
                MusicPlayerRemote.pauseSong()
            } else {
                MusicPlayerRemote.resumePlaying()
            }
            it.showBounceAnimation()
        }

        binding.previousButton.setOnClickListener {
            MusicPlayerRemote.playPreviousSong()
        }

        binding.nextButton.setOnClickListener {
            MusicPlayerRemote.playNextSong()
        }
    }

    private fun updatePlayPauseDrawableState() {
        if (MusicPlayerRemote.isPlaying) {
            binding.playPauseButton.setImageResource(R.drawable.ic_pause_large)
        } else {
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow_large)
        }
    }

    public override fun show() {
        binding.playPauseButton.animate()
            .scaleX(1f)
            .scaleY(1f)
            .rotation(360f)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    public override fun hide() {
        binding.playPauseButton.apply {
            scaleX = 0f
            scaleY = 0f
            rotation = 0f
        }
    }

    private fun alignToProgressTrack() {
        val binding = _binding ?: return
        val slider = binding.progressSlider
        val rootWidth = binding.root.width
        if (rootWidth <= 0 || slider.width <= 0) return

        val trackStart = slider.left + slider.trackSidePadding
        val trackEnd = slider.right - slider.trackSidePadding
        if (trackEnd <= trackStart) return

        val constrainedStart = trackStart.coerceAtLeast(0)
        val constrainedEnd = (rootWidth - trackEnd).coerceAtLeast(0)
        val startChanged = updateGuideBegin(binding.progressStartGuide, constrainedStart)
        val endChanged = updateGuideEnd(binding.progressEndGuide, constrainedEnd)
        if (startChanged || endChanged) {
            binding.root.requestLayout()
        }
    }

    private fun updateGuideBegin(guideline: View, begin: Int): Boolean {
        val params = guideline.layoutParams as ConstraintLayout.LayoutParams
        if (params.guideBegin == begin && params.guideEnd == -1 && params.guidePercent == -1f) {
            return false
        }
        params.guideBegin = begin
        params.guideEnd = -1
        params.guidePercent = -1f
        guideline.layoutParams = params
        return true
    }

    private fun updateGuideEnd(guideline: View, end: Int): Boolean {
        val params = guideline.layoutParams as ConstraintLayout.LayoutParams
        if (params.guideEnd == end && params.guideBegin == -1 && params.guidePercent == -1f) {
            return false
        }
        params.guideEnd = end
        params.guideBegin = -1
        params.guidePercent = -1f
        guideline.layoutParams = params
        return true
    }

    override fun onDestroyView() {
        _binding?.progressSlider?.removeOnLayoutChangeListener(progressSliderLayoutChangeListener)
        super.onDestroyView()
        _binding = null
    }
}
