package code.name.monkey.retromusic.fragments.search

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import code.name.monkey.retromusic.EXTRA_PLAYLIST_ID
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.SearchPlaylistGridAdapter
import code.name.monkey.retromusic.databinding.FragmentSearchTabBinding
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.ImportPlaylistDialog
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.interfaces.IScrollHelper

class SearchTabFragment : AbsMainActivityFragment(R.layout.fragment_search_tab), IScrollHelper {

    private var _binding: FragmentSearchTabBinding? = null
    private val binding get() = _binding!!
    private var playlistGridAdapter: SearchPlaylistGridAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSearchTabBinding.bind(view)

        mainActivity.setBottomNavVisibility(true)
        mainActivity.setSupportActionBar(binding.appBarLayout.toolbar)
        binding.appBarLayout.title = getString(R.string.action_search)
        binding.appBarLayout.toolbar.isTitleCentered = false
        binding.appBarLayout.toolbar.navigationIcon = null

        setupSearchBar()
        setupPlaylistGrid()
        observePlaylists()
    }

    private fun setupSearchBar() {
        binding.searchEntry.setOnClickListener {
            openSearch()
        }
    }

    private fun setupPlaylistGrid() {
        playlistGridAdapter = SearchPlaylistGridAdapter(requireActivity()) { playlist ->
            findNavController().navigate(
                R.id.playlistDetailsFragment,
                bundleOf(EXTRA_PLAYLIST_ID to playlist.playlistEntity.playListId)
            )
        }
        binding.songsRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = playlistGridAdapter
            setHasFixedSize(false)
        }
    }

    private fun observePlaylists() {
        libraryViewModel.getPlaylists().observe(viewLifecycleOwner) { playlists ->
            playlistGridAdapter?.submitPlaylists(playlists)
            binding.emptyText.isVisible = playlists.isEmpty()
            binding.songsRecycler.isVisible = playlists.isNotEmpty()
        }
    }

    private fun openSearch(query: String? = null) {
        val args = query?.takeIf { it.isNotBlank() }?.let {
            bundleOf(SearchFragment.QUERY to it)
        }
        findNavController().navigate(R.id.action_search, args, navOptions)
    }

    override fun scrollToTop() {
        binding.songsRecycler.scrollToPosition(0)
        binding.appBarLayout.setExpanded(true, true)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.appBarLayout.toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(binding.appBarLayout.toolbar)
        )
    }

    override fun onPrepareMenu(menu: Menu) {
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(
            requireActivity(),
            binding.appBarLayout.toolbar
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> findNavController().navigate(
                R.id.settings_fragment,
                null,
                navOptions
            )

            R.id.action_import_playlist -> ImportPlaylistDialog().show(
                childFragmentManager,
                "ImportPlaylist"
            )

            R.id.action_add_to_playlist -> CreatePlaylistDialog.create(emptyList()).show(
                childFragmentManager,
                "ShowCreatePlaylistDialog"
            )
        }
        return false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        playlistGridAdapter = null
        _binding = null
    }
}
