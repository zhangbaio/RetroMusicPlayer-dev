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

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.text.TextUtils
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.text.parseAsHtml
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.DialogFragment
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.EXTRA_SONG
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.saf.SAFGuideActivity
import code.name.monkey.retromusic.extensions.extraNotNull
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.SourceType
import code.name.monkey.retromusic.repository.WebDAVRepository
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.util.MusicUtil
import code.name.monkey.retromusic.util.SAFUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.getViewModel
import org.koin.core.context.GlobalContext

class DeleteSongsDialog : DialogFragment() {
    lateinit var libraryViewModel: LibraryViewModel

    // Activity Result Launchers for the new API
    private val safGuideResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Launch tree picker directly
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.content.extra.SHOW_ADVANCED", true)
            }
            safPickerResultLauncher.launch(intent)
        }
    }

    private val safPickerResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                val hostActivity = activity ?: return@let
                SAFUtil.saveTreeUri(hostActivity, data)
                val songs = extraNotNull<List<Song>>(EXTRA_SONG).value
                deleteSongsWithSaf(songs)
            }
        }
    }

    companion object {
        fun create(song: Song): DeleteSongsDialog {
            val list = ArrayList<Song>()
            list.add(song)
            return create(list)
        }

        fun create(songs: List<Song>): DeleteSongsDialog {
            return DeleteSongsDialog().apply {
                arguments = bundleOf(
                    EXTRA_SONG to ArrayList(songs)
                )
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        libraryViewModel = activity?.getViewModel() as LibraryViewModel
        val songs = extraNotNull<List<Song>>(EXTRA_SONG).value
        val hasWebDavSongs = songs.any(::isWebDavSong)
        if (VersionUtils.hasR() && !hasWebDavSongs) {
            val deleteResultLauncher =
                registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        if ((songs.size == 1) && MusicPlayerRemote.isPlaying(songs[0])) {
                            MusicPlayerRemote.playNextSong()
                        }
                        MusicPlayerRemote.removeFromQueue(songs)
                        reloadTabs()
                    }
                    dismiss()
                }
            val pendingIntent =
                MediaStore.createDeleteRequest(requireActivity().contentResolver, songs.map {
                    MusicUtil.getSongFileUri(it.id)
                })
            deleteResultLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
            return super.onCreateDialog(savedInstanceState)
        } else {
            val pair = if (songs.size > 1) {
                Pair(
                    R.string.delete_songs_title,
                    String.format(getString(R.string.delete_x_songs), songs.size).parseAsHtml()
                )
            } else {
                val safeSongTitle = TextUtils.htmlEncode(songs[0].title)
                Pair(
                    R.string.delete_song_title,
                    String.format(getString(R.string.delete_song_x), safeSongTitle).parseAsHtml()
                )
            }

            return materialDialog()
                .title(pair.first)
                .message(text = pair.second)
                .noAutoDismiss()
                .negativeButton(android.R.string.cancel)
                {
                    dismiss()
                }
                .positiveButton(R.string.action_delete)
                {
                    if ((songs.size == 1) && MusicPlayerRemote.isPlaying(songs[0])) {
                        MusicPlayerRemote.playNextSong()
                    }
                    val localSongs = songs.filterNot(::isWebDavSong)
                    if (!SAFUtil.isSAFRequiredForSongs(localSongs)) {
                        deleteSongs(songs)
                    } else {
                        if (SAFUtil.isSDCardAccessGranted(requireActivity())) {
                            deleteSongsWithSaf(songs)
                        } else {
                            safGuideResultLauncher.launch(
                                Intent(requireActivity(), SAFGuideActivity::class.java)
                            )
                        }
                    }
                }
        }
    }

    private fun deleteSongsWithSaf(songs: List<Song>) {
        deleteSongs(songs, useSafDelete = true)
    }

    private fun deleteSongs(
        songs: List<Song>,
        useSafDelete: Boolean = false
    ) {
        val hostActivity = activity as? FragmentActivity
        val appContext = context?.applicationContext ?: return
        CoroutineScope(Dispatchers.IO).launch {
            deleteSongsInternal(
                songs = songs,
                hostActivity = hostActivity,
                appContext = appContext,
                useSafDelete = useSafDelete
            )
            withContext(Dispatchers.Main) {
                dismissAllowingStateLoss()
                reloadTabs()
            }
        }
    }

    private suspend fun deleteSongsInternal(
        songs: List<Song>,
        hostActivity: FragmentActivity?,
        appContext: Context,
        useSafDelete: Boolean = false
    ) {
        val localSongs = songs.filterNot(::isWebDavSong)
        val webDavSongs = songs.filter(::isWebDavSong)

        if (localSongs.isNotEmpty()) {
            if (useSafDelete && hostActivity != null) {
                MusicUtil.deleteTracks(hostActivity, localSongs, null, null)
            } else {
                MusicUtil.deleteTracks(appContext, localSongs)
            }
        }

        if (webDavSongs.isNotEmpty()) {
            val webDavRepository = GlobalContext.get().get<WebDAVRepository>()
            webDavRepository.deleteSongsByIds(webDavSongs.map { it.id })
            MusicPlayerRemote.removeFromQueue(webDavSongs)
            if (localSongs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    appContext.showToast(
                        appContext.getString(R.string.deleted_x_songs, webDavSongs.size)
                    )
                }
            }
        }
    }

    private fun isWebDavSong(song: Song): Boolean = song.sourceType == SourceType.WEBDAV

    private fun reloadTabs() {
        libraryViewModel.forceReload(ReloadType.Songs)
        libraryViewModel.forceReload(ReloadType.HomeSections)
        libraryViewModel.forceReload(ReloadType.Artists)
        libraryViewModel.forceReload(ReloadType.Albums)
        libraryViewModel.forceReload(ReloadType.PlayCount)
    }
}
