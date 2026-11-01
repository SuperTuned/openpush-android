package ai.openpush.android

/**
 * The one phone-number rule every phone call in this SDK applies before anything goes on the wire,
 * written to match the server's own normalisation step for step so a number the device accepts is
 * the number the server stores:
 *
 * 1. every character that is not a digit or `+` is removed, so `+1 (415) 555-0123` is `+14155550123`;
 * 2. a leading `00` becomes `+`;
 * 3. what is left must be `+` and then digits only (a second `+` fails), 8 to 15 of them, the first
 *    of which is not `0`.
 *
 * A number with no `+` or `00` is refused rather than guessed at: the caller is the only one who
 * knows which country a local number belongs to.
 */
internal object PhoneNumberFormat {
    private val NOT_DIGIT_OR_PLUS = Regex("[^0-9+]")
    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    /** Says what a refused number must look like. It never echoes the number itself. */
    const val INVALID_NUMBER_MESSAGE =
        "not an international phone number — start with + (or 00) and the country code, " +
            "8 to 15 digits in all, e.g. +14155550123"

    /** The E.164 form of [raw], or null when it is not a phone number this SDK sends. */
    fun normalized(raw: String): String? {
        val kept = raw.replace(NOT_DIGIT_OR_PLUS, "")
        val prefixed = if (kept.startsWith("00")) "+" + kept.substring(2) else kept
        return prefixed.takeIf(E164::matches)
    }

    /** The E.164 form of [raw]; throws [OpenPushVerifyError.Code.INVALID_NUMBER] when it has none. */
    fun require(raw: String): String = normalized(raw)
        ?: throw OpenPushVerifyError(OpenPushVerifyError.Code.INVALID_NUMBER, INVALID_NUMBER_MESSAGE)

    /** [number] with its middle digits hidden, for log lines: `+1415•••0123`. */
    fun masked(number: String): String =
        if (number.length <= 8) number.take(2) + "•••" else number.take(5) + "•••" + number.takeLast(4)
}
