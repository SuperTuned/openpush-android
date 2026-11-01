package ai.openpush.android

import android.content.Context

/**
 * Receives background (data-only) pushes without the app having called [OpenPush.initialize].
 *
 * A background push can start the app's process from nothing, and in that fresh process no
 * `addBackgroundMessageListener` has run yet. Declare an implementation in the manifest and the
 * SDK instantiates it by name for every background push, before and beside any runtime listener:
 *
 * ```xml
 * <meta-data android:name="ai.openpush.background_message_handler"
 *            android:value="com.example.app.SyncOnPush" />
 * ```
 *
 * The class needs a public no-argument constructor. [onBackgroundMessage] runs on the thread that
 * delivered the push, in the process the push arrived in; keep it short and hand longer work to
 * WorkManager. A force-stopped app receives nothing — Android itself withholds the push.
 */
fun interface OpenPushBackgroundMessageHandler {
    fun onBackgroundMessage(context: Context, event: BackgroundMessageEvent)
}
