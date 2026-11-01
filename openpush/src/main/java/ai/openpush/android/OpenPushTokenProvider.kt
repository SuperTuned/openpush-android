package ai.openpush.android

import android.content.Context

/**
 * Fetches the provider token on the SDK's behalf, so the host does not have to forward it.
 *
 * Install one through [OpenPush.tokenProvider]. The optional FCM module installs a Firebase-backed
 * provider at process start; the core SDK never fetches a token on its own. The SDK asks after every
 * attach, and again on the next foreground while the device has no accepted registration. A token it
 * already registered is not sent twice.
 */
fun interface OpenPushTokenProvider {
    /** Calls back exactly once, on any thread. Must not throw. */
    fun requestToken(context: Context, callback: OpenPushTokenCallback)
}

/** The outcome of one [OpenPushTokenProvider.requestToken]. */
interface OpenPushTokenCallback {
    fun onToken(token: String)

    /** No token this time; [reason] is logged and the SDK asks again on the next foreground. */
    fun onUnavailable(reason: String)
}
