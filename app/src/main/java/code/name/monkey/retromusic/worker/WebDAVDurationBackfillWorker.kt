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

class WebDAVDurationBackfillWorker(
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
        val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE).coerceIn(1, 200)

        val result = repository.backfillDurations(configId, batchSize)
        return result.fold(
            onSuccess = { backfill ->
                if (backfill.remainingSongs > 0) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        uniqueWorkName(configId),
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        createRequest(configId, batchSize)
                    )
                }
                Result.success(
                    workDataOf(
                        KEY_OUTPUT_UPDATED_SONGS to backfill.updatedSongs,
                        KEY_OUTPUT_REMAINING_SONGS to backfill.remainingSongs
                    )
                )
            },
            onFailure = { error ->
                Result.failure(workDataOf(KEY_OUTPUT_ERROR to (error.message ?: "Backfill failed")))
            }
        )
    }

    companion object {
        const val KEY_CONFIG_ID = "key_config_id"
        const val KEY_BATCH_SIZE = "key_batch_size"

        const val KEY_OUTPUT_UPDATED_SONGS = "key_output_updated_songs"
        const val KEY_OUTPUT_REMAINING_SONGS = "key_output_remaining_songs"
        const val KEY_OUTPUT_ERROR = "key_output_error"

        private const val DEFAULT_BATCH_SIZE = 16

        fun uniqueWorkName(configId: Long): String = "webdav_duration_backfill_$configId"

        fun createRequest(configId: Long, batchSize: Int = DEFAULT_BATCH_SIZE): OneTimeWorkRequest {
            return OneTimeWorkRequest.Builder(WebDAVDurationBackfillWorker::class.java)
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_CONFIG_ID, configId)
                        .putInt(KEY_BATCH_SIZE, batchSize)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        }
    }
}
