package ai.openpush.android

/**
 * Why an [OpenPush.User.addEmail] or [OpenPush.User.removeEmail] write did not take. [code] is what
 * to branch on; [message] is the server's own words when it gave them, ready to log. [status] is the
 * HTTP status of the refusal, or null when the device refused the address before sending it.
 * [subscriptionId] is set for [Code.SUPPRESSED]: the address is stored, just never emailed.
 */
class OpenPushEmailError @JvmOverloads constructor(
    val code: Code,
    override val message: String,
    val status: Int? = null,
    val subscriptionId: String? = null,
) : Exception(message) {
    enum class Code {
        /** The address failed the local check. Raised on the device; nothing was sent. */
        INVALID_EMAIL,

        /** This app's suppression list has the address (it unsubscribed, bounced or complained). Not retried. */
        SUPPRESSED,

        /** The server refused the write in words: email off for the app, or not an address it accepts. Not retried. */
        REFUSED,
    }

    override fun toString(): String =
        "OpenPushEmailError(code=$code, status=$status, subscriptionId=$subscriptionId, message=$message)"
}
