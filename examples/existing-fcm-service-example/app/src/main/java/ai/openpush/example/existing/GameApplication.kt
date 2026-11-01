package ai.openpush.example.existing

import ai.openpush.android.OpenPush
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** Initializes OpenPush from the manifest keys the setup plugin merged in from `openpush.properties`. */
class GameApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("game_news", getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val metaData = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData
        val appId = metaData?.getString("ai.openpush.app_id")
        if (appId.isNullOrBlank() || appId.startsWith("replace-with-")) {
            Log.i(TAG, "copy app/openpush.properties.example to app/openpush.properties to initialize OpenPush")
            return
        }
        OpenPush.initialize(this)

        // Example only: a push that reaches these listeners without passing through GameMessagingService
        // was taken by OpenPushMessagingService, which Forward mode should never allow. The foreground
        // listener sees pushes while the app is open; the click listener sees taps on the rest.
        OpenPush.Notifications.addForegroundLifecycleListener { event ->
            recordIfNotForwarded(event.messageId, event.notification.title)
        }
        OpenPush.Notifications.addClickListener { event ->
            recordIfNotForwarded(event.messageId, event.notification.title)
        }
    }

    private fun recordIfNotForwarded(messageId: String?, title: String?) {
        if (messageId == null || LastPush.wasForwarded(this, messageId)) return
        LastPush.record(this, messageId, title, LastPush.RECEIVER_OPENPUSH)
    }

    private companion object {
        const val TAG = "GameApplication"
    }
}
