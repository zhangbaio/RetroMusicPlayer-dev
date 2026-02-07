package code.name.monkey.retromusic.adapter.song

import android.view.MenuItem
import android.view.View
import androidx.fragment.app.FragmentActivity
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.db.toSongEntity
import code.name.monkey.retromusic.db.toSongsEntity
import code.name.monkey.retromusic.dialogs.RemoveSongFromPlaylistDialog
import code.name.monkey.retromusic.model.Song

class FavoriteSongAdapter(
    private val playlistId: Long,
    activity: FragmentActivity,
    dataSet: MutableList<Song>,
    itemLayoutRes: Int
) : SongAdapter(activity, dataSet, itemLayoutRes) {

    init {
        setMultiSelectMenuRes(R.menu.menu_favorites_songs_selection)
    }

    override fun createViewHolder(view: View): ViewHolder = ViewHolder(view)

    override fun onMultipleItemAction(menuItem: MenuItem, selection: List<Song>) {
        when (menuItem.itemId) {
            R.id.action_remove_from_playlist -> {
                RemoveSongFromPlaylistDialog.create(
                    selection.toSongsEntity(playlistId),
                    isFavoritesPlaylist = true
                ).show(activity.supportFragmentManager, "REMOVE_FROM_FAVORITES")
            }

            else -> super.onMultipleItemAction(menuItem, selection)
        }
    }

    inner class ViewHolder(itemView: View) : SongAdapter.ViewHolder(itemView) {

        override var songMenuRes: Int
            get() = R.menu.menu_item_favorite_song
            set(value) {
                super.songMenuRes = value
            }

        override fun onSongMenuItemClick(item: MenuItem): Boolean {
            if (item.itemId == R.id.action_remove_from_playlist) {
                RemoveSongFromPlaylistDialog.create(
                    song.toSongEntity(playlistId),
                    isFavoritesPlaylist = true
                ).show(activity.supportFragmentManager, "REMOVE_FROM_FAVORITES")
                return true
            }
            return super.onSongMenuItemClick(item)
        }
    }
}
