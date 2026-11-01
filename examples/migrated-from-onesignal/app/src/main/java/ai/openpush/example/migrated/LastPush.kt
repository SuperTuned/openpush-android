package ai.openpush.example.migrated

import android.content.Context
import android.content.SharedPreferences

/** Device-test scaffolding, not part of the migration: the last push this app received, for the status screen. */
object LastPush {
    private const val FILE = "example_last_push"
    private const val MESSAGE_ID = "message_id"
    private const val TITLE = "title"

    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, data: Map<String, String>) {
        preferences(context).edit()
            .putString(MESSAGE_ID, data["op_message_id"])
            .putString(TITLE, data["title"])
            .apply()
    }

    fun messageId(context: Context): String? = preferences(context).getString(MESSAGE_ID, null)

    fun title(context: Context): String? = preferences(context).getString(TITLE, null)
}
