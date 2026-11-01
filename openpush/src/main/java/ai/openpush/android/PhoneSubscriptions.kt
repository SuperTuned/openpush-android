package ai.openpush.android

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The SMS and WhatsApp numbers and email addresses behind [OpenPush.User.addSms],
 * [OpenPush.User.addEmail] and their siblings. Each write is a `POST /v1/apps/{app_id}/subscriptions`
 * with the number or address as its token, sent on the same serialized queue as registration, so one
 * added right after `login` carries that login's external id.
 *
 * Writes are offline-first, like tags: one recorded before `initialize`, or while the server cannot
 * be reached, is persisted and sent at the next attach. Only the newest write per (platform, number
 * or address) is kept, so add-then-remove offline sends just the removal. A transport failure, 429
 * or 5xx keeps the write and retries on a backoff that holds at its last step; any other 4xx is the server's final
 * answer, so that write is dropped with a log rather than wedging every write behind it.
 */
internal object PhoneSubscriptions {
    // Same curve as custom events: a server that is down for an hour is polled twice a minute.
    internal val RETRY_DELAYS_MS = longArrayOf(2_000L, 8_000L, 30_000L)

    internal const val SUPPRESSED_MESSAGE =
        "this app's suppression list has the address (it unsubscribed, bounced or complained); it is never emailed"

    private val lock = Any()
    private val pending = LinkedHashMap<String, PhoneSubscriptionWrite>()

    // Email callbacks, by write key, for this process only: a write restored from disk reports to
    // nobody, and a callback whose write is replaced by a newer one for the same address is dropped.
    private val callbacks = HashMap<String, (OpenPushEmailResult) -> Unit>()
    private var store: PhoneSubscriptionStore? = null
    private var boundAppId: String? = null
    private var flushQueued = false
    private var retryTimer: OpenPush.Cancellable? = null
    private var attempt = 0

    // Bumped whenever the bound app changes, so a response for the app left behind writes nothing.
    private var generation = 0L

    /**
     * Records an add or a removal of [raw] under [platform]; false, with a log, when [raw] fails the
     * platform's local check. [callback] (email only) hears the server's final answer, or, posted at
     * once, [OpenPushEmailError.Code.INVALID_EMAIL]. Either way it runs on the main thread.
     */
    fun record(
        platform: PhonePlatform,
        raw: String,
        removal: Boolean,
        callback: ((OpenPushEmailResult) -> Unit)? = null,
    ): Boolean {
        val number = platform.normalized(raw)
        if (number == null) {
            OpenPush.onLog("✗ ${verb(platform, removal)} refused — ${platform.invalidMessage}")
            callback?.let {
                report(
                    it,
                    OpenPushEmailResult.Failure(
                        OpenPushEmailError(OpenPushEmailError.Code.INVALID_EMAIL, platform.invalidMessage),
                    ),
                )
            }
            return false
        }
        val write = PhoneSubscriptionWrite(platform, number, removal)
        val attached = synchronized(lock) {
            pending.remove(write.key)
            pending[write.key] = write
            if (callback != null) callbacks[write.key] = callback else callbacks.remove(write.key)
            store?.savePending(pending.values)
            boundAppId != null
        }
        if (!attached) {
            OpenPush.onLog("${verb(platform, removal)} stored — it is sent once OpenPush is initialized")
        }
        scheduleFlush()
        return true
    }

    /**
     * Binds the writes to [appId] at the end of every attach and sends whatever is owed. Writes recorded
     * before the first attach belong to the first app; writes owed to another app stay on disk under
     * that app's key until it is attached again.
     */
    fun onAttach(appId: String, prefs: SharedPreferences?) {
        synchronized(lock) {
            if (boundAppId != appId || store == null) {
                val staged = if (boundAppId == null) pending.values.toList() else emptyList()
                val next = PhoneSubscriptionStore(prefs, appId)
                pending.clear()
                if (boundAppId != null) callbacks.clear()
                next.loadPending().forEach { pending[it.key] = it }
                staged.forEach {
                    pending.remove(it.key)
                    pending[it.key] = it
                }
                next.savePending(pending.values)
                store = next
                boundAppId = appId
                generation++
                retryTimer?.cancel()
                retryTimer = null
                attempt = 0
            }
        }
        scheduleFlush()
    }

    /** The subscription id the server returned for [number] under [platform], when one is known. */
    fun subscriptionId(platform: PhonePlatform, number: String): String? = synchronized(lock) {
        store?.subscriptionId(PhoneSubscriptionWrite(platform, number, removal = false).key)
    }

    /** Writes not yet accepted by the server, oldest first. */
    fun pendingWrites(): List<PhoneSubscriptionWrite> = synchronized(lock) { pending.values.toList() }

    fun resetForTests() = synchronized(lock) {
        pending.clear()
        callbacks.clear()
        store = null
        boundAppId = null
        flushQueued = false
        retryTimer?.cancel()
        retryTimer = null
        attempt = 0
        generation++
    }

    private fun scheduleFlush() {
        synchronized(lock) {
            if (flushQueued || retryTimer != null || boundAppId == null || pending.isEmpty()) return
            flushQueued = true
        }
        OpenPush.enqueue { flush() }
    }

