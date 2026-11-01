package ai.openpush.example.existing

import ai.openpush.android.OpenPush
import ai.openpush.android.fcm.OpenPushMessagingService
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The game's own service, as it was before OpenPush, plus the lines `./gradlew :app:openpushSetup`
 * prints in Forward mode (or inserts with `-Popenpush.applyPatch=true`): OpenPush pushes go to
 * [OpenPushMessagingService.handle] first, and new tokens reach [OpenPush.register].
 */
class GameMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        OpenPush.register(token)
        Log.i(TAG, "token rotated")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val messageId = OpenPush.messageId(message.data)
        // Example only: lets the status screen show that this service, not OpenPush's, took the push.
        LastPush.record(this, messageId, message.data["title"] ?: message.notification?.title, LastPush.RECEIVER_HOST)
        if (messageId != null) {
            OpenPushMessagingService.handle(this, message)
            return
        }
        // Everything else is the game's own push, handled exactly as before.
        Log.i(TAG, "game push ${message.data}")
    }

    private companion object {
        const val TAG = "GameMessagingService"
    }
}
