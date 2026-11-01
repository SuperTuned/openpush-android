package ai.openpush.example.migrated

import ai.openpush.android.onesignal.*
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log

/**
 * A receive-only app that ran on OneSignal v5 and now runs on OpenPush.
 *
 * The pre-migration version of this sample app is in `Before/`, so the migration is a diff
 * anyone can reproduce rather than a claim in a README:
 *
 *     diff -u Before/ExampleApplication.kt.onesignal \
 *       app/src/main/java/ai/openpush/example/migrated/ExampleApplication.kt
 *
 * See README.md for that diff, annotated.
 */
class ExampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        OneSignal.initWithContext(this, APP_ID)

        // The click event carries the parsed notification, so a migrating host reads the composer's
        // fields off the model instead of picking them out of the raw data map. `actionId` is null
        // for a tap on the notification body and the button's id for a tap on one of its actions.
        OneSignal.Notifications.addClickListener { event ->
            Log.i(TAG, "notification opened · ${event.messageId}")
            Log.i(TAG, "  title      · ${event.notification.title ?: "none"}")
            Log.i(TAG, "  launch url · ${event.notification.launchUrl ?: "none"}")
            Log.i(TAG, "  action     · ${event.actionId ?: "body tap"}")
        }

        OneSignal.InAppMessages.addClickListener { event ->
            Log.i(TAG, "in-app clicked · ${event.actionId ?: "none"}")
        }

        OneSignal.InAppMessages.addTrigger("example", "1")

        OneSignal.Session.addOutcome("tutorial")
        OneSignal.Session.addUniqueOutcome("first_open")
        OneSignal.Session.addOutcomeWithValue("tutorial_progress", 1f)

        // OneSignal.User.addEmail(address) forwards to OpenPush.User.addEmail: subscribed at once unless suppressed
    }

    /** A real host passes its own user id, and an auth hash computed by its server. */
    fun signIn(externalId: String) {
        OneSignal.login(externalId)
        OneSignal.User.trackEvent("signed_in")
        OneSignal.User.addTag("example_build", "migrated-from-onesignal")
        OneSignal.User.addTags(mapOf("tier" to "free", "signed_in" to "true"))
        OneSignal.User.removeTags(listOf("signed_in"))
        OneSignal.User.addAlias("example_player_id", "p-1042")
        OneSignal.User.addAliases(mapOf("account_id" to externalId, "example_id" to "p-1042"))
        OneSignal.User.removeAliases(listOf("example_id"))
    }

    fun signOut() = OneSignal.logout()

    private fun createNotificationChannel() {
        // Channels exist from API 26; below it there is nothing to declare.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val APP_ID = BuildConfig.OPENPUSH_APP_ID

        private const val TAG = "MigratedExample"
        const val CHANNEL_ID = "example_pushes"
    }
}
