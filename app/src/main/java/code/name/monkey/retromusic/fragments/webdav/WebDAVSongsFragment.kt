/*
 * Copyright (c) 2025 Hemanth Savarla.
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
package code.name.monkey.retromusic.fragments.webdav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.song.SongAdapter
import code.name.monkey.retromusic.activities.MainActivity
import code.name.monkey.retromusic.databinding.FragmentWebdavSongsBinding
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.WebDAVConfig
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Fragment for displaying and playing WebDAV songs
 */
class WebDAVSongsFragment : Fragment() {

    private val viewModel: WebDAVViewModel by viewModel()
    private var _binding: FragmentWebdavSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SongAdapter
    private var configs: List<WebDAVConfig> = emptyList()
    private var selectedConfigId: Long? = null

    val mainActivity: MainActivity
        get() = activity as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebdavSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainActivity.setBottomNavVisibility(true)
        mainActivity.supportActionBar?.setTitle(R.string.webdav_songs)

        setupRecyclerView()
        setupServerSpinner()
        setupSwipeRefresh()
        observeViewModel()

        // Load configs
        viewModel.loadConfigs()
    }

    private fun setupRecyclerView() {
        adapter = SongAdapter(
            requireActivity(),
            mutableListOf(),
            R.layout.item_list
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupServerSpinner() {
        binding.serverSpinner.isEnabled = false
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            selectedConfigId?.let { configId ->
                viewModel.syncConfig(configId)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.configs.observe(viewLifecycleOwner) { configList ->
            android.util.Log.d(TAG, "configs observer: received ${configList.size} configs")
            configs = configList
            updateServerSpinner()

            // Auto-select first config if none selected
            if (selectedConfigId == null && configList.isNotEmpty()) {
                selectedConfigId = configList.firstOrNull { it.isEnabled }?.id
                android.util.Log.d(TAG, "Auto-selected config id: $selectedConfigId")
            }
            // Always load songs when configs are updated
            android.util.Log.d(TAG, "Loading songs for configId: $selectedConfigId")
            viewModel.loadSongs(selectedConfigId)
        }

        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            android.util.Log.d(TAG, "songs observer: received ${songs.size} songs")
            adapter.swapDataSet(songs)
            binding.emptyView.isVisible = songs.isEmpty()
            binding.swipeRefreshLayout.isRefreshing = false
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is WebDAVUiState.Idle -> {
                    binding.progressBar.isVisible = false
                    binding.swipeRefreshLayout.isRefreshing = false
                }
                is WebDAVUiState.Syncing -> {
                    binding.progressBar.isVisible = true
                    binding.swipeRefreshLayout.isRefreshing = true
                }
                is WebDAVUiState.SyncComplete -> {
                    binding.progressBar.isVisible = false
                    binding.swipeRefreshLayout.isRefreshing = false
                    // Reload songs after sync
                    selectedConfigId?.let { viewModel.loadSongs(it) }
                }
                is WebDAVUiState.Error -> {
                    binding.progressBar.isVisible = false
                    binding.swipeRefreshLayout.isRefreshing = false
                }
                else -> {}
            }
        }
    }

    private fun updateServerSpinner() {
        val enabledConfigs = configs.filter { it.isEnabled }

        if (enabledConfigs.isNotEmpty()) {
            val items = enabledConfigs.map { it.name }.toMutableList()
            items.add(0, "All Servers")

            val spinnerAdapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                items
            )
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.serverSpinner.adapter = spinnerAdapter
            binding.serverSpinner.isEnabled = true

            binding.serverSpinner.setOnItemSelectedListener(null)
            binding.serverSpinner.setSelection(
                if (selectedConfigId == null) 0
                else enabledConfigs.indexOfFirst { it.id == selectedConfigId } + 1
            )

            binding.serverSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        selectedConfigId = null
                        viewModel.loadSongs(null)
                    } else {
                        selectedConfigId = enabledConfigs[position - 1].id
                        viewModel.loadSongs(selectedConfigId!!)
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
        } else {
            binding.serverSpinner.isEnabled = false
            binding.emptyView.isVisible = true
            binding.emptyView.text = "No WebDAV servers configured.\nGo to Settings > WebDAV to add a server."
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload configs (which will trigger loadSongs in the observer)
        viewModel.loadConfigs()
    }

    companion object {
        const val TAG = "WebDAVSongsFragment"
        fun newInstance() = WebDAVSongsFragment()
    }
}
