package code.name.monkey.retromusic.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.databinding.ItemSearchSongGridBinding
import code.name.monkey.retromusic.databinding.ItemSearchSongPlaceholderBinding
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSongs
import code.name.monkey.retromusic.glide.RetroGlideExtension.playlistOptions
import code.name.monkey.retromusic.glide.playlistPreview.PlaylistPreview
import code.name.monkey.retromusic.util.MusicUtil
import com.bumptech.glide.Glide

class SearchPlaylistGridAdapter(
    private val activity: FragmentActivity,
    private val onPlaylistClick: (PlaylistWithSongs) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class GridItem {
        data class PlaylistItem(val playlist: PlaylistWithSongs) : GridItem()
        data object Placeholder : GridItem()
    }

    private var items: List<GridItem> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submitPlaylists(playlists: List<PlaylistWithSongs>) {
        val playlistItems = playlists.map { GridItem.PlaylistItem(it) }
        items = if (playlists.isNotEmpty() && playlists.size % 2 == 1) {
            playlistItems + GridItem.Placeholder
        } else {
            playlistItems
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long {
        return when (val item = items[position]) {
            is GridItem.PlaylistItem -> item.playlist.playlistEntity.playListId
            GridItem.Placeholder -> Long.MIN_VALUE
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is GridItem.PlaylistItem -> VIEW_TYPE_PLAYLIST
            GridItem.Placeholder -> VIEW_TYPE_PLACEHOLDER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_PLAYLIST) {
            PlaylistViewHolder(
                ItemSearchSongGridBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        } else {
            PlaceholderViewHolder(
                ItemSearchSongPlaceholderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PlaylistViewHolder -> holder.bind((items[position] as GridItem.PlaylistItem).playlist)
            is PlaceholderViewHolder -> Unit
        }
    }

    private inner class PlaylistViewHolder(
        private val binding: ItemSearchSongGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(playlist: PlaylistWithSongs) {
            binding.title.text = playlist.playlistEntity.playlistName.ifEmpty { "-" }
            binding.subtitle.text = MusicUtil.getPlaylistInfoString(activity, playlist.songs.toSongs())
            Glide.with(activity)
                .load(PlaylistPreview(playlist))
                .playlistOptions()
                .into(binding.image)
            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }
        }
    }

    private class PlaceholderViewHolder(
        binding: ItemSearchSongPlaceholderBinding
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_PLAYLIST = 1
        private const val VIEW_TYPE_PLACEHOLDER = 2
    }
}
