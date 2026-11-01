package ai.openpush.example.existing

import android.content.Context
import android.content.SharedPreferences

/** The last push this device received and which service took it, kept for the status screen. */
object LastPush {
    const val RECEIVER_HOST = "GameMessagingService (host)"
    const val RECEIVER_OPENPUSH = "OpenPushMessagingService (SDK)"

    private const val FILE = "example_last_push"
    private const val MESSAGE_ID = "message_id"
    private const val TITLE = "title"
    private const val RECEIVER = "receiver"
    private const val FORWARDED = "forwarded_ids"
    private const val FORWARDED_LIMIT = 20

    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, messageId: String?, title: String?, receiver: String) {
        val editor = preferences(context).edit()
            .putString(MESSAGE_ID, messageId)
            .putString(TITLE, title)
            .putString(RECEIVER, receiver)
        if (receiver == RECEIVER_HOST && messageId != null) {
            val forwarded = preferences(context).getStringSet(FORWARDED, emptySet()).orEmpty()
            editor.putStringSet(FORWARDED, (forwarded.toList().takeLast(FORWARDED_LIMIT - 1) + messageId).toSet())
        }
        editor.apply()
    }

    /** True when [messageId] reached the host service, which forwarded it to OpenPush. */
    fun wasForwarded(context: Context, messageId: String): Boolean =
        preferences(context).getStringSet(FORWARDED, emptySet()).orEmpty().contains(messageId)

    fun messageId(context: Context): String? = preferences(context).getString(MESSAGE_ID, null)

    fun title(context: Context): String? = preferences(context).getString(TITLE, null)

    fun receiver(context: Context): String? = preferences(context).getString(RECEIVER, null)
}
