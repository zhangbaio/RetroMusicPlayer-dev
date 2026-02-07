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
package code.name.monkey.retromusic.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.fragment.app.DialogFragment
import code.name.monkey.retromusic.EXTRA_SONG
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.SongEntity
import code.name.monkey.retromusic.extensions.colorButtons
import code.name.monkey.retromusic.extensions.extraNotNull
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.fragments.LibraryViewModel
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class RemoveSongFromPlaylistDialog : DialogFragment() {
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    companion object {
        private const val EXTRA_IS_FAVORITES_PLAYLIST = "extra_is_favorites_playlist"

        fun create(
            song: SongEntity,
            isFavoritesPlaylist: Boolean = false
        ): RemoveSongFromPlaylistDialog {
            val list = mutableListOf<SongEntity>()
            list.add(song)
            return create(list, isFavoritesPlaylist)
        }

        fun create(
            songs: List<SongEntity>,
            isFavoritesPlaylist: Boolean = false
        ): RemoveSongFromPlaylistDialog {
            return RemoveSongFromPlaylistDialog().apply {
                arguments = bundleOf(
                    EXTRA_SONG to songs,
                    EXTRA_IS_FAVORITES_PLAYLIST to isFavoritesPlaylist
                )
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val songs = extraNotNull<List<SongEntity>>(EXTRA_SONG).value
        val isFavoritesPlaylist = arguments?.getBoolean(EXTRA_IS_FAVORITES_PLAYLIST, false) ?: false
        val pair = if (isFavoritesPlaylist) {
            if (songs.size > 1) {
                Pair(
                    R.string.remove_songs_from_favorites_title,
                    String.format(getString(R.string.remove_x_songs_from_favorites), songs.size)
                        .parseAsHtml()
                )
            } else {
                val safeSongTitle = TextUtils.htmlEncode(songs[0].title)
                Pair(
                    R.string.remove_song_from_favorites_title,
                    String.format(
                        getString(R.string.remove_song_x_from_favorites),
                        safeSongTitle
                    ).parseAsHtml()
                )
            }
        } else {
            if (songs.size > 1) {
                Pair(
                    R.string.remove_songs_from_playlist_title,
                    String.format(getString(R.string.remove_x_songs_from_playlist), songs.size)
                        .parseAsHtml()
                )
            } else {
                val safeSongTitle = TextUtils.htmlEncode(songs[0].title)
                Pair(
                    R.string.remove_song_from_playlist_title,
                    String.format(
                        getString(R.string.remove_song_x_from_playlist),
                        safeSongTitle
                    ).parseAsHtml()
                )
            }
        }
        return materialDialog(pair.first)
            .setMessage(pair.second)
            .setPositiveButton(R.string.remove_action) { _, _ ->
                libraryViewModel.deleteSongsInPlaylist(songs)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .colorButtons()
    }
}
