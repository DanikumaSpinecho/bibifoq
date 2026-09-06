package app.bibifoq.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.bibifoq.BibifoqApplication
import app.bibifoq.MainActivity
import app.bibifoq.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while downloads run.
 *
 * Without a foreground service Android is free to kill the app the moment it leaves the
 * screen, which for a downloader means every download that is not actively being watched
 * eventually dies. The service does no work itself - [DownloadCoordinator] owns that - it
 * only holds the process up and shows what is happening.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val services = (application as BibifoqApplication).services

        startForeground(NOTIFICATION_ID, buildNotification(0))

        scope.launch {
            services.downloads.active.collectLatest { count ->
                if (count == 0) {
                    stopSelf()
                } else {
                    val manager = androidx.core.app.NotificationManagerCompat.from(this@DownloadService)
                    runCatching { manager.notify(NOTIFICATION_ID, buildNotification(count)) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, BibifoqApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_downloading))
            .setContentText(resources.getQuantityString(R.plurals.downloads_running, count, count))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
