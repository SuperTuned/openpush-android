package ai.openpush.android

/**
 * The subscription platforms a phone number or an email address is registered under, by their wire
 * name, with the local check and the log mask each applies.
 */
internal enum class PhonePlatform(val wireValue: String, val label: String) {
    SMS("sms", "SMS number"),
    WHATSAPP("whatsapp", "WhatsApp number"),
    EMAIL("email", "email address"),
    ;

    val isEmail: Boolean get() = this == EMAIL

    /** The form of [raw] sent on the wire, or null when it fails this platform's local check. */
    fun normalized(raw: String): String? =
        if (isEmail) EmailAddressFormat.normalized(raw) else PhoneNumberFormat.normalized(raw)

    val invalidMessage: String
        get() = if (isEmail) EmailAddressFormat.INVALID_EMAIL_MESSAGE else PhoneNumberFormat.INVALID_NUMBER_MESSAGE

    fun masked(value: String): String =
        if (isEmail) EmailAddressFormat.masked(value) else PhoneNumberFormat.masked(value)
}
