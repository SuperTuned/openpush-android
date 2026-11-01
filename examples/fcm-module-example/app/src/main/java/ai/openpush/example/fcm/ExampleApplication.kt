package ai.openpush.example.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.fcm.OpenPushNotificationRenderer
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * The smallest host that takes the module integration path.
 *
 * Everything this app does not declare is the point: no host messaging service in the manifest,
 * no notification builder, no tap-handling Activity. `openpush-android-fcm` merges
 * `OpenPushMessagingService` and `OpenPushClickActivity` in from its own manifest,
 * renders the notification, and reports the click. The host supplies credentials, a
 * channel, an icon, and a listener.
 */
class ExampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (!hasCredentials) {
            // Refusing to initialize is deliberate: a half-configured SDK that
            // registers against a placeholder app id is worse than one that plainly
            // did nothing and said so.
            Log.i(TAG, getString(R.string.example_not_configured))
            return
        }

        JavaHostIntegration.initialize(
            this,
            BuildConfig.OPENPUSH_APP_ID,
            BuildConfig.OPENPUSH_SDK_KEY,
            serverUrl,
        )
        Log.i(TAG, "OpenPush Android ${JavaHostIntegration.sdkVersion()}")

        // Application registration receives a cold-start tap immediately. Registering
        // the first listener later is also safe: the SDK holds up to ten early clicks
        // and delivers them synchronously when that listener is added.
        OpenPush.Notifications.addClickListener { event ->
            LastPush.record(this, event.messageId, event.notification.title, "opened from the shade")
            val notificationId = event.messageId?.let(OpenPushNotificationRenderer::notificationIdFor)
            Log.i(
                TAG,
                "notification opened · ${event.notification.title} · " +
                    "${event.actionId ?: "body tap"} · ${event.notification.launchUrl} · " +
                    "shade id ${notificationId ?: "none"}",
            )
        }
        // Example only: a push that arrives while the app is open, for the status screen.
        OpenPush.Notifications.addForegroundLifecycleListener { event ->
            LastPush.record(this, event.messageId, event.notification.title, "received while open")
        }
    }

    /**
     * The module's renderer creates a channel of its own when this one is missing, but
     * a real host owns the channel's name and importance, so this app declares it.
     * The id matches the `ai.openpush.notification_channel_id` meta-data. Channels exist from
     * API 26; below it there is nothing to declare.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.example_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FcmModuleExample"
        const val CHANNEL_ID = "example_pushes"

        /** False while either value is still the placeholder shipped in the example. */
        val hasCredentials: Boolean
            get() = !isPlaceholder(BuildConfig.OPENPUSH_APP_ID) &&
                !isPlaceholder(BuildConfig.OPENPUSH_SDK_KEY)

        private val serverUrl: String
            get() = BuildConfig.OPENPUSH_SERVER_URL.takeUnless {
                isPlaceholder(it)
            } ?: OpenPush.DEFAULT_SERVER_URL

        private fun isPlaceholder(value: String): Boolean = value.isBlank() || value.startsWith("replace-with-")
    }
}
