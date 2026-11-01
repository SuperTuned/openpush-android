package ai.openpush.android

/**
 * Why a phone-code call did not produce an [OpenPushVerification]. [code] is what to branch on;
 * [message] is the server's own explanation when it gave one, ready to log. [status] is the HTTP
 * status of the refusal, or null when nothing came back from the server.
 */
class OpenPushVerifyError @JvmOverloads constructor(
    val code: Code,
    override val message: String,
    val status: Int? = null,
) : Exception(message) {
    enum class Code(internal val wireValue: String?) {
        /** Too many codes for this number or this app; wait before starting again. */
        RATE_LIMITED("rate_limited"),

        /** A resend came less than 30 seconds after the last send. */
        TOO_SOON("too_soon"),

        /** The verification is no longer pending: approved, expired or out of attempts. */
        NOT_PENDING("not_pending"),

        /** The messaging provider could not deliver the code. */
        PROVIDER("provider"),

        /** The number replied STOP or has opted out of texts. Never retry. */
        OPTED_OUT("opted_out"),

        /** The workspace has no text credit left (HTTP 402). */
        NO_CREDIT("credit"),

        /** Texts to this number's country are not priced or not allowed. */
        NO_RATE("no_rate"),

        /**
         * The server refused the request: an unknown verification, a disabled channel, a bad body, or a
         * refusal code this SDK does not know.
         */
        REFUSED("refused"),

        /** The number is not `+` and 8 to 15 digits once normalised. Raised on the device; nothing was sent. */
        INVALID_NUMBER(null),

        /** The request did not reach the server, or the server failed without saying why. */
        NETWORK(null),
        ;

        internal companion object {
            fun fromWire(value: String?): Code? = value?.let { wire -> values().firstOrNull { it.wireValue == wire } }
        }
    }

    override fun toString(): String = "OpenPushVerifyError(code=$code, status=$status, message=$message)"
}
