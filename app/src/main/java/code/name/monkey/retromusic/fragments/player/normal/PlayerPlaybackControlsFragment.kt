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

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import code.name.monkey.appthemehelper.util.ATHUtil
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import code.name.monkey.appthemehelper.util.TintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentPlayerPlaybackControlsBinding
import code.name.monkey.retromusic.dialogs.AddToPlaylistDialog
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.DeleteSongsDialog
import code.name.monkey.retromusic.dialogs.PlaybackSpeedDialog
import code.name.monkey.retromusic.dialogs.SleepTimerDialog
import code.name.monkey.retromusic.dialogs.SongDetailDialog
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsPlayerControlsFragment
import code.name.monkey.retromusic.fragments.base.goToAlbum
import code.name.monkey.retromusic.fragments.base.goToArtist
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.repository.RealRepository
import code.name.monkey.retromusic.repository.Repository
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.NavigationUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.RingtoneManager
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class PlayerPlaybackControlsFragment :
    AbsPlayerControlsFragment(R.layout.fragment_player_playback_controls) {

    private var _binding: FragmentPlayerPlaybackControlsBinding? = null
    private val binding get() = _binding!!

    private val repository: Repository by inject()
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
        binding.title.isSelected = true
        binding.text.isSelected = true
        binding.title.setOnClickListener {
            goToAlbum(requireActivity())
        }
        binding.text.setOnClickListener {
            goToArtist(requireActivity())
        }

        // Setup favorite button
        binding.songFavourite.setOnClickListener {
            toggleFavorite()
        }

        // Setup menu button
        binding.songMenu.setOnClickListener { view ->
            showSongMenu(view)
        }
    }

    override fun setColor(color: MediaNotificationProcessor) {
        val colorBg = ATHUtil.resolveColor(requireContext(), android.R.attr.colorBackground)
        if (ColorUtil.isColorLight(colorBg)) {
            lastPlaybackControlsColor =
                MaterialValueHelper.getSecondaryTextColor(requireContext(), true)
            lastDisabledPlaybackControlsColor =
                MaterialValueHelper.getSecondaryDisabledTextColor(requireContext(), true)
        } else {
            lastPlaybackControlsColor =
                MaterialValueHelper.getPrimaryTextColor(requireContext(), false)
            lastDisabledPlaybackControlsColor =
                MaterialValueHelper.getPrimaryDisabledTextColor(requireContext(), false)
        }

        val colorFinal = if (PreferenceUtil.isAdaptiveColor) {
            color.primaryTextColor
        } else {
            accentColor()
        }.ripAlpha()

        // Tint playback control icons
        binding.playPauseButton.setColorFilter(lastPlaybackControlsColor)
        binding.previousButton.setColorFilter(lastPlaybackControlsColor)
        binding.nextButton.setColorFilter(lastPlaybackControlsColor)
        binding.progressSlider.applyColor(colorFinal)
        volumeFragment?.setTintable(colorFinal)
        updateRepeatState()
        updateShuffleState()
        updatePrevNextColor()
    }

    private fun updateSong() {
        val song = MusicPlayerRemote.currentSong
        binding.title.text = song.title
        binding.text.text = song.artistName

        if (PreferenceUtil.isSongInfo) {
            binding.songInfo.text = getSongInfo(song)
            binding.songInfo.show()
        } else {
            binding.songInfo.hide()
        }
    }


    override fun onServiceConnected() {
        updatePlayPauseDrawableState()
        updateRepeatState()
        updateShuffleState()
        updateSong()
        updateFavoriteState()
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        updateSong()
        updateFavoriteState()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleFavorite() {
        val song = MusicPlayerRemote.currentSong
        lifecycleScope.launch(Dispatchers.IO) {
            MusicUtil.toggleFavorite(song)
            val newFavoriteState = MusicUtil.isFavorite(song)
            withContext(Dispatchers.Main) {
                isFavorite = newFavoriteState
                updateFavoriteIcon()
            }
        }
    }

    private fun updateFavoriteState() {
        val song = MusicPlayerRemote.currentSong
        lifecycleScope.launch(Dispatchers.IO) {
            val favorite = MusicUtil.isFavorite(song)
            withContext(Dispatchers.Main) {
                isFavorite = favorite
                updateFavoriteIcon()
            }
        }
    }

    private fun updateFavoriteIcon() {
        val icon = if (isFavorite) {
            R.drawable.ic_favorite
        } else {
            R.drawable.ic_favorite_border
        }
        binding.songFavourite.setImageResource(icon)
    }

    private fun showSongMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_song_detail, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val song = MusicPlayerRemote.currentSong
            when (item.itemId) {
                R.id.action_add_to_playlist -> {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val playlists = get<RealRepository>().fetchPlaylists()
                        withContext(Dispatchers.Main) {
                            AddToPlaylistDialog.create(playlists, song)
                                .show(childFragmentManager, "ADD_PLAYLIST")
                        }
                    }
                    true
                }
                R.id.action_share -> {
                    requireActivity().startActivity(
                        Intent.createChooser(
                            MusicUtil.createShareSongFileIntent(requireContext(), song),
                            null
                        )
                    )
                    true
                }
                R.id.action_go_to_album -> {
                    goToAlbum(requireActivity())
                    true
                }
                R.id.action_go_to_artist -> {
                    goToArtist(requireActivity())
                    true
                }
                R.id.action_playback_speed -> {
                    PlaybackSpeedDialog.newInstance().show(childFragmentManager, "PLAYBACK_SETTINGS")
                    true
                }
                R.id.action_sleep_timer -> {
                    SleepTimerDialog().show(parentFragmentManager, "SLEEP_TIMER")
                    true
                }
                R.id.action_equalizer -> {
                    NavigationUtil.openEqualizer(requireActivity())
                    true
                }
                R.id.action_go_to_drive_mode -> {
                    NavigationUtil.gotoDriveMode(requireActivity())
                    true
                }
                R.id.action_save_playing_queue -> {
                    CreatePlaylistDialog.create(ArrayList(MusicPlayerRemote.playingQueue))
                        .show(childFragmentManager, "ADD_TO_PLAYLIST")
                    true
                }
                R.id.action_set_as_ringtone -> {
                    requireContext().run {
                        if (RingtoneManager.requiresDialog(this)) {
                            RingtoneManager.showDialog(this)
                        } else {
                            RingtoneManager.setRingtone(this, song)
                        }
                    }
                    true
                }
                R.id.action_details -> {
                    SongDetailDialog.create(song).show(childFragmentManager, "SONG_DETAIL")
                    true
                }
                R.id.action_clear_playing_queue -> {
                    MusicPlayerRemote.clearQueue()
                    true
                }
                R.id.action_delete_from_device -> {
                    DeleteSongsDialog.create(song).show(childFragmentManager, "DELETE_SONGS")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
