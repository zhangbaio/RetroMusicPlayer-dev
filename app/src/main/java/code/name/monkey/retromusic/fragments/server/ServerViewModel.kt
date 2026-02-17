package code.name.monkey.retromusic.fragments.server

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.repository.ServerRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for Server configuration and song management
 */
class ServerViewModel(
    private val repository: ServerRepository
) : ViewModel() {

    private val _configs = MutableLiveData<List<ServerConfig>>()
    val configs: LiveData<List<ServerConfig>> = _configs

    private val _songs = MutableLiveData<List<Song>>()
    val songs: LiveData<List<Song>> = _songs

    private val _uiState = MutableLiveData<ServerUiState>(ServerUiState.Idle)
    val uiState: LiveData<ServerUiState> = _uiState

    private fun postUiState(state: ServerUiState) {
        if (_uiState.value == state) return
        _uiState.postValue(state)
    }

    fun loadConfigs() {
        viewModelScope.launch {
            try {
                val configList = repository.getAllConfigs()
                _configs.postValue(configList)
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun addConfig(config: ServerConfig) {
        viewModelScope.launch {
            try {
                repository.saveConfig(config)
                loadConfigs()
                postUiState(ServerUiState.ConfigSaved)
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Failed to save config"))
            }
        }
    }

    fun updateConfig(config: ServerConfig) {
        viewModelScope.launch {
            try {
                repository.saveConfig(config)
                loadConfigs()
                postUiState(ServerUiState.ConfigSaved)
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Failed to update config"))
            }
        }
    }

    fun deleteConfig(config: ServerConfig) {
        viewModelScope.launch {
            try {
                repository.deleteConfig(config)
                loadConfigs()
                postUiState(ServerUiState.ConfigDeleted)
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Failed to delete config"))
            }
        }
    }

    fun syncConfig(configId: Long) {
        viewModelScope.launch {
            try {
                postUiState(ServerUiState.Syncing)
                val result = repository.syncSongs(configId)
                result.fold(
                    onSuccess = { count ->
                        postUiState(ServerUiState.SyncComplete(count))
                        loadConfigs()
                    },
                    onFailure = { e ->
                        postUiState(ServerUiState.Error(e.message ?: "Sync failed"))
                    }
                )
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Sync failed"))
            }
        }
    }

    fun triggerScan(configId: Long) {
        viewModelScope.launch {
            try {
                postUiState(ServerUiState.Syncing)
                val result = repository.triggerBackendScan(configId)
                result.fold(
                    onSuccess = {
                        postUiState(ServerUiState.ScanTriggered)
                    },
                    onFailure = { e ->
                        postUiState(ServerUiState.Error(e.message ?: "Scan failed"))
                    }
                )
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Scan failed"))
            }
        }
    }

    fun testConnection(config: ServerConfig) {
        viewModelScope.launch {
            try {
                postUiState(ServerUiState.Syncing)
                val result = repository.testConnection(config)
                result.fold(
                    onSuccess = {
                        postUiState(ServerUiState.ConnectionTestSuccess)
                    },
                    onFailure = { e ->
                        postUiState(ServerUiState.Error(e.message ?: "Connection test failed"))
                    }
                )
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Connection test failed"))
            }
        }
    }

    fun loadSongs(configId: Long?) {
        viewModelScope.launch {
            try {
                val songList = if (configId != null) {
                    repository.getSongsByConfig(configId)
                } else {
                    repository.getAllSongs()
                }
                _songs.postValue(songList)
            } catch (e: Exception) {
                postUiState(ServerUiState.Error(e.message ?: "Failed to load songs"))
            }
        }
    }

    fun clearState() {
        postUiState(ServerUiState.Idle)
    }
}

/**
 * UI state for Server operations
 */
sealed class ServerUiState {
    object Idle : ServerUiState()
    object Syncing : ServerUiState()
    data class SyncComplete(val count: Int) : ServerUiState()
    object ConnectionTestSuccess : ServerUiState()
    object ScanTriggered : ServerUiState()
    object ConfigSaved : ServerUiState()
    object ConfigDeleted : ServerUiState()
    data class Error(val message: String) : ServerUiState()
}
