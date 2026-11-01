package ai.openpush.android

/** Where an [OpenPushVerification] stands. Only [PENDING] accepts another check or a resend. */
enum class OpenPushVerificationStatus(internal val wireValue: String) {
    /** A code is out and not yet matched. A wrong code also leaves the verification pending. */
    PENDING("pending"),

    /** The code matched. The server has marked this number's SMS and WhatsApp subscriptions subscribed. */
    APPROVED("approved"),

    /** The code was not entered in time. Start a new verification. */
    EXPIRED("expired"),

    /** Too many wrong codes. Start a new verification. */
    MAX_ATTEMPTS("max_attempts"),
    ;

    internal companion object {
        fun fromWire(value: String?): OpenPushVerificationStatus? = values().firstOrNull { it.wireValue == value }
    }
}
