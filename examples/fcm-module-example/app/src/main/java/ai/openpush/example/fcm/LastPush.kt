package ai.openpush.example.fcm

import android.content.Context
import android.content.SharedPreferences

/**
 * The last push the SDK showed this app, kept for the status screen. The module's own service takes
 * delivery, so the host sees a push through its listeners: the foreground listener while the app is
 * open, the click listener when one is tapped from the shade.
 */
object LastPush {
    private const val FILE = "example_last_push"
    private const val MESSAGE_ID = "message_id"
    private const val TITLE = "title"
    private const val SEEN = "seen"

    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, messageId: String?, title: String?, seen: String) {
        preferences(context).edit()
            .putString(MESSAGE_ID, messageId)
            .putString(TITLE, title)
            .putString(SEEN, seen)
            .apply()
    }

    fun messageId(context: Context): String? = preferences(context).getString(MESSAGE_ID, null)

    fun title(context: Context): String? = preferences(context).getString(TITLE, null)

    fun seen(context: Context): String? = preferences(context).getString(SEEN, null)
}
