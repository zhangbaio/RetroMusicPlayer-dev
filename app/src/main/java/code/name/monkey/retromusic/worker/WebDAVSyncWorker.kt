package code.name.monkey.retromusic.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.repository.WebDAVRepository
import code.name.monkey.retromusic.repository.WebDAVSyncProgress
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

class WebDAVSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository: WebDAVRepository by lazy {
        KoinJavaComponent.get(WebDAVRepository::class.java)
    }
    private var syncStartedAtMs: Long = 0L

    override suspend fun doWork(): Result {
        val configId = inputData.getLong(KEY_CONFIG_ID, -1L)
        if (configId <= 0L) {
            return Result.failure(workDataOf(KEY_OUTPUT_ERROR to "Invalid WebDAV config id"))
        }
        val retryFailedOnly = inputData.getBoolean(KEY_RETRY_FAILED_ONLY, false)

        syncStartedAtMs = SystemClock.elapsedRealtime()
        setForeground(createForegroundInfo(configId, retryFailedOnly, null))

        val result = if (retryFailedOnly) {
            repository.syncFailedFolders(configId) { progress ->
                publishProgress(configId, retryFailedOnly, progress)
            }
        } else {
            repository.syncSongs(configId) { progress ->
                publishProgress(configId, retryFailedOnly, progress)
            }
        }

        return result.fold(
            onSuccess = { syncedCount ->
                if (syncedCount > 0 && !isStopped) {
                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        WebDAVDurationBackfillWorker.uniqueWorkName(configId),
                        ExistingWorkPolicy.REPLACE,
                        WebDAVDurationBackfillWorker.createRequest(configId)
                    )
                }
                val pendingRetryFolders = repository.getPendingFailedFolderCount(configId)
                if (pendingRetryFolders > 0 && !isStopped) {
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
                if (isStopped) {
                    return@fold Result.failure(
                        workDataOf(KEY_OUTPUT_ERROR to (error.message ?: "Sync cancelled"))
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
                Result.failure(
                    workDataOf(
                        KEY_OUTPUT_ERROR to (error.message ?: "WebDAV sync failed"),
                        KEY_OUTPUT_PENDING_RETRY_FOLDERS to pendingRetryFolders
                    )
                )
            }
        )
    }

    private suspend fun publishProgress(
        configId: Long,
        retryFailedOnly: Boolean,
        progress: WebDAVSyncProgress
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS_COMPLETED_FOLDERS to progress.completedFolders,
                KEY_PROGRESS_TOTAL_FOLDERS to progress.totalFolders,
                KEY_PROGRESS_FOLDER_PATH to progress.folderPath,
                KEY_PROGRESS_SYNCED_SONGS to progress.syncedSongs,
                KEY_PROGRESS_FAILED to progress.failed
            )
        )
        if (!isStopped) {
            setForeground(createForegroundInfo(configId, retryFailedOnly, progress))
        }
    }

    private fun createForegroundInfo(
        configId: Long,
        retryFailedOnly: Boolean,
        progress: WebDAVSyncProgress?
    ): ForegroundInfo {
        ensureNotificationChannel(applicationContext)

        val completed = progress?.completedFolders ?: 0
        val total = progress?.totalFolders ?: 0
        val remaining = (total - completed).coerceAtLeast(0)
        val elapsedSeconds =
            ((SystemClock.elapsedRealtime() - syncStartedAtMs).coerceAtLeast(1000L) / 1000.0)
        val speedPerMinute = if (completed <= 0) 0.0 else completed / elapsedSeconds * 60.0
        val speedText = String.format("%.1f f/m", speedPerMinute)
        val currentFolder = progress?.folderPath
            ?.substringAfterLast('/')
            ?.ifBlank { progress.folderPath }
            ?: ""

        val title = applicationContext.getString(
            if (retryFailedOnly) R.string.webdav_retry_notification_title else R.string.webdav_sync_notification_title
        )
        val content = if (total > 0) {
            applicationContext.getString(
                R.string.webdav_sync_notification_content,
                completed,
                total,
                remaining,
                speedText
            )
        } else {
            applicationContext.getString(R.string.webdav_syncing)
        }

        val pausePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId(configId, retryFailedOnly) + 10000,
            createControlIntent(
                context = applicationContext,
                action = ACTION_PAUSE_SYNC,
                configId = configId,
                retryFailedOnly = retryFailedOnly
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            notificationId(configId, retryFailedOnly) + 20000,
            createControlIntent(
                context = applicationContext,
                action = ACTION_RESUME_SYNC,
                configId = configId,
                retryFailedOnly = retryFailedOnly
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_media_pause, applicationContext.getString(R.string.webdav_pause), pausePendingIntent)
            .addAction(android.R.drawable.ic_media_play, applicationContext.getString(R.string.webdav_resume), resumePendingIntent)
            .apply {
                if (total > 0) {
                    setProgress(total, completed.coerceAtMost(total), false)
                } else {
                    setProgress(0, 0, true)
                }
                if (currentFolder.isNotBlank()) {
                    setSubText(currentFolder)
                }
            }
            .build()

        val id = notificationId(configId, retryFailedOnly)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
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

        const val ACTION_PAUSE_SYNC = "code.name.monkey.retromusic.action.WEBDAV_SYNC_PAUSE"
        const val ACTION_RESUME_SYNC = "code.name.monkey.retromusic.action.WEBDAV_SYNC_RESUME"

        const val EXTRA_CONFIG_ID = "extra_config_id"
        const val EXTRA_RETRY_FAILED_ONLY = "extra_retry_failed_only"

        const val NOTIFICATION_CHANNEL_ID = "webdav_sync"
        private const val NOTIFICATION_CHANNEL_NAME = "WebDAV Sync"
        private const val NOTIFICATION_BASE_ID = 42000
        private const val RETRY_DELAY_MINUTES = 3L

        fun uniqueWorkName(configId: Long): String = "webdav_sync_$configId"

        fun retryWorkName(configId: Long): String = "webdav_sync_retry_$configId"

        fun createRequest(configId: Long): OneTimeWorkRequest {
            return createRequest(configId = configId, retryFailedOnly = false)
        }

        fun createRequest(
            configId: Long,
            retryFailedOnly: Boolean,
            initialDelayMinutes: Long = 0L
        ): OneTimeWorkRequest {
            val requestBuilder = OneTimeWorkRequest.Builder(WebDAVSyncWorker::class.java)
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_CONFIG_ID, configId)
                        .putBoolean(KEY_RETRY_FAILED_ONLY, retryFailedOnly)
                        .build()
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
            if (initialDelayMinutes > 0L) {
                requestBuilder.setInitialDelay(initialDelayMinutes, TimeUnit.MINUTES)
            }
            return requestBuilder.build()
        }

        fun createRetryRequest(configId: Long): OneTimeWorkRequest {
            return createRequest(
                configId = configId,
                retryFailedOnly = true,
                initialDelayMinutes = RETRY_DELAY_MINUTES
            )
        }

        fun notificationId(configId: Long, retryFailedOnly: Boolean): Int {
            val modeOffset = if (retryFailedOnly) 1 else 0
            return NOTIFICATION_BASE_ID + ((configId % 10000L).toInt() * 2) + modeOffset
        }

        fun pausedNotificationId(configId: Long, retryFailedOnly: Boolean): Int {
            return notificationId(configId, retryFailedOnly) + 50000
        }

        fun createControlIntent(
            context: Context,
            action: String,
            configId: Long,
            retryFailedOnly: Boolean
        ): Intent {
            return Intent(context, WebDAVSyncControlReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_CONFIG_ID, configId)
                .putExtra(EXTRA_RETRY_FAILED_ONLY, retryFailedOnly)
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Service.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing != null) return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
