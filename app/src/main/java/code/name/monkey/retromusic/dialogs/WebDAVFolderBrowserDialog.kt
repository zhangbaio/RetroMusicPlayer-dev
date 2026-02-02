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
package code.name.monkey.retromusic.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapters.WebDAVFolderAdapter
import code.name.monkey.retromusic.databinding.DialogWebdavFolderBrowserBinding
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.webdav.SardineWebDAVClient
import code.name.monkey.retromusic.webdav.WebDAVFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for browsing and selecting WebDAV folders
 */
class WebDAVFolderBrowserDialog : DialogFragment() {

    private var _binding: DialogWebdavFolderBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WebDAVFolderAdapter
    private val folders = mutableListOf<WebDAVFile>()
    private val webDAVClient = SardineWebDAVClient()

    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private var currentPath: String = ""

    private var onFolderSelected: ((Set<String>) -> Unit)? = null
    private var selectedFolders: MutableSet<String> = mutableSetOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogWebdavFolderBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()

        // Start browsing from root
        browseFolder("")
    }

    private fun setupRecyclerView() {
        adapter = WebDAVFolderAdapter(
            folders = folders,
            onFolderClick = { folder ->
                if (folder.isDirectory) {
                    browseFolder(folder.path)
                }
            },
            onFolderSelected = { folder ->
                toggleFolderSelection(folder)
            },
            selectedFolders = selectedFolders
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            navigateUp()
        }

        binding.currentPathText.setOnClickListener {
            // Show full path in a toast or dialog
        }

        binding.confirmButton.setOnClickListener {
            confirmSelection()
        }
    }

    private fun confirmSelection() {
        // If no folders were explicitly selected via checkboxes,
        // automatically add the current folder path
        val foldersToReturn = if (selectedFolders.isEmpty() && currentPath.isNotEmpty()) {
            // Clean up the path: ensure it starts with / and remove trailing /
            val cleanPath = if (currentPath.startsWith("/")) currentPath else "/$currentPath"
            setOf(cleanPath.trimEnd('/'))
        } else {
            // Clean up all selected folder paths
            selectedFolders.map { path ->
                val cleanPath = if (path.startsWith("/")) path else "/$path"
                cleanPath.trimEnd('/')
            }.toSet()
        }
        onFolderSelected?.invoke(foldersToReturn)
        dismiss()
    }

    private fun browseFolder(path: String) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                binding.progressBar.isVisible = true

                val config = code.name.monkey.retromusic.model.WebDAVConfig(
                    name = "temp",
                    serverUrl = serverUrl,
                    username = username,
                    password = password
                )

                val files = withContext(Dispatchers.IO) {
                    webDAVClient.listFiles(config, path)
                }

                folders.clear()
                folders.addAll(files)

                // Add parent directory option if not at root
                if (path.isNotEmpty()) {
                    val parentPath = path.substringBeforeLast("/", "")
                    folders.add(0, WebDAVFile(
                        name = "..",
                        path = parentPath,
                        isDirectory = true
                    ))
                }

                currentPath = path
                updatePathDisplay()
                adapter.notifyDataSetChanged()

                binding.progressBar.isVisible = false
                binding.emptyView.isVisible = files.isEmpty()

            } catch (e: Exception) {
                binding.progressBar.isVisible = false
                requireContext().showToast("Failed to load folder: ${e.message}")
            }
        }
    }

    private fun navigateUp() {
        val parentPath = if (currentPath.contains("/")) {
            currentPath.substringBeforeLast("/", "")
        } else {
            ""
        }
        browseFolder(parentPath)
    }

    private fun toggleFolderSelection(folder: WebDAVFile) {
        if (folder.isDirectory) {
            // Toggle selection for this folder
            if (selectedFolders.contains(folder.path)) {
                selectedFolders.remove(folder.path)
            } else {
                selectedFolders.add(folder.path)
            }
            adapter.notifyDataSetChanged()
        }
    }

    private fun updatePathDisplay() {
        binding.currentPathText.text = if (currentPath.isEmpty()) {
            "/"
        } else {
            currentPath
        }
        binding.backButton.isEnabled = currentPath.isNotEmpty()
    }

    companion object {
        const val TAG = "WebDAVFolderBrowserDialog"
        const val ARG_SERVER_URL = "server_url"
        const val ARG_USERNAME = "username"
        const val ARG_PASSWORD = "password"
        const val ARG_SELECTED_FOLDERS = "selected_folders"

        fun newInstance(
            serverUrl: String,
            username: String,
            password: String,
            selectedFolders: Set<String> = emptySet(),
            onFolderSelected: (Set<String>) -> Unit
        ): WebDAVFolderBrowserDialog {
            return WebDAVFolderBrowserDialog().apply {
                this.serverUrl = serverUrl
                this.username = username
                this.password = password
                this.selectedFolders.clear()
                this.selectedFolders.addAll(selectedFolders)
                // Pass the callback directly - confirmSelection() will invoke it with the correct folders
                this.onFolderSelected = onFolderSelected
            }
        }
    }
}