    /** Sends pending writes in order until one has to wait for a retry. Runs on the registration queue. */
    private suspend fun flush() {
        synchronized(lock) { flushQueued = false }
        while (true) {
            val claimed = synchronized(lock) {
                val write = pending.values.firstOrNull()
                val appId = boundAppId
                if (write == null || appId == null || retryTimer != null) return
                Triple(write, appId, generation)
            }
            val (write, appId, claimedGeneration) = claimed
            val path = "/v1/apps/$appId/subscriptions"
            OpenPush.onLog("→ POST $path · ${write.platform.wireValue} ${write.platform.masked(write.number)}")
            val response = OpenPush.bridgeRequest("POST", path, body(write).toString())
            if (!settle(write, claimedGeneration, response)) return
        }
    }

    /**
     * The body for [write]. Phone and email writes state the identity the way registration does: with
     * its auth hash under identity verification, and not at all while a required hash is missing.
     */
    private fun body(write: PhoneSubscriptionWrite): JSONObject {
        val (externalId, authHash) = OpenPush.identityClaim()
        return write.body(externalId, authHash)
    }

    /**
     * Applies [response] to [write]; true when the next pending write may go now. The log line and
     * any email callback run after the lock is released, so host code can never run inside it.
     */
    private fun settle(write: PhoneSubscriptionWrite, claimedGeneration: Long, response: OpenPush.Response): Boolean {
        val status = response.status
        val what = "${verb(write.platform, write.removal)} ${write.platform.masked(write.number)}"
        var callback: ((OpenPushEmailResult) -> Unit)? = null
        var result: OpenPushEmailResult? = null
        val (proceed, note) = synchronized(lock) {
            if (claimedGeneration != generation) return false
            when {
                status in 200..299 -> {
                    val json = runCatching { JSONObject(response.body) }.getOrNull()
                    val id = json?.optString("id")?.takeIf(String::isNotEmpty)
                    id?.let { store?.recordSubscriptionId(write.key, it) }
                    callback = takeCallbackLocked(write)
                    forgetLocked(write)
                    attempt = 0
                    // -2 on an add is the server's suppression list, not a removal: reported, never retried.
                    val suppressed = write.platform.isEmail && !write.removal && json?.optInt("status", 0) == -2
                    if (suppressed) {
                        result = OpenPushEmailResult.Failure(
                            OpenPushEmailError(OpenPushEmailError.Code.SUPPRESSED, SUPPRESSED_MESSAGE, status, id),
                        )
                        true to "✗ $what suppressed — $SUPPRESSED_MESSAGE"
                    } else {
                        result = OpenPushEmailResult.Success(id, write.removal)
                        // The server kept the address but refused the identity hash: it has no user.
                        val rejection = OpenPushPayload.registrationAck(response.body).identityRejection
                        if (rejection != null) {
                            true to "✓ $what stored without a user — the identity hash was refused: $rejection"
                        } else {
                            true to "✓ $what"
                        }
                    }
                }
                status.isRetryableStatus() -> {
                    val delay = OpenPush.backoffDelay(
                        RETRY_DELAYS_MS[minOf(attempt, RETRY_DELAYS_MS.lastIndex)],
                        OpenPush.retryAfterMs(response),
                    )
                    attempt++
                    retryTimer = OpenPush.scheduler.schedule(delay) {
                        synchronized(lock) { retryTimer = null }
                        scheduleFlush()
                    }
                    false to "✗ $what failed ($status); retrying in ${delay}ms"
                }
                else -> {
                    callback = takeCallbackLocked(write)
                    forgetLocked(write)
                    attempt = 0
                    val words = VerifyResponses.detailMessage(response.body)
                    if (write.platform.isEmail) {
                        result = OpenPushEmailResult.Failure(
                            OpenPushEmailError(
                                OpenPushEmailError.Code.REFUSED,
                                words ?: "the server refused the email write ($status)",
                                status,
                            ),
                        )
                    }
                    val why = words?.let { " — $it" }.orEmpty()
                    true to "✗ $what refused ($status)$why — dropped; the server will not accept it on a retry"
                }
            }
        }
        OpenPush.onLog(note)
        val heard = callback
        val outcome = result
        if (heard != null && outcome != null) report(heard, outcome)
        return proceed
    }

    /** The callback waiting on [write], unless a newer write for the same key replaced it. */
    private fun takeCallbackLocked(write: PhoneSubscriptionWrite): ((OpenPushEmailResult) -> Unit)? =
        if (pending[write.key] == write) callbacks.remove(write.key) else null

    private fun report(callback: (OpenPushEmailResult) -> Unit, result: OpenPushEmailResult) {
        OpenPush.deliverOnMain("email callback") { callback(result) }
    }

    /** Removes [write] unless a newer write for the same number arrived while it was in flight. */
    private fun forgetLocked(write: PhoneSubscriptionWrite) {
        if (pending[write.key] == write) pending.remove(write.key)
        store?.savePending(pending.values)
    }

    private fun verb(platform: PhonePlatform, removal: Boolean) =
        if (removal) "remove ${platform.label}" else "add ${platform.label}"
}
