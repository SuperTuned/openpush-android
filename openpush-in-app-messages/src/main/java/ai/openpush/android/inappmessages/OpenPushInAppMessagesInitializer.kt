package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushLifecycleInitializer
import android.content.Context
import androidx.startup.Initializer

/** Starts the receive-side IAM engine after core lifecycle observation is installed. */
class OpenPushInAppMessagesInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        OpenPushInAppMessages.paused
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(OpenPushLifecycleInitializer::class.java)
}
