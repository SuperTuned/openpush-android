package ai.openpush.android

/** Opaque registration token returned by the `add*Listener` calls. */
class ListenerHandle internal constructor(internal val id: String)

/**
 * A notification the user opened.
 *
 * @property notification the parsed push, so a host does not have to read [data] by hand
 * @property actionId the id of the action button that was tapped, or null for the body of the notification
 */
class ClickEvent(
    val messageId: String?,
    val data: Map<String, String>,
    val notification: OpenPushNotification,
    val actionId: String?,
) {
    /** Parses [notification] and [actionId] out of [data]. */
    constructor(messageId: String?, data: Map<String, String>) : this(
        messageId,
        data,
        OpenPushNotification.fromData(data),
        data[OpenPush.ACTION_ID_KEY],
    )

    /**
     * The `url` of the action button that was tapped, or null for a tap on the notification body or
     * on a button without one. When the SDK opens launch URLs (the default), it opened this instead
     * of the push's launch URL and [launchUrlOpened] says so.
     */
    val actionUrl: String?
        get() = actionId?.let { id -> notification.actions.firstOrNull { it.id == id }?.url }

    /**
     * True when the SDK already opened the push's launch URL — or, for a button with its own
     * [actionUrl], that URL — for this tap, so a listener should not open it again.
     */
    val launchUrlOpened: Boolean get() = data[OpenPush.LAUNCH_URL_OPENED_KEY] == "true"
}

/**
 * A background (data-only) push: one sent with `op_background` = `"1"` and no title or body. It is
 * never displayed; the SDK has posted its `received` receipt.
 *
 * @property data the delivered map, untouched — the composer's custom data is in it, and in
 *   [OpenPushNotification.additionalData] without the OpenPush control keys
 * @property notification the parsed push, so a host does not have to read [data] by hand
 */
class BackgroundMessageEvent(
    val messageId: String?,
    val data: Map<String, String>,
    val notification: OpenPushNotification,
) {
    /** Parses [notification] out of [data]. */
    constructor(messageId: String?, data: Map<String, String>) : this(
        messageId,
        data,
        OpenPushNotification.fromData(data),
    )
}

/**
 * A notification that arrived while the app was foregrounded.
 *
 * @property notification the parsed push, so a host does not have to read [data] by hand
 */
class ForegroundEvent(val messageId: String?, val data: Map<String, String>, val notification: OpenPushNotification) {
    /** Parses [notification] out of [data]. */
    constructor(messageId: String?, data: Map<String, String>) : this(
        messageId,
        data,
        OpenPushNotification.fromData(data),
    )

    @Volatile var defaultPrevented: Boolean = false
        private set

    /** Tells the host not to display its own notification for this message. */
    fun preventDefault() {
        defaultPrevented = true
    }
}
