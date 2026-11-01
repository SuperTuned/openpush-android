package ai.openpush.android

import org.json.JSONObject

/**
 * One phone-number or email-address change owed to the server: register [number] (for email, the
 * address) under [platform], or, when [removal] is set, opt it out (email: remove the subscription).
 * Only the newest write for a (platform, number) pair is kept, so [key] names the pair rather than
 * the write: `sms:+14155550123`, `email:ana@example.com`.
 */
internal data class PhoneSubscriptionWrite(val platform: PhonePlatform, val number: String, val removal: Boolean) {
    val key: String get() = "${platform.wireValue}:$number"

    /**
     * The subscription request body. A registration carries no `status`: a new number is not
     * verified until a phone code matches, and the server alone decides that. A removal carries the
     * one status a device may send, [STATUS_OPTED_OUT]. [authHash] rides with [externalId] only.
     */
    fun body(externalId: String?, authHash: String? = null): JSONObject = JSONObject()
        .put("token", number)
        .put("platform", platform.wireValue)
        .apply {
            externalId?.takeIf(String::isNotEmpty)?.let {
                put("external_id", it)
                authHash?.let { hash -> put("external_id_auth_hash", hash) }
            }
            if (removal) put("status", STATUS_OPTED_OUT)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform.wireValue)
        .put("number", number)
        .put("removal", removal)

    companion object {
        const val STATUS_OPTED_OUT = -2

        /** The write [json] holds, or null for an entry this build cannot read. */
        fun fromJson(json: JSONObject): PhoneSubscriptionWrite? {
            val platform = PhonePlatform.values().firstOrNull { it.wireValue == json.optString("platform") }
            val number = json.optString("number").takeIf(String::isNotEmpty)
            if (platform == null || number == null) return null
            return PhoneSubscriptionWrite(platform, number, json.optBoolean("removal"))
        }
    }
}
