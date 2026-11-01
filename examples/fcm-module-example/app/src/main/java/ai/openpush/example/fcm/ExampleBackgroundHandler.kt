package ai.openpush.example.fcm

import ai.openpush.android.BackgroundMessageEvent
import ai.openpush.android.OpenPushBackgroundMessageHandler
import android.content.Context

/**
 * Receives data-only pushes in a process FCM started from nothing, named by the
 * `ai.openpush.background_message_handler` meta-data in the manifest. Nothing in the app's code
 * refers to this class, so it reaches the minified release build only through the keep rule the SDK
 * ships; CI checks the release mapping for it.
 */
class ExampleBackgroundHandler : OpenPushBackgroundMessageHandler {
    override fun onBackgroundMessage(context: Context, event: BackgroundMessageEvent) {
        LastPush.record(context, event.messageId, null, "data-only push in the background")
    }
}
