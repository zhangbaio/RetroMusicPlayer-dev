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

import androidx.lifecycle.asFlow
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.model.WebDAVConfig
import code.name.monkey.retromusic.repository.WebDAVRepository
import code.name.monkey.retromusic.worker.WebDAVSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ViewModel for WebDAV configuration and song management
 */
class WebDAVViewModel(
    private val repository: WebDAVRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _configs = MutableLiveData<List<WebDAVConfig>>()
    val configs: LiveData<List<WebDAVConfig>> = _configs

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs

    private val _uiState = MutableLiveData<WebDAVUiState>(WebDAVUiState.Idle)
    val uiState: LiveData<WebDAVUiState> = _uiState
    private var syncWorkObserverJob: Job? = null

    fun loadConfigs() {
        viewModelScope.launch {
            try {
                val configList = repository.getAllConfigs()
                _configs.postValue(configList)
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun addConfig(config: WebDAVConfig) {
        viewModelScope.launch {
            try {
                repository.saveConfig(config)
                loadConfigs()
                _uiState.postValue(WebDAVUiState.ConfigSaved)
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Failed to save config"))
            }
        }
    }

    fun updateConfig(config: WebDAVConfig) {
        viewModelScope.launch {
            try {
                repository.saveConfig(config)
                loadConfigs()
                _uiState.postValue(WebDAVUiState.ConfigSaved)
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Failed to update config"))
            }
        }
    }

    fun deleteConfig(config: WebDAVConfig) {
        viewModelScope.launch {
            try {
                repository.deleteConfig(config)
                loadConfigs()
                _uiState.postValue(WebDAVUiState.ConfigDeleted)
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Failed to delete config"))
            }
        }
    }

    fun testConnection(config: WebDAVConfig) {
        viewModelScope.launch {
            try {
                _uiState.postValue(WebDAVUiState.TestingConnection)
                val result = repository.testConnection(config)
                if (result.isSuccess) {
                    _uiState.postValue(WebDAVUiState.ConnectionSuccess)
                } else {
                    _uiState.postValue(
                        WebDAVUiState.Error(
                            result.exceptionOrNull()?.message ?: "Connection failed"
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Connection test failed"))
            }
        }
    }

    fun syncConfig(configId: Long) {
        viewModelScope.launch {
            try {
                _uiState.postValue(WebDAVUiState.Syncing)
                val request = WebDAVSyncWorker.createRequest(configId)
                workManager.enqueueUniqueWork(
                    WebDAVSyncWorker.uniqueWorkName(configId),
                    ExistingWorkPolicy.REPLACE,
                    request
                )

                observeSyncWork(configId)
            } catch (e: Exception) {
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Sync failed"))
            }
        }
    }

    private fun observeSyncWork(configId: Long) {
        syncWorkObserverJob?.cancel()
        syncWorkObserverJob = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkLiveData(WebDAVSyncWorker.uniqueWorkName(configId))
                .asFlow()
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED -> {
                            _uiState.postValue(WebDAVUiState.Syncing)
                        }

                        WorkInfo.State.RUNNING -> {
                            val completed = info.progress.getInt(
                                WebDAVSyncWorker.KEY_PROGRESS_COMPLETED_FOLDERS,
                                0
                            )
                            val total = info.progress.getInt(
                                WebDAVSyncWorker.KEY_PROGRESS_TOTAL_FOLDERS,
                                0
                            )
                            if (total > 0) {
                                _uiState.postValue(
                                    WebDAVUiState.SyncProgress(
                                        configId = configId,
                                        completedFolders = completed,
                                        totalFolders = total,
                                        folderPath = info.progress.getString(
                                            WebDAVSyncWorker.KEY_PROGRESS_FOLDER_PATH
                                        ).orEmpty(),
                                        syncedSongs = info.progress.getInt(
                                            WebDAVSyncWorker.KEY_PROGRESS_SYNCED_SONGS,
                                            0
                                        ),
                                        failed = info.progress.getBoolean(
                                            WebDAVSyncWorker.KEY_PROGRESS_FAILED,
                                            false
                                        )
                                    )
                                )
                            } else {
                                _uiState.postValue(WebDAVUiState.Syncing)
                            }
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            _uiState.postValue(
                                WebDAVUiState.SyncComplete(
                                    info.outputData.getInt(
                                        WebDAVSyncWorker.KEY_OUTPUT_SYNCED_COUNT,
                                        0
                                    )
                                )
                            )
                            cancel()
                        }

                        WorkInfo.State.FAILED -> {
                            _uiState.postValue(
                                WebDAVUiState.Error(
                                    info.outputData.getString(WebDAVSyncWorker.KEY_OUTPUT_ERROR)
                                        ?: "Sync failed"
                                )
                            )
                            cancel()
                        }

                        WorkInfo.State.CANCELLED -> {
                            _uiState.postValue(WebDAVUiState.Error("Sync cancelled"))
                            cancel()
                        }
                    }
                }
        }
    }

    fun loadSongs(configId: Long?) {
        viewModelScope.launch {
            try {
                android.util.Log.d("WebDAVViewModel", "loadSongs called with configId: $configId")
                val songList = if (configId != null) {
                    repository.getSongsByConfig(configId)
                } else {
                    repository.getAllSongs()
                }
                android.util.Log.d("WebDAVViewModel", "Loaded ${songList.size} songs")
                _songs.postValue(songList)
            } catch (e: Exception) {
                android.util.Log.e("WebDAVViewModel", "Failed to load songs", e)
                _uiState.postValue(WebDAVUiState.Error(e.message ?: "Failed to load songs"))
            }
        }
    }

    fun clearState() {
        _uiState.postValue(WebDAVUiState.Idle)
    }

    override fun onCleared() {
        syncWorkObserverJob?.cancel()
        super.onCleared()
    }
}

/**
 * UI state for WebDAV operations
 */
sealed class WebDAVUiState {
    object Idle : WebDAVUiState()
    object TestingConnection : WebDAVUiState()
    object ConnectionSuccess : WebDAVUiState()
    object Syncing : WebDAVUiState()
    data class SyncProgress(
        val configId: Long,
        val completedFolders: Int,
        val totalFolders: Int,
        val folderPath: String,
        val syncedSongs: Int,
        val failed: Boolean
    ) : WebDAVUiState()
    data class SyncComplete(val count: Int) : WebDAVUiState()
    object ConfigSaved : WebDAVUiState()
    object ConfigDeleted : WebDAVUiState()
    data class Error(val message: String) : WebDAVUiState()
}
