package code.name.monkey.retromusic.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import code.name.monkey.retromusic.R

class WebDAVSyncControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val configId = intent.getLongExtra(WebDAVSyncWorker.EXTRA_CONFIG_ID, -1L)
        if (configId <= 0L) return
        val retryFailedOnly = intent.getBooleanExtra(WebDAVSyncWorker.EXTRA_RETRY_FAILED_ONLY, false)
        val workManager = WorkManager.getInstance(context)

        when (intent.action) {
            WebDAVSyncWorker.ACTION_PAUSE_SYNC -> {
                workManager.cancelUniqueWork(WebDAVSyncWorker.uniqueWorkName(configId))
                workManager.cancelUniqueWork(WebDAVSyncWorker.retryWorkName(configId))
                showPausedNotification(context, configId, retryFailedOnly)
            }

            WebDAVSyncWorker.ACTION_RESUME_SYNC -> {
                val workName = if (retryFailedOnly) {
                    WebDAVSyncWorker.retryWorkName(configId)
                } else {
                    WebDAVSyncWorker.uniqueWorkName(configId)
                }
                workManager.enqueueUniqueWork(
                    workName,
                    ExistingWorkPolicy.REPLACE,
                    WebDAVSyncWorker.createRequest(
                        configId = configId,
                        retryFailedOnly = retryFailedOnly
                    )
                )
                dismissPausedNotification(context, configId, retryFailedOnly)
            }
        }
    }

    private fun showPausedNotification(
        context: Context,
        configId: Long,
        retryFailedOnly: Boolean
    ) {
        WebDAVSyncWorker.ensureNotificationChannel(context)
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val resumePendingIntent = PendingIntent.getBroadcast(
            context,
            WebDAVSyncWorker.pausedNotificationId(configId, retryFailedOnly) + 1000,
            WebDAVSyncWorker.createControlIntent(
                context = context,
                action = WebDAVSyncWorker.ACTION_RESUME_SYNC,
                configId = configId,
                retryFailedOnly = retryFailedOnly
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, WebDAVSyncWorker.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.webdav_sync_paused_title))
            .setContentText(context.getString(R.string.webdav_sync_paused_content))
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.webdav_resume),
                resumePendingIntent
            )
            .build()

        manager.notify(WebDAVSyncWorker.pausedNotificationId(configId, retryFailedOnly), notification)
    }

    private fun dismissPausedNotification(
        context: Context,
        configId: Long,
        retryFailedOnly: Boolean
    ) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(WebDAVSyncWorker.pausedNotificationId(configId, retryFailedOnly))
    }
}
