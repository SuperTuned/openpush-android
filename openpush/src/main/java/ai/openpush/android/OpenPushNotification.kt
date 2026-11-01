package ai.openpush.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * One push as the composer described it: the parsed form of the data map a provider delivered.
 *
 * Every field the composer can set has a place here, so a host reading a [ClickEvent] or a
 * [ForegroundEvent] — and the renderer drawing the notification — read the same parse rather than
 * each digging into the raw map with its own key spellings. [rawData] keeps the map itself for
 * anything this model does not name yet.
 *
 * @property messageId the OpenPush message id (`op_message_id`), absent for a push OpenPush did not send
 * @property accentColor ARGB, parsed from `op_android_accent`; a six-digit value is made opaque
 * @property visibility one of `Notification.VISIBILITY_PUBLIC` (1), `_PRIVATE` (0) or `_SECRET` (-1)
 * @property actions the composer's action buttons, in display order, at most three
 * @property additionalData every key that is neither a rendered field nor an OpenPush `op_` control key
 * @property rawData the delivered map, untouched
 */
data class OpenPushNotification(
    val messageId: String?,
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val launchUrl: String?,
    val channelId: String?,
    val accentColor: Int?,
    val largeIconUrl: String?,
    val bigPictureUrl: String?,
    val group: String?,
    val category: String?,
    val visibility: Int?,
    val sound: String?,
    val smallIcon: String?,
    val ledColor: Int?,
    val actions: List<OpenPushAction>,
    val collapseKey: String?,
    val additionalData: Map<String, String>,
    val rawData: Map<String, String>,
) {
    /** True when this push carries an `op_message_id`, i.e. when OpenPush sent it. */
    val isOpenPush: Boolean get() = messageId != null

    /**
     * True for a background (data-only) push: `op_background` is `"1"` and there is no title or
     * body. Such a push is never displayed; it goes to the background-message listeners instead.
     */
    val isBackground: Boolean
        get() = rawData[KEY_BACKGROUND] == "1" && title.isNullOrEmpty() && body.isNullOrEmpty()

    /**
     * This notification as JSON for wrapper SDKs (snake_case keys; absent values are JSON null).
     * [rawData] is carried as `data` so a wrapper can rebuild anything this model does not name.
     */
    fun toJson(): JSONObject {
        val actionArray = JSONArray()
        actions.forEach { action ->
            actionArray.put(
                JSONObject()
                    .put("id", action.id)
                    .put("label", action.label)
                    .put("icon", action.icon)
                    .put("system_icon", action.systemIcon)
                    .put("url", action.url ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("message_id", messageId ?: JSONObject.NULL)
            .put("title", title ?: JSONObject.NULL)
            .put("body", body ?: JSONObject.NULL)
            .put("image_url", imageUrl ?: JSONObject.NULL)
            .put("launch_url", launchUrl ?: JSONObject.NULL)
            .put("collapse_id", collapseKey ?: JSONObject.NULL)
            .put("channel_id", channelId ?: JSONObject.NULL)
            .put("group", group ?: JSONObject.NULL)
            .put("category", category ?: JSONObject.NULL)
            .put("actions", actionArray)
            .put("additional_data", JSONObject(additionalData as Map<*, *>))
            .put("data", JSONObject(rawData as Map<*, *>))
    }

    companion object {
        /** Data key carrying the deep link the composer attached. */
        const val KEY_LAUNCH_URL = "deep_link"

        /** Data key marking a background (data-only) push with `"1"`. See [isBackground]. */
        const val KEY_BACKGROUND = "op_background"

        /** Keys with a rendered meaning of their own, so they are never [additionalData]. */
        internal val RESERVED_KEYS = setOf("title", "body", "image_url", "deep_link", "op_actions")

        /**
         * Parses a delivered data map. Nothing here throws: an unparseable colour or visibility is
         * null and a map from another sender simply yields a model whose [isOpenPush] is false.
         *
         * @param collapseKey the provider's collapse key, which is not part of the data map
         */
        @JvmStatic
        @JvmOverloads
        fun fromData(data: Map<String, String>, collapseKey: String? = null): OpenPushNotification =
            OpenPushNotification(
                messageId = OpenPushPayload.messageId(data),
                title = data["title"],
                body = data["body"],
                imageUrl = data["image_url"],
                launchUrl = data[KEY_LAUNCH_URL],
                channelId = data["op_android_channel"],
                accentColor = parseColor(data["op_android_accent"]),
                largeIconUrl = data["op_android_large_icon"],
                bigPictureUrl = data["op_android_big_picture"] ?: data["image_url"],
                group = data["op_android_group"],
                category = data["op_android_category"],
                visibility = parseVisibility(data["op_android_visibility"]),
                sound = data["op_android_sound"],
                smallIcon = data["op_android_small_icon"],
                ledColor = parseColor(data["op_android_led"]),
                actions = OpenPushPayload.actions(data),
                collapseKey = collapseKey,
                additionalData = data.filterKeys { it !in RESERVED_KEYS && !it.startsWith("op_") },
                rawData = data,
            )

        /** `#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB`; six digits are made opaque. Else null. */
        private fun parseColor(raw: String?): Int? {
            val text = raw?.trim()?.removePrefix("#") ?: return null
            if (text.length != 6 && text.length != 8) return null
            if (!text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val value = text.toLong(16)
            return if (text.length == 6) (0xFF000000L or value).toInt() else value.toInt()
        }

        /** `public`/`private`/`secret`, or the platform's own -1..1 as a numeric string. Else null. */
        private fun parseVisibility(raw: String?): Int? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (text.lowercase()) {
                "public" -> 1
                "private" -> 0
                "secret" -> -1
                else -> text.toIntOrNull()?.takeIf { it in -1..1 }
            }
        }
    }
}
