package ai.openpush.android.fcm

import ai.openpush.android.OpenPushTokenCallback
import ai.openpush.android.OpenPushTokenProvider
import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Fetches the FCM registration token from the default Firebase app. Installed automatically by
 * [OpenPushFcmInitializer]; construct one yourself only if you removed that initializer.
 */
class OpenPushFirebaseTokenProvider : OpenPushTokenProvider {
    override fun requestToken(context: Context, callback: OpenPushTokenCallback) {
        if (!OpenPushFirebaseApp.ensure(context)) {
            callback.onUnavailable(OpenPushFirebaseApp.MISSING_CONFIGURATION)
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = if (task.isSuccessful) task.result else null
            when {
                !token.isNullOrBlank() -> callback.onToken(token)
                task.isCanceled -> callback.onUnavailable("the Firebase token request was cancelled")
                else -> callback.onUnavailable(
                    "Firebase returned no token (${task.exception?.message ?: "no error given"})",
                )
            }
        }
    }
}
