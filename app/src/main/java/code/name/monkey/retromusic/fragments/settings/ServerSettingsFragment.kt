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
package code.name.monkey.retromusic.fragments.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.base.AbsSlidingMusicPanelActivity
import code.name.monkey.retromusic.adapters.ServerConfigAdapter
import code.name.monkey.retromusic.databinding.DialogServerConfigBinding
import code.name.monkey.retromusic.databinding.FragmentServerSettingsBinding
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.fragments.server.ServerUiState
import code.name.monkey.retromusic.fragments.server.ServerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Fragment for managing music server configurations
 */
class ServerSettingsFragment : Fragment(),
    ServerConfigAdapter.Callback {

    private val viewModel: ServerViewModel by viewModel()

    private var _binding: FragmentServerSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ServerConfigAdapter
    private var configDialog: Dialog? = null
    private var editingConfig: ServerConfig? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        (activity as? AbsSlidingMusicPanelActivity)?.hideBottomSheet(hide = false)
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? AbsSlidingMusicPanelActivity)?.hideBottomSheet(hide = true)

        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ServerConfigAdapter(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupFab() {
        binding.addConfigFab.setOnClickListener {
            showConfigDialog()
        }
    }

    private fun observeViewModel() {
        viewModel.configs.observe(viewLifecycleOwner) { configs ->
            adapter.submitList(configs)
            binding.emptyView.isVisible = configs.isEmpty()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ServerUiState.Idle -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                }
                is ServerUiState.Syncing -> {
                    binding.progressBar.isVisible = true
                    binding.syncStatusText.isVisible = true
                    binding.syncStatusText.text = getString(R.string.server_syncing)
                }
                is ServerUiState.SyncComplete -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                    requireContext().showToast(
                        getString(R.string.server_sync_complete, state.count),
                        Toast.LENGTH_SHORT
                    )
                    viewModel.loadConfigs()
                }
                is ServerUiState.ScanTriggered -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                    requireContext().showToast(
                        getString(R.string.server_scan_triggered),
                        Toast.LENGTH_SHORT
                    )
                }
                is ServerUiState.ConfigSaved -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                    requireContext().showToast(
                        getString(R.string.server_config_saved),
                        Toast.LENGTH_SHORT
                    )
                }
                is ServerUiState.ConfigDeleted -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                    requireContext().showToast(
                        getString(R.string.server_config_deleted),
                        Toast.LENGTH_SHORT
                    )
                }
                is ServerUiState.Error -> {
                    binding.progressBar.isVisible = false
                    binding.syncStatusText.isVisible = false
                    requireContext().showToast(
                        "Error: ${state.message}",
                        Toast.LENGTH_LONG
                    )
                }
            }
        }

        viewModel.loadConfigs()
    }

    private fun showConfigDialog(config: ServerConfig? = null) {
        editingConfig = config

        val dialogBinding = DialogServerConfigBinding.inflate(layoutInflater, null, false)

        // Pre-fill if editing
        config?.let {
            dialogBinding.nameEditText.setText(it.name)
            dialogBinding.urlEditText.setText(it.serverUrl)
            dialogBinding.tokenEditText.setText(it.apiToken)
        }

        // Test connection button
        dialogBinding.testConnectionButton.setOnClickListener {
            testConnectionInDialog(dialogBinding)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                if (config == null) getString(R.string.server_add)
                else getString(R.string.server_edit)
            )
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                saveConfig(dialogBinding)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        configDialog = dialog
        dialog.show()

        // Position dialog toward top to avoid keyboard occlusion
        dialog.window?.let { window ->
            val params = window.attributes
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            params.y = 100
            window.attributes = params
        }
    }

    private fun testConnectionInDialog(dialogBinding: DialogServerConfigBinding) {
        val url = dialogBinding.urlEditText.text.toString().trim()
        val token = dialogBinding.tokenEditText.text.toString().trim()

        if (url.isEmpty() || token.isEmpty()) {
            requireContext().showToast(getString(R.string.server_fill_required_fields))
            return
        }

        // Simple test: just try to connect
        requireContext().showToast(getString(R.string.server_testing_connection), Toast.LENGTH_SHORT)
        // TODO: Implement actual connection test via ViewModel
    }

    private fun saveConfig(dialogBinding: DialogServerConfigBinding) {
        val name = dialogBinding.nameEditText.text.toString().trim()
        val url = dialogBinding.urlEditText.text.toString().trim()
        val token = dialogBinding.tokenEditText.text.toString().trim()

        if (name.isEmpty() || url.isEmpty() || token.isEmpty()) {
            requireContext().showToast(
                getString(R.string.server_fill_required_fields),
                Toast.LENGTH_SHORT
            )
            return
        }

        val config = ServerConfig(
            id = editingConfig?.id ?: 0L,
            name = name,
            serverUrl = url,
            apiToken = token,
            isEnabled = true,
            lastSynced = editingConfig?.lastSynced ?: 0L
        )

        if (editingConfig == null) {
            viewModel.addConfig(config)
        } else {
            viewModel.updateConfig(config)
        }

        editingConfig = null
    }

    // ServerConfigAdapter.Callback implementation

    override fun onConfigClick(config: ServerConfig) {
        showConfigOptionsDialog(config)
    }

    override fun onConfigEdit(config: ServerConfig) {
        showConfigDialog(config)
    }

    override fun onConfigDelete(config: ServerConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.server_delete)
            .setMessage(getString(R.string.server_delete_confirmation, config.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteConfig(config)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onConfigSync(config: ServerConfig) {
        viewModel.syncConfig(config.id)
    }

    override fun onConfigScan(config: ServerConfig) {
        viewModel.triggerScan(config.id)
    }

    private fun showConfigOptionsDialog(config: ServerConfig) {
        val options = arrayOf(
            getString(R.string.server_sync),
            getString(R.string.server_scan),
            getString(R.string.action_edit),
            getString(R.string.action_delete)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(config.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.syncConfig(config.id)
                    1 -> viewModel.triggerScan(config.id)
                    2 -> showConfigDialog(config)
                    3 -> onConfigDelete(config)
                }
            }
            .show()
    }

    companion object {
        fun newInstance() = ServerSettingsFragment()
    }
}
