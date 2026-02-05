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
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.databinding.ItemSelectedFolderBinding

/**
 * Adapter for displaying selected folders with edit and delete actions
 */
class SelectedFolderAdapter(
    private var folders: List<String>,
    private val onEditClick: (String, Int) -> Unit,
    private val onDeleteClick: (String, Int) -> Unit
) : RecyclerView.Adapter<SelectedFolderAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(folders[position], position)
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<String>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        private val binding: ItemSelectedFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folderPath: String, position: Int) {
            binding.folderPath.text = folderPath

            binding.editButton.setOnClickListener {
                onEditClick(folderPath, position)
            }

            binding.deleteButton.setOnClickListener {
                onDeleteClick(folderPath, position)
            }
        }
    }
}
