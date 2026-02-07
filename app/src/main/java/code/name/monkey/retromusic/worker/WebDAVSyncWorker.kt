package code.name.monkey.retromusic.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import code.name.monkey.retromusic.repository.WebDAVRepository
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

class WebDAVSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository: WebDAVRepository by lazy {
        KoinJavaComponent.get(WebDAVRepository::class.java)
    }

    override suspend fun doWork(): Result {
        val configId = inputData.getLong(KEY_CONFIG_ID, -1L)
        if (configId <= 0L) {
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR to "Invalid WebDAV config id"))
        }
        val retryFailedOnly = inputData.getBoolean(KEY_RETRY_FAILED_ONLY, false)

        val result = if (retryFailedOnly) {
            repository.syncFailedFolders(configId) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_COMPLETED_FOLDERS to progress.completedFolders,
                        KEY_PROGRESS_TOTAL_FOLDERS to progress.totalFolders,
                        KEY_PROGRESS_FOLDER_PATH to progress.folderPath,
                        KEY_PROGRESS_SYNCED_SONGS to progress.syncedSongs,
                        KEY_PROGRESS_FAILED to progress.failed
                    )
                )
            }
        } else {
            repository.syncSongs(configId) { progress ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS_COMPLETED_FOLDERS to progress.completedFolders,
                        KEY_PROGRESS_TOTAL_FOLDERS to progress.totalFolders,
                        KEY_PROGRESS_FOLDER_PATH to progress.folderPath,
                        KEY_PROGRESS_SYNCED_SONGS to progress.syncedSongs,
                        KEY_PROGRESS_FAILED to progress.failed
                    )
                )
            }
        }

        return result.fold(
            onSuccess = { syncedCount ->
                if (syncedCount > 0) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        WebDAVDurationBackfillWorker.uniqueWorkName(configId),
                        ExistingWorkPolicy.REPLACE,
                        WebDAVDurationBackfillWorker.createRequest(configId)
                    )
                }
                val pendingRetryFolders = repository.getPendingFailedFolderCount(configId)
                if (pendingRetryFolders > 0) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        retryWorkName(configId),
                        ExistingWorkPolicy.REPLACE,
                        createRetryRequest(configId)
                    )
                }
                Result.success(
                    workDataOf(
                        KEY_OUTPUT_SYNCED_COUNT to syncedCount,
                        KEY_OUTPUT_PENDING_RETRY_FOLDERS to pendingRetryFolders
                    )
                )
            },
            onFailure = { error ->
                val pendingRetryFolders = repository.getPendingFailedFolderCount(configId)
                if (pendingRetryFolders > 0) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        retryWorkName(configId),
                        ExistingWorkPolicy.REPLACE,
                        createRetryRequest(configId)
                    )
                }
                Result.failure(
                    workDataOf(
                        KEY_OUTPUT_ERROR to (error.message ?: "WebDAV sync failed"),
                        KEY_OUTPUT_PENDING_RETRY_FOLDERS to pendingRetryFolders
                    )
                )
            }
        )
    }

    companion object {
        const val KEY_CONFIG_ID = "key_config_id"
        const val KEY_RETRY_FAILED_ONLY = "key_retry_failed_only"

        const val KEY_PROGRESS_COMPLETED_FOLDERS = "key_progress_completed_folders"
        const val KEY_PROGRESS_TOTAL_FOLDERS = "key_progress_total_folders"
        const val KEY_PROGRESS_FOLDER_PATH = "key_progress_folder_path"
        const val KEY_PROGRESS_SYNCED_SONGS = "key_progress_synced_songs"
        const val KEY_PROGRESS_FAILED = "key_progress_failed"

        const val KEY_OUTPUT_SYNCED_COUNT = "key_output_synced_count"
        const val KEY_OUTPUT_PENDING_RETRY_FOLDERS = "key_output_pending_retry_folders"
        const val KEY_OUTPUT_ERROR = "key_output_error"

        private const val RETRY_DELAY_MINUTES = 3L

        fun uniqueWorkName(configId: Long): String = "webdav_sync_$configId"

        fun retryWorkName(configId: Long): String = "webdav_sync_retry_$configId"

        fun createRequest(configId: Long): OneTimeWorkRequest {
            return OneTimeWorkRequest.Builder(WebDAVSyncWorker::class.java)
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_CONFIG_ID, configId)
                        .putBoolean(KEY_RETRY_FAILED_ONLY, false)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        }

        fun createRetryRequest(configId: Long): OneTimeWorkRequest {
            return OneTimeWorkRequest.Builder(WebDAVSyncWorker::class.java)
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_CONFIG_ID, configId)
                        .putBoolean(KEY_RETRY_FAILED_ONLY, true)
                        .build()
                )
                .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        }
    }
}
