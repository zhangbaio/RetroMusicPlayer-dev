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
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapters.WebDAVConfigAdapter
import code.name.monkey.retromusic.adapters.WebDAVFolderAdapter
import code.name.monkey.retromusic.databinding.DialogWebdavConfigBinding
import code.name.monkey.retromusic.databinding.FragmentWebdavSettingsBinding
import code.name.monkey.retromusic.dialogs.WebDAVFolderBrowserDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.WebDAVConfig
import code.name.monkey.retromusic.webdav.WebDAVFile
import code.name.monkey.retromusic.fragments.webdav.WebDAVUiState
import code.name.monkey.retromusic.fragments.webdav.WebDAVViewModel
import code.name.monkey.retromusic.webdav.WebDAVCryptoUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Fragment for managing WebDAV server configurations
 */
class WebDAVSettingsFragment : Fragment(),
    WebDAVConfigAdapter.Callback {

    private val viewModel: WebDAVViewModel by viewModel()

    private var _binding: FragmentWebdavSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WebDAVConfigAdapter
    private var configDialog: Dialog? = null
    private var editingConfig: WebDAVConfig? = null

    // For folder selection in dialog
    private var selectedFoldersInDialog: MutableSet<String> = mutableSetOf()
    private var folderAdapter: WebDAVFolderAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentWebdavSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = WebDAVConfigAdapter(this)
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
                is WebDAVUiState.Idle -> {
                    binding.progressBar.isVisible = false
                }
                is WebDAVUiState.TestingConnection -> {
                    binding.progressBar.isVisible = true
                }
                is WebDAVUiState.ConnectionSuccess -> {
                    binding.progressBar.isVisible = false
                    requireContext().showToast("Connection successful", Toast.LENGTH_SHORT)
                }
                is WebDAVUiState.Syncing -> {
                    binding.progressBar.isVisible = true
                    requireContext().showToast("Syncing...", Toast.LENGTH_SHORT)
                }
                is WebDAVUiState.SyncComplete -> {
                    binding.progressBar.isVisible = false
                    requireContext().showToast(
                        "Synced ${state.count} songs",
                        Toast.LENGTH_SHORT
                    )
                }
                is WebDAVUiState.ConfigSaved -> {
                    binding.progressBar.isVisible = false
                    requireContext().showToast("Configuration saved", Toast.LENGTH_SHORT)
                }
                is WebDAVUiState.ConfigDeleted -> {
                    binding.progressBar.isVisible = false
                    requireContext().showToast("Configuration deleted", Toast.LENGTH_SHORT)
                }
                is WebDAVUiState.Error -> {
                    binding.progressBar.isVisible = false
                    requireContext().showToast(
                        "Error: ${state.message}",
                        Toast.LENGTH_LONG
                    )
                }
            }
        }

        viewModel.loadConfigs()
    }

    private fun showConfigDialog(config: WebDAVConfig? = null) {
        editingConfig = config
        selectedFoldersInDialog = config?.musicFolders?.toMutableSet() ?: mutableSetOf()

        val dialogBinding = DialogWebdavConfigBinding.inflate(layoutInflater, null, false)

        // Pre-fill if editing
        config?.let {
            android.util.Log.d("WebDAVSettings", "Editing config: id=${it.id}, password='${it.password}'")
            dialogBinding.nameEditText.setText(it.name)
            dialogBinding.urlEditText.setText(it.serverUrl)
            dialogBinding.usernameEditText.setText(it.username)
            // Decrypt password if it's encrypted
            val decryptedPassword = if (it.password.startsWith("encrypted://")) {
                android.util.Log.d("WebDAVSettings", "Password is encrypted, decrypting...")
                WebDAVCryptoUtil.decryptPassword(it.password, it.id)
            } else {
                android.util.Log.d("WebDAVSettings", "Password is not encrypted format")
                it.password
            }
            android.util.Log.d("WebDAVSettings", "Setting password to EditText, length=${decryptedPassword.length}, isEmpty=${decryptedPassword.isEmpty()}")
            // If decryption returns empty, show a placeholder or the encrypted marker
            if (decryptedPassword.isNotEmpty()) {
                dialogBinding.passwordEditText.setText(decryptedPassword)
            } else {
                // Password not found in encrypted storage - user needs to re-enter
                android.util.Log.w("WebDAVSettings", "Password not found in storage, leaving field empty")
                dialogBinding.passwordEditText.setText("")
                dialogBinding.passwordEditText.hint = "Re-enter password"
            }
        }

        // Setup folders RecyclerView
        setupFoldersRecyclerView(dialogBinding)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (config == null) "Add WebDAV Server" else "Edit WebDAV Server")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                saveConfig(dialogBinding)
            }
            .setNegativeButton("Cancel", null)
            .create()

        configDialog = dialog
        dialog.show()
    }

    private fun setupFoldersRecyclerView(dialogBinding: DialogWebdavConfigBinding) {
        folderAdapter = WebDAVFolderAdapter(
            folders = selectedFoldersInDialog.map { WebDAVFile(it, it, true) },
            onFolderClick = { /* Folder navigation handled by separate dialog */ },
            onFolderSelected = { /* Already selected */ },
            selectedFolders = selectedFoldersInDialog
        )

        dialogBinding.foldersRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = folderAdapter
        }

        dialogBinding.addFolderButton.setOnClickListener {
            showAddFolderDialog(dialogBinding)
        }

        dialogBinding.testConnectionButton.setOnClickListener {
            testConnectionInDialog(dialogBinding)
        }

        updateFoldersDisplay(dialogBinding)
    }

    private fun showAddFolderDialog(dialogBinding: DialogWebdavConfigBinding) {
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = dialogBinding.passwordEditText.text.toString()

        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            requireContext().showToast("Please enter server URL, username and password first")
            return
        }

        // Show simple dialog to enter folder path manually
        showManualFolderInputDialog(dialogBinding, url, username, password)
    }

    private fun showManualFolderInputDialog(
        dialogBinding: DialogWebdavConfigBinding,
        serverUrl: String,
        username: String,
        password: String
    ) {
        val input = android.widget.EditText(requireContext())
        input.hint = "/Music"
        if (selectedFoldersInDialog.isNotEmpty()) {
            input.setText(selectedFoldersInDialog.first())
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Music Folder")
            .setMessage("Enter the path to your music folder on the WebDAV server:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val folderPath = input.text.toString().trim()
                if (folderPath.isNotEmpty()) {
                    // Clean up the path
                    val cleanPath = if (!folderPath.startsWith("/")) "/$folderPath" else folderPath

                    // Remove trailing slash for consistency
                    val finalPath = cleanPath.trimEnd('/')

                    selectedFoldersInDialog.add(finalPath)
                    updateFoldersDisplay(dialogBinding)
                }
            }
            .setNeutralButton("Browse") { _, _ ->
                // Open folder browser dialog
                showFolderBrowserDialog(serverUrl, username, password, dialogBinding)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderBrowserDialog(
        serverUrl: String,
        username: String,
        password: String,
        dialogBinding: DialogWebdavConfigBinding
    ) {
        val browserDialog = WebDAVFolderBrowserDialog.newInstance(
            serverUrl = serverUrl,
            username = username,
            password = password,
            selectedFolders = selectedFoldersInDialog
        ) { selectedPaths ->
            // Add newly selected folders
            selectedFoldersInDialog.addAll(selectedPaths)
            updateFoldersDisplay(dialogBinding)
        }
        browserDialog.show(childFragmentManager, WebDAVFolderBrowserDialog.TAG)
    }

    private fun testConnectionInDialog(dialogBinding: DialogWebdavConfigBinding) {
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = dialogBinding.passwordEditText.text.toString()

        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            requireContext().showToast("Please enter server URL, username and password first")
            return
        }

        val config = WebDAVConfig(
            name = "Test",
            serverUrl = url,
            username = username,
            password = password,
            musicFolders = emptyList(),
            isEnabled = true
        )

        viewModel.testConnection(config)
    }

    private fun updateFoldersDisplay(dialogBinding: DialogWebdavConfigBinding) {
        if (selectedFoldersInDialog.isEmpty()) {
            dialogBinding.foldersHint.isVisible = true
            dialogBinding.foldersHint.text = "No folders selected\nAll audio files will be scanned"
        } else {
            dialogBinding.foldersHint.isVisible = false
        }

        // Update the folders adapter
        folderAdapter?.updateFolders(
            selectedFoldersInDialog.map { WebDAVFile(it, it, true) }
        )
    }

    private fun saveConfig(dialogBinding: DialogWebdavConfigBinding) {
        val name = dialogBinding.nameEditText.text.toString().trim()
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = dialogBinding.passwordEditText.text.toString()

        if (name.isEmpty() || url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            requireContext().showToast("Please fill all required fields", Toast.LENGTH_SHORT)
            return
        }

        val config = WebDAVConfig(
            id = editingConfig?.id ?: 0L,
            name = name,
            serverUrl = url,
            username = username,
            password = password,
            musicFolders = selectedFoldersInDialog.toList(),
            isEnabled = true,
            lastSynced = editingConfig?.lastSynced ?: 0L
        )

        if (editingConfig == null) {
            viewModel.addConfig(config)
        } else {
            viewModel.updateConfig(config)
        }

        editingConfig = null
        selectedFoldersInDialog.clear()
    }

    // WebDAVConfigAdapter.Callback implementation

    override fun onConfigClick(config: WebDAVConfig) {
        // Show details dialog or navigate to songs
        showConfigOptionsDialog(config)
    }

    override fun onConfigEdit(config: WebDAVConfig) {
        showConfigDialog(config)
    }

    override fun onConfigDelete(config: WebDAVConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Configuration")
            .setMessage("Are you sure you want to delete '${config.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteConfig(config)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onConfigSync(config: WebDAVConfig) {
        viewModel.syncConfig(config.id)
    }

    override fun onConfigTestConnection(config: WebDAVConfig) {
        viewModel.testConnection(config)
    }

    private fun showConfigOptionsDialog(config: WebDAVConfig) {
        val options = arrayOf("View Songs", "Sync Now", "Test Connection", "Edit", "Delete")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(config.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Navigate to songs view
                        // TODO: Implement navigation to WebDAVSongsFragment
                        requireContext().showToast("Viewing songs from ${config.name}")
                    }
                    1 -> viewModel.syncConfig(config.id)
                    2 -> viewModel.testConnection(config)
                    3 -> showConfigDialog(config)
                    4 -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Delete Configuration")
                            .setMessage("Are you sure?")
                            .setPositiveButton("Delete") { _, _ ->
                                viewModel.deleteConfig(config)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    companion object {
        fun newInstance() = WebDAVSettingsFragment()
    }
}
