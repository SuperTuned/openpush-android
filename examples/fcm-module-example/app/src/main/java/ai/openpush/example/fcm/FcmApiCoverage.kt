package ai.openpush.example.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.OpenPushPayload
import ai.openpush.android.fcm.OpenPushImageLoader
import ai.openpush.android.fcm.OpenPushMessagingService
import ai.openpush.android.fcm.OpenPushNotificationRenderer
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.firebase.messaging.RemoteMessage

/**
 * Compile-checked coexistence recipes for hosts that replace the module's merged service.
 * This sample itself keeps the zero-service path; these declarations are intentionally absent
 * from the manifest and therefore cannot compete with [OpenPushMessagingService].
 */
@Suppress("unused")
internal object FcmApiCoverage {
    fun forwardFromAHostService(context: Context, message: RemoteMessage) {
        OpenPushMessagingService.handle(context, message)
        OpenPushLiveUpdates.handle(context, message.data, message.collapseKey)
        OpenPush.initialize(context, "example-app-id", serverURL = OpenPush.DEFAULT_SERVER_URL)
    }

    fun hostOwnedRenderer(context: Context, data: Map<String, String>) {
        val imageLoader = OpenPushImageLoader()
        val renderer = OpenPushNotificationRenderer(context, imageLoader)
        renderer.render(data)
        renderer.render(OpenPushNotification.fromData(data))
        OpenPushNotification.fromData(data, "provider-collapse-key")
        imageLoader.load("https://openpush.ai/example.png")
        val notificationId = OpenPushNotificationRenderer.notificationIdFor(data["op_message_id"].orEmpty())
        OpenPush.Notifications.remove(notificationId)
        OpenPushNotificationRenderer.META_CHANNEL_ID
        OpenPushNotificationRenderer.META_SMALL_ICON
        OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID
        OpenPushNotification.KEY_LAUNCH_URL
        OpenPushPayload.appSessionBody("provider-token")
        OpenPushPayload.appSessionDurationBody("provider-token", 1L, "duration-idempotency")
        OpenPushLiveUpdates.CHANNEL_ID
        OpenPushLiveUpdates.DEFAULT_TIMEOUT_MILLIS
    }

    /**
     * A host whose own prompt asked for POST_NOTIFICATIONS hands the answer to OpenPush. Not needed for
     * `requestPermission`, whose prompt answers in OpenPush's own Activity.
     */
    fun answerFromTheHostsOwnPrompt(requestCode: Int, grantResults: IntArray) {
        if (requestCode == OpenPush.Notifications.PERMISSION_REQUEST_CODE) {
            OpenPush.Notifications.onPermissionResult(requestCode, grantResults)
        }
    }

    fun coreSubscriptionSpellings(activity: Activity) {
        val live = OpenPush.User.pushSubscription
        live.id
        live.token
        live.optedIn
        live.snapshot()
        live.optIn()
        live.optOut()
        val liveState = live.addObserver { }
        live.removeObserver(liveState)
        val liveChange = live.addChangeObserver { }
        live.removeChangeObserver(liveChange)

        OpenPush.PushSubscription.optedIn
        OpenPush.PushSubscription.optIn()
        OpenPush.PushSubscription.optIn(activity)
        OpenPush.PushSubscription.optOut()
        val state = OpenPush.PushSubscription.addObserver { }
        OpenPush.PushSubscription.removeObserver(state)
        val change = OpenPush.PushSubscription.addChangeObserver { }
        OpenPush.PushSubscription.removeChangeObserver(change)
    }

    class CoexistingService : OpenPushMessagingService() {
        override fun renderer() = OpenPushNotificationRenderer(this)

        override fun handleLiveUpdate(data: Map<String, String>, collapseKey: String?) =
            super.handleLiveUpdate(data, collapseKey)

        override fun onForeignMessage(message: RemoteMessage) {
            Log.i(TAG, "host-owned Firebase message · ${message.messageId ?: "no id"}")
        }

        companion object {
            private const val TAG = "FcmCoexistenceRecipe"
        }
    }
}
