package ai.openpush.android

/** What the optional callback of [OpenPush.User.addEmail] and [OpenPush.User.removeEmail] reports. */
sealed class OpenPushEmailResult {
    /**
     * The server accepted the write: the address is subscribed, or, when [removed], its
     * subscription is removed. [subscriptionId] is the server's id for it, when the response had one.
     */
    data class Success(val subscriptionId: String?, val removed: Boolean) : OpenPushEmailResult()

    /** The write did not take and will not be retried; [error] says why. */
    data class Failure(val error: OpenPushEmailError) : OpenPushEmailResult()
}
