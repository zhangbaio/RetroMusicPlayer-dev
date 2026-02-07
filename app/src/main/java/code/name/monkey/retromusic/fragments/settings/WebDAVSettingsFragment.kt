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
import code.name.monkey.retromusic.adapters.SelectedFolderAdapter
import code.name.monkey.retromusic.adapters.WebDAVConfigAdapter
import code.name.monkey.retromusic.databinding.DialogWebdavConfigBinding
import code.name.monkey.retromusic.databinding.FragmentWebdavSettingsBinding
import code.name.monkey.retromusic.dialogs.WebDAVFolderBrowserDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.model.WebDAVConfig
import code.name.monkey.retromusic.fragments.webdav.WebDAVUiState
import code.name.monkey.retromusic.fragments.webdav.WebDAVViewModel
import code.name.monkey.retromusic.webdav.WebDAVCryptoUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
    private var selectedFoldersInDialog: MutableList<String> = mutableListOf()
    private var selectedFolderAdapter: SelectedFolderAdapter? = null
    private var currentDialogBinding: DialogWebdavConfigBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentWebdavSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        // 恢复底部播放栏
        (activity as? AbsSlidingMusicPanelActivity)?.hideBottomSheet(hide = false)
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 隐藏底部播放栏
        (activity as? AbsSlidingMusicPanelActivity)?.hideBottomSheet(hide = true)

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
        selectedFoldersInDialog = config?.musicFolders?.toMutableList() ?: mutableListOf()

        val dialogBinding = DialogWebdavConfigBinding.inflate(layoutInflater, null, false)
        currentDialogBinding = dialogBinding

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
            if (decryptedPassword.isNotEmpty() && !decryptedPassword.startsWith("encrypted://")) {
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

        // 让弹窗靠上显示，避免被键盘遮挡
        dialog.window?.let { window ->
            val params = window.attributes
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            params.y = 100
            window.attributes = params
        }
    }

    private fun setupFoldersRecyclerView(dialogBinding: DialogWebdavConfigBinding) {
        selectedFolderAdapter = SelectedFolderAdapter(
            folders = selectedFoldersInDialog.toList(),
            onEditClick = { folderPath, position ->
                showEditFolderDialog(dialogBinding, folderPath, position)
            },
            onDeleteClick = { folderPath, position ->
                showDeleteFolderConfirmation(dialogBinding, folderPath, position)
            }
        )

        dialogBinding.foldersRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = selectedFolderAdapter
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Music Folder")
            .setMessage("Enter the path to your music folder on the WebDAV server:")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val folderPath = input.text.toString().trim()
                if (folderPath.isNotEmpty()) {
                    val finalPath = normalizeFolderPath(folderPath)
                    if (!selectedFoldersInDialog.contains(finalPath)) {
                        selectedFoldersInDialog.add(finalPath)
                        updateFoldersDisplay(dialogBinding)
                    }
                }
            }
            .setNeutralButton("Browse") { _, _ ->
                // Open folder browser dialog for adding new folders
                showFolderBrowserDialog(serverUrl, username, password, dialogBinding, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditFolderDialog(
        dialogBinding: DialogWebdavConfigBinding,
        currentPath: String,
        position: Int
    ) {
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = dialogBinding.passwordEditText.text.toString()

        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
            requireContext().showToast("Please enter server URL, username and password first")
            return
        }

        val input = android.widget.EditText(requireContext())
        input.hint = "/Music"
        input.setText(currentPath)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit Music Folder")
            .setMessage("Enter the path to your music folder on the WebDAV server:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val folderPath = input.text.toString().trim()
                if (folderPath.isNotEmpty()) {
                    val finalPath = normalizeFolderPath(folderPath)
                    selectedFoldersInDialog[position] = finalPath
                    updateFoldersDisplay(dialogBinding)
                }
            }
            .setNeutralButton("Browse") { _, _ ->
                // Open folder browser dialog for editing - pass position to replace
                showFolderBrowserDialog(url, username, password, dialogBinding, position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteFolderConfirmation(
        dialogBinding: DialogWebdavConfigBinding,
        folderPath: String,
        position: Int
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Folder")
            .setMessage("Are you sure you want to remove '$folderPath' from the list?")
            .setPositiveButton("Delete") { _, _ ->
                selectedFoldersInDialog.removeAt(position)
                updateFoldersDisplay(dialogBinding)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizeFolderPath(path: String): String {
        // Add leading slash if missing
        val cleanPath = if (!path.startsWith("/")) "/$path" else path
        // Remove trailing slash for consistency
        return cleanPath.trimEnd('/')
    }

    private fun showFolderBrowserDialog(
        serverUrl: String,
        username: String,
        password: String,
        dialogBinding: DialogWebdavConfigBinding,
        editPosition: Int?
    ) {
        val browserDialog = WebDAVFolderBrowserDialog.newInstance(
            serverUrl = serverUrl,
            username = username,
            password = password,
            selectedFolders = selectedFoldersInDialog.toSet()
        ) { selectedPaths ->
            if (editPosition != null && selectedPaths.isNotEmpty()) {
                // Editing mode: replace the folder at the position with the first selected path
                selectedFoldersInDialog[editPosition] = selectedPaths.first()
            } else {
                // Adding mode: add all newly selected folders
                for (path in selectedPaths) {
                    if (!selectedFoldersInDialog.contains(path)) {
                        selectedFoldersInDialog.add(path)
                    }
                }
            }
            updateFoldersDisplay(dialogBinding)
        }
        browserDialog.show(childFragmentManager, WebDAVFolderBrowserDialog.TAG)
    }

    private fun testConnectionInDialog(dialogBinding: DialogWebdavConfigBinding) {
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = resolvePasswordInput(dialogBinding.passwordEditText.text.toString())

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
        selectedFolderAdapter?.updateFolders(selectedFoldersInDialog.toList())
    }

    private fun saveConfig(dialogBinding: DialogWebdavConfigBinding) {
        val name = dialogBinding.nameEditText.text.toString().trim()
        val url = dialogBinding.urlEditText.text.toString().trim()
        val username = dialogBinding.usernameEditText.text.toString().trim()
        val password = resolvePasswordInput(dialogBinding.passwordEditText.text.toString())

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
        currentDialogBinding = null
    }

    private fun resolvePasswordInput(rawPassword: String): String {
        if (!rawPassword.startsWith("encrypted://")) {
            return rawPassword
        }
        val configId = editingConfig?.id ?: return ""
        val decrypted = WebDAVCryptoUtil.decryptPassword(rawPassword, configId)
        return if (decrypted.startsWith("encrypted://")) "" else decrypted
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
