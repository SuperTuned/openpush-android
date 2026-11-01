package ai.openpush.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * What the SDK reads back from a 2xx registration response: the subscription [subscriptionId], the note
 * explaining why an identity claim was refused, any alias labels the server declined to store, the
 * subscription [status] the server now holds, and the fields this app is configured not to keep.
 *
 * The server refuses reserved and over-cap alias labels by naming them in [refusedAliases] on a 2xx
 * response rather than failing the request, so the SDK reports them instead of guessing the rule.
 */
class RegistrationAck @JvmOverloads constructor(
    val subscriptionId: String?,
    val identityRejection: String?,
    val refusedAliases: List<String> = emptyList(),
    val status: Int? = null,
    val notCollected: List<String> = emptyList(),
) {
    /** The synthetic default constructor used by compiled Kotlin call sites that omit aliases. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        subscriptionId: String?,
        identityRejection: String?,
        refusedAliases: List<String>?,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
        subscriptionId,
        identityRejection,
        if (mask and 4 != 0) emptyList() else requireNotNull(refusedAliases),
    )

    operator fun component1(): String? = subscriptionId

    operator fun component2(): String? = identityRejection

    operator fun component3(): List<String> = refusedAliases

    operator fun component4(): Int? = status

    operator fun component5(): List<String> = notCollected

    /** The three-argument copy descriptor. The other fields stay attached when a caller uses it. */
    fun copy(subscriptionId: String?, identityRejection: String?, refusedAliases: List<String>): RegistrationAck =
        RegistrationAck(subscriptionId, identityRejection, refusedAliases, status, notCollected)

    /** The complete copy surface. */
    fun copy(
        subscriptionId: String? = this.subscriptionId,
        identityRejection: String? = this.identityRejection,
        refusedAliases: List<String> = this.refusedAliases,
        status: Int? = this.status,
        notCollected: List<String> = this.notCollected,
    ): RegistrationAck = RegistrationAck(subscriptionId, identityRejection, refusedAliases, status, notCollected)

    override fun equals(other: Any?): Boolean = other is RegistrationAck &&
        subscriptionId == other.subscriptionId &&
        identityRejection == other.identityRejection &&
        refusedAliases == other.refusedAliases &&
        status == other.status &&
        notCollected == other.notCollected

    override fun hashCode(): Int {
        var result = subscriptionId?.hashCode() ?: 0
        result = 31 * result + (identityRejection?.hashCode() ?: 0)
        result = 31 * result + refusedAliases.hashCode()
        result = 31 * result + (status ?: 0)
        return 31 * result + notCollected.hashCode()
    }

    override fun toString(): String =
        "RegistrationAck(subscriptionId=$subscriptionId, identityRejection=$identityRejection, " +
            "refusedAliases=$refusedAliases, status=$status, notCollected=$notCollected)"

    companion object {
        /** The static `copy$default` helper of the three-argument copy, for compiled Kotlin callers. */
        @JvmStatic
        @Suppress("FunctionName", "ktlint:standard:function-naming")
        fun `copy$default`(
            source: RegistrationAck,
            subscriptionId: String?,
            identityRejection: String?,
            refusedAliases: List<String>?,
            mask: Int,
            marker: Any?,
        ): RegistrationAck {
            check(marker == null) { "Super calls with default arguments are not supported" }
            return source.copy(
                if (mask and 1 != 0) source.subscriptionId else subscriptionId,
                if (mask and 2 != 0) source.identityRejection else identityRejection,
                if (mask and 4 != 0) source.refusedAliases else requireNotNull(refusedAliases),
            )
        }
    }
}

/**
 * One action button the composer configured.
 *
 * @property url the link this button opens, or null when it has none and a tap behaves like a tap on
 *   the notification body. A payload without one leaves it null.
 */
data class OpenPushAction(
    val id: String,
    val label: String,
    val icon: String = "",
    val systemIcon: Boolean = false,
    val url: String? = null,
) {
    /** The four-argument constructor, kept so code compiled against it still links. */
    constructor(id: String, label: String, icon: String, systemIcon: Boolean) :
        this(id, label, icon, systemIcon, null)
}

object OpenPushPayload {
    private const val GENERIC_REJECTION = "identity verification rejected"

    @JvmStatic
    fun messageId(data: Map<String, String>) = data["op_message_id"]?.takeIf(String::isNotEmpty)

