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
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.ItemServerConfigBinding
import code.name.monkey.retromusic.diffutil.ServerConfigDiffCallback
import code.name.monkey.retromusic.model.ServerConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for music server configurations
 */
class ServerConfigAdapter(
    private val callback: Callback? = null
) : ListAdapter<ServerConfig, ServerConfigAdapter.ViewHolder>(ServerConfigDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServerConfigBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemServerConfigBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(config: ServerConfig) {
            binding.nameText.text = config.name
            binding.urlText.text = config.serverUrl

            // Show last sync time
            if (config.lastSynced > 0) {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                binding.lastSyncText.text = "Last synced: ${dateFormat.format(Date(config.lastSynced))}"
                binding.lastSyncText.isVisible = true
            } else {
                binding.lastSyncText.isVisible = false
            }

            // Status indicator
            binding.statusIndicator.isVisible = config.isEnabled

            // Menu click
            binding.menuButton.setOnClickListener {
                showPopupMenu(it, config)
            }

            // Root view click
            binding.root.setOnClickListener {
                callback?.onConfigClick(config)
            }
        }

        private fun showPopupMenu(view: View, config: ServerConfig) {
            val popup = PopupMenu(view.context, view)
            popup.menuInflater.inflate(R.menu.menu_server_config, popup.menu)

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_sync -> {
                        callback?.onConfigSync(config)
                        true
                    }
                    R.id.action_scan -> {
                        callback?.onConfigScan(config)
                        true
                    }
                    R.id.action_edit -> {
                        callback?.onConfigEdit(config)
                        true
                    }
                    R.id.action_delete -> {
                        callback?.onConfigDelete(config)
                        true
                    }
                    else -> false
                }
            }

            popup.show()
        }
    }

    interface Callback {
        fun onConfigClick(config: ServerConfig)
        fun onConfigEdit(config: ServerConfig)
        fun onConfigDelete(config: ServerConfig)
        fun onConfigSync(config: ServerConfig)
        fun onConfigScan(config: ServerConfig)
    }
}
