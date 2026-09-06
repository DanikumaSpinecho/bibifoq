package app.bibifoq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BibifoqApplication : Application() {

    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceLocator(this)
        createNotificationChannel()

        // The single biggest latency win in the app: pay the extraction engine's start-up cost
        // now, in the background, while the user is still looking at the home screen. Doing it
        // lazily on the first paste puts a second or more of interpreter boot directly in front
        // of the thing the user came to do.
        services.applicationScope.launch(Dispatchers.IO) {
            services.ytDlpEngine.warmUp()
            runCatching { FFmpeg.getInstance().init(this@BibifoqApplication) }
        }

        // A download that was running when the process died is not running any more; say so
        // rather than leaving a progress bar frozen at 40% forever.
        services.applicationScope.launch {
            runCatching {
                services.database.downloads().markInterrupted("Interrupted - tap to retry")
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "downloads"
    }
}
