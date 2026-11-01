package ai.openpush.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns `/verify` responses into the public types: a 2xx body into an [OpenPushVerification], and
 * anything else into an [OpenPushVerifyError].
 *
 * The server refuses with `{"detail": {"code", "message"}}`, or with a plain string `detail` for a
 * validation failure. A known `code` always wins, and an unknown one is
 * [OpenPushVerifyError.Code.REFUSED]. Without a code the status decides: 429 is
 * [OpenPushVerifyError.Code.RATE_LIMITED], a 5xx or a request that never reached the server is
 * [OpenPushVerifyError.Code.NETWORK], and any other 4xx is [OpenPushVerifyError.Code.REFUSED].
 */
internal object VerifyResponses {
    /**
     * The verification [body] describes, carrying [phoneNumber] for the next check or resend. A body
     * this SDK cannot read is reported as [OpenPushVerifyError.Code.PROVIDER]: the server answered,
     * but not with a verification.
     */
    fun verification(body: String, phoneNumber: String, requested: OpenPushVerifyChannel): OpenPushVerification {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val id = json?.optString("id")?.takeIf(String::isNotEmpty)
        val status = OpenPushVerificationStatus.fromWire(json?.optString("status"))
        if (json == null || id == null || status == null) {
            throw OpenPushVerifyError(
                OpenPushVerifyError.Code.PROVIDER,
                "the verify response could not be read as a verification",
            )
        }
        return OpenPushVerification(
            id = id,
            maskedNumber = json.optString("to").takeIf(String::isNotEmpty) ?: PhoneNumberFormat.masked(phoneNumber),
            channel = OpenPushVerifyChannel.fromWire(json.optString("channel")) ?: requested,
            status = status,
            attempts = json.optInt("attempts", 0),
            expiresAtMillis = IsoTimestamp.parseMillis(json.optString("expires_at").takeIf(String::isNotEmpty)),
            phoneNumber = phoneNumber,
        )
    }

    /** The typed error for a non-2xx [response], or for a transport failure (status <= 0). */
    fun error(response: OpenPush.Response): OpenPushVerifyError {
        val status = response.status
        if (status <= 0) {
            return OpenPushVerifyError(
                OpenPushVerifyError.Code.NETWORK,
                response.body.takeIf(String::isNotBlank) ?: "the request did not reach the server",
            )
        }
        val detail = runCatching { JSONObject(response.body).opt("detail") }.getOrNull()
        val wireCode = (detail as? JSONObject)?.optString("code")
        val message = messageOf(detail) ?: "verify request failed ($status)"
        val code = OpenPushVerifyError.Code.fromWire(wireCode) ?: when {
            !wireCode.isNullOrEmpty() -> OpenPushVerifyError.Code.REFUSED
            status == 429 -> OpenPushVerifyError.Code.RATE_LIMITED
            status >= 500 -> OpenPushVerifyError.Code.NETWORK
            else -> OpenPushVerifyError.Code.REFUSED
        }
        return OpenPushVerifyError(code, message, status)
    }

    /** The server's words in a refusal [body], or null when it gave none. */
    fun detailMessage(body: String): String? = messageOf(runCatching { JSONObject(body).opt("detail") }.getOrNull())

    /** `detail.message`, a string `detail`, or the `msg` of each entry of a validation list. */
    private fun messageOf(detail: Any?): String? = when (detail) {
        is JSONObject -> detail.optString("message").takeIf(String::isNotBlank)
        is String -> detail.takeIf(String::isNotBlank)
        is JSONArray -> (0 until detail.length())
            .mapNotNull { detail.optJSONObject(it)?.optString("msg")?.takeIf(String::isNotBlank) }
            .joinToString("; ")
            .takeIf(String::isNotEmpty)
        else -> null
    }
}
