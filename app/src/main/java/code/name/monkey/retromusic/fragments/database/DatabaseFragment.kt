package code.name.monkey.retromusic.fragments.database

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.EXTRA_ALBUM_ID
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.RECENT_ALBUMS
import code.name.monkey.retromusic.adapter.album.AlbumAdapter
import code.name.monkey.retromusic.databinding.FragmentDatabaseBinding
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.interfaces.IAlbumClickListener
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.model.CategoryInfo
import code.name.monkey.retromusic.util.PreferenceUtil

class DatabaseFragment : AbsMainActivityFragment(R.layout.fragment_database), IAlbumClickListener,
    IScrollHelper {

    private var _binding: FragmentDatabaseBinding? = null
    private val binding get() = _binding!!

    private var recentAlbumsAdapter: AlbumAdapter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDatabaseBinding.bind(view)

        mainActivity.setBottomNavVisibility(true)
        mainActivity.setSupportActionBar(binding.appBarLayout.toolbar)
        binding.appBarLayout.title = getString(R.string.database)
        binding.appBarLayout.toolbar.isTitleCentered = false
        binding.appBarLayout.toolbar.navigationIcon = null

        setupEntries()
        childFragmentManager.setFragmentResultListener(
            DatabaseCategoryPreferenceDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(DatabaseCategoryPreferenceDialog.RESULT_UPDATED, false)) {
                applyDatabaseCategoryVisibility()
            }
        }
        applyDatabaseCategoryVisibility()
        setupRecentAlbums()
    }

    private fun setupEntries() {
        binding.entryPlaylists.setOnClickListener {
            findNavController().navigate(R.id.action_playlist, null, navOptions)
        }
        binding.entryArtists.setOnClickListener {
            findNavController().navigate(R.id.action_artist, null, navOptions)
        }
        binding.entryAlbums.setOnClickListener {
            findNavController().navigate(R.id.action_album, null, navOptions)
        }
        binding.entrySongs.setOnClickListener {
            val webTabItem = mainActivity.navigationView.menu.findItem(R.id.action_web)
            if (webTabItem != null && webTabItem.isVisible) {
                mainActivity.navigationView.selectedItemId = R.id.action_web
            } else {
                findNavController().navigate(R.id.action_web, null, navOptions)
            }
        }
    }

    private fun setupRecentAlbums() {
        recentAlbumsAdapter = AlbumAdapter(
            requireActivity(),
            emptyList(),
            R.layout.item_database_album,
            this
        )
        binding.recentAlbumsRecycler.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = recentAlbumsAdapter
            setHasFixedSize(false)
        }

        libraryViewModel.albums(RECENT_ALBUMS).observe(viewLifecycleOwner) { albums ->
            recentAlbumsAdapter?.swapDataSet(albums)
            binding.recentEmptyText.isVisible = albums.isEmpty()
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_database, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.appBarLayout.toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(binding.appBarLayout.toolbar)
        )
    }

    override fun onPrepareMenu(menu: Menu) {
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), binding.appBarLayout.toolbar)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit_database -> {
                DatabaseCategoryPreferenceDialog.newInstance()
                    .show(childFragmentManager, "DatabaseCategoryPreferenceDialog")
                return true
            }

            R.id.action_settings -> {
                findNavController().navigate(R.id.settings_fragment, null, navOptions)
                return true
            }
        }
        return false
    }

    override fun onAlbumClick(albumId: Long, view: View) {
        findNavController().navigate(
            R.id.albumDetailsFragment,
            bundleOf(EXTRA_ALBUM_ID to albumId),
            null,
            FragmentNavigatorExtras(view to albumId.toString())
        )
    }

    override fun scrollToTop() {
        binding.contentScroll.smoothScrollTo(0, 0)
        binding.appBarLayout.setExpanded(true, true)
    }

    private fun applyDatabaseCategoryVisibility() {
        val configuredCategories = PreferenceUtil.databaseCategory
        val visibleCategories = configuredCategories
            .filter { it.visible }
            .map { it.category }

        val entryMap = mapOf(
            CategoryInfo.Category.Playlists to binding.entryPlaylists,
            CategoryInfo.Category.Artists to binding.entryArtists,
            CategoryInfo.Category.Albums to binding.entryAlbums,
            CategoryInfo.Category.Songs to binding.entrySongs
        )
        val dividerMap = mapOf(
            CategoryInfo.Category.Playlists to binding.dividerPlaylists,
            CategoryInfo.Category.Artists to binding.dividerArtists,
            CategoryInfo.Category.Albums to binding.dividerAlbums,
            CategoryInfo.Category.Songs to binding.dividerSongs
        )

        entryMap.values.forEach { it.isVisible = false }
        dividerMap.values.forEach { it.isVisible = false }
        binding.entriesContainer.removeAllViews()

        visibleCategories.forEachIndexed { index, category ->
            val entry = entryMap[category] ?: return@forEachIndexed
            entry.isVisible = true
            binding.entriesContainer.addView(entry)

            if (index < visibleCategories.lastIndex) {
                val divider = dividerMap[category] ?: return@forEachIndexed
                divider.isVisible = true
                binding.entriesContainer.addView(divider)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyDatabaseCategoryVisibility()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        recentAlbumsAdapter = null
    }
}
