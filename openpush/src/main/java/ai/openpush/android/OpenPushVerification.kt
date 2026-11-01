package ai.openpush.android

import java.util.Date

/**
 * One phone code, as the server last described it. Pass it back to [OpenPush.Verify.check] and
 * [OpenPush.Verify.resend]; each returns a new value and leaves this one unchanged.
 *
 * The server only ever shows the number masked ([maskedNumber]). The full number this verification
 * was started for is held in memory so check and resend can name it, and it goes nowhere else: this
 * type is neither `Serializable` nor `Parcelable`, and nothing in the SDK persists it. A verification
 * does not survive process death; start a new one.
 */
class OpenPushVerification internal constructor(
    val id: String,
    val maskedNumber: String,
    val channel: OpenPushVerifyChannel,
    val status: OpenPushVerificationStatus,
    val attempts: Int,
    private val expiresAtMillis: Long?,
    internal val phoneNumber: String,
) {
    /** When the code stops being accepted, or null when the server did not say. A fresh copy each read. */
    val expiresAt: Date? get() = expiresAtMillis?.let(::Date)

    override fun toString(): String = "OpenPushVerification(id=$id, maskedNumber=$maskedNumber, " +
        "channel=$channel, status=$status, attempts=$attempts, expiresAt=$expiresAt)"
}
