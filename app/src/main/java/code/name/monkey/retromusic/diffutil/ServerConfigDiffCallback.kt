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
package code.name.monkey.retromusic.diffutil

import androidx.recyclerview.widget.DiffUtil
import code.name.monkey.retromusic.model.ServerConfig

class ServerConfigDiffCallback : DiffUtil.ItemCallback<ServerConfig>() {
    override fun areItemsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ServerConfig, newItem: ServerConfig): Boolean {
        return oldItem == newItem
    }
}
