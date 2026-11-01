package ai.openpush.android

/** How a phone code reaches the user. [AUTO] lets the server pick the channel for the number. */
enum class OpenPushVerifyChannel(internal val wireValue: String) {
    AUTO("auto"),
    SMS("sms"),
    WHATSAPP("whatsapp"),
    CALL("call"),
    ;

    internal companion object {
        fun fromWire(value: String?): OpenPushVerifyChannel? = values().firstOrNull { it.wireValue == value }
    }
}
