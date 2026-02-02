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
package code.name.monkey.retromusic.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.databinding.ItemFolderBinding
import code.name.monkey.retromusic.webdav.WebDAVFile

/**
 * Adapter for displaying WebDAV folders in a browser dialog
 */
class WebDAVFolderAdapter(
    private var folders: List<WebDAVFile>,
    private val onFolderClick: (WebDAVFile) -> Unit,
    private val onFolderSelected: (WebDAVFile) -> Unit,
    private val selectedFolders: Set<String>
) : RecyclerView.Adapter<WebDAVFolderAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<WebDAVFile>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: WebDAVFile) {
            binding.folderName.text = file.name

            // Show checkbox for directories
            if (file.isDirectory) {
                binding.selectedCheck.visibility = View.VISIBLE
                binding.selectedCheck.isChecked = selectedFolders.contains(file.path)
                binding.selectedCheck.setOnClickListener { view ->
                    // Prevent click from propagating to root
                    view.setOnClickListener(null)
                    onFolderSelected(file)
                }
                binding.root.setOnClickListener {
                    onFolderClick(file)
                }
            } else {
                binding.selectedCheck.visibility = View.GONE
                binding.root.setOnClickListener {
                    onFolderClick(file)
                }
            }
        }
    }
}