    /**
     * Parses a registration response body. Unknown fields are ignored, a malformed body yields an empty
     * ack, and only a truthy `identity_rejected` counts as a rejection.
     */
    @JvmStatic
    fun registrationAck(body: String): RegistrationAck {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return RegistrationAck(null, null)
        val id = json.optString("id").takeIf { it.isNotEmpty() }
        val rejection = if (!json.has("identity_rejected")) {
            null
        } else {
            when (val value = json.get("identity_rejected")) {
                is JSONObject -> listOf("note", "reason", "message")
                    .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotEmpty() } }
                    ?: GENERIC_REJECTION
                is String -> value.takeIf { it.isNotEmpty() } ?: GENERIC_REJECTION
                is Boolean -> if (value) GENERIC_REJECTION else null
                JSONObject.NULL -> null
                else -> GENERIC_REJECTION
            }
        }
        val refused = stringList(json, "aliases_refused")
        val status = if (json.has("status") && !json.isNull("status")) json.optInt("status") else null
        val notCollected = stringList(json, "not_collected")
        return RegistrationAck(id, rejection, refused, status, notCollected)
    }

    private fun stringList(json: JSONObject, key: String): List<String> = json.optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotEmpty() } }
    }.orEmpty()

    /**
     * Human-readable name for a subscription status, mirroring the server's own `status_name` as far as
     * the SDK knows the codes. An unknown code is reported as-is rather than guessed at.
     */
    @JvmStatic
    fun statusName(code: Int): String = when (code) {
        -2 -> "unsubscribed"
        0 -> "no permission"
        1 -> "subscribed"
        -10 -> "invalid token"
        else -> "status $code"
    }

    /** Action buttons configured in the OpenPush composer, in display order. */
    @JvmStatic
    fun actions(data: Map<String, String>): List<OpenPushAction> {
        val raw = data["op_actions"]?.takeIf(String::isNotBlank) ?: return emptyList()
        val values = try {
            JSONArray(raw)
        } catch (error: Exception) {
            OpenPush.onLog("notification actions ignored — op_actions is not valid JSON: ${error.message}")
            return emptyList()
        }
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val id = value.optString("id").trim()
                val label = value.optString("label").trim()
                if (id.isEmpty() || label.isEmpty()) {
                    OpenPush.onLog("notification action $index dropped — rendered id or label is empty")
                    continue
                }
                add(
                    OpenPushAction(
                        id = id,
                        label = label,
                        icon = value.optString("icon").trim(),
                        systemIcon = value.optBoolean("system_icon", false),
                        url = if (value.isNull("url")) null else value.optString("url").trim().ifEmpty { null },
                    ),
                )
                if (size == 3) break
            }
        }
    }

    /**
     * Body for POST /v1/ingest. [extra] is merged into every event object, which is how the in-app
     * module adds its own wire fields (`click_id`, `block_key`, `first_click`, `page_id`) to a receipt.
     */
    @JvmStatic
    @JvmOverloads
    fun receiptBody(
        types: List<String>,
        messageId: String,
        token: String,
        appId: String,
        extra: Map<String, Any?> = emptyMap(),
    ): String = receiptBody(types.map { it to messageId }, token, appId, extra)

    /**
     * The same body for several messages at once: [events] pairs each `type` with its own
     * `message_id`, so a queue that has been holding receipts across a cold start sends them in one
     * request instead of one request each. The wire shape is unchanged — a single pair produces
     * exactly the body the one-message overload produces.
     *
     * Every event in one call shares [token] and [appId]; the caller groups by those before batching.
     */
    @JvmStatic
    @JvmOverloads
    fun receiptBody(
        events: List<Pair<String, String>>,
        token: String,
        appId: String,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val array = JSONArray()
        events.forEach { (type, messageId) ->
            val event = JSONObject()
                .put("type", type)
                .put("app", appId)
                .put("message_id", messageId)
                .put("token", token)
            extra.forEach { (key, value) -> event.put(key, value ?: JSONObject.NULL) }
            array.put(event)
        }
        return JSONObject().put("events", array).toString()
    }

    /** Body for POST /v1/apps/{app}/sessions — one foreground observation. */
    @JvmStatic
    fun appSessionBody(token: String): String = JSONObject().put("token", token).toString()

    /** Body for POST /v1/apps/{app}/sessions — one idempotent foreground-duration report. */
    @JvmStatic
    fun appSessionDurationBody(token: String, seconds: Long, key: String): String = JSONObject()
        .put("token", token)
        .put("duration_seconds", seconds)
        .put("idempotency_key", key)
        .toString()
}
