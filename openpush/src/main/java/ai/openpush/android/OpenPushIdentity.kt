package ai.openpush.android

/** Outcome of a [OpenPush.login] or [OpenPush.logout] attempt. */
sealed class IdentityResult {
    /** The identity was accepted, or stored for the next registration. */
    object Success : IdentityResult()

    /** The device is registered anonymously; [note] says why the identity was refused. */
    data class IdentityRejected(val note: String) : IdentityResult()

    /** The request did not complete; [message] is a bounded description. */
    data class Failed(val message: String) : IdentityResult()
}

/**
 * Snapshot of this device's push subscription. [id] is the identifier from the last accepted
 * registration; [optedIn] is true only when notifications are permitted, a provider token is held, and
 * the host has not called [OpenPush.PushSubscription.optOut].
 *
 * The response `status` is adopted on the opt-out axis only: `-2` sets the local opt-out, a positive
 * value clears it (spec A6).
 */
data class PushSubscriptionState(val id: String?, val token: String?, val optedIn: Boolean)

/** One observed change of the push subscription. [previous] is null on the first observation. */
data class PushSubscriptionChange(val previous: PushSubscriptionState?, val current: PushSubscriptionState)
