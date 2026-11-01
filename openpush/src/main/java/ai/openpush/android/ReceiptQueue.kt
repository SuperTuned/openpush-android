package ai.openpush.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The receipts this device owes the server, held on disk until the server acknowledges them.
 *
 * A receipt is the only record that a message was delivered, opened or confirmed, and the moment it
 * is owed is exactly the moment the network is least likely to be there: a push wakes a backgrounded
 * process on a phone that has just come out of a tunnel. An in-memory retry loses that receipt when
 * the process dies, and the message silently reads as undelivered forever. So the queue lands in
 * SharedPreferences the instant a receipt is enqueued, survives the cold start, and is drained again
 * on the next foreground or the next accepted registration.
 *
 * Two collections, both persisted and both bounded:
 *
 *  - **[key]** — the pending receipts, oldest first, as a JSON array of objects. The newest
 *    [PENDING_CAP] are retained when an offline device owes more than the store can safely hold.
 *  - **[sentKey]** — the dedupe set: the `type|message_id` of every receipt the server has taken,
 *    bounded to the most recent [SENT_CAP] keys with the oldest dropped. It replaces the in-memory
 *    "already sent" set an earlier build kept, which forgot everything on every relaunch and let a
 *    relaunched process re-post a receipt the server had already counted.
 *
 * A receipt is also bounded in time and in tries. A batch the server refuses outright (a 4xx other
 * than 429) is [dropped][drop], because sending it again gets the same answer. A batch that fails for
 * a reason that can pass (5xx, 429, no network) stays, and [markFailed] then evicts every receipt in it
 * that the server has failed [MAX_SERVER_FAILURES] times or that was queued more than [MAX_AGE_MS]
 * ago. Only server answers count as failures, and at most one per [SERVER_FAILURE_SPACING_MS]: every
 * new receipt and every foreground starts a drain, so during an outage one receipt can fail dozens
 * of times in an hour, and an outage is not a reason to give it up. A device that is offline for a
 * week keeps its receipts until the age bound, however many drains find no network.
 *
 * The bound is what keeps the dedupe set from growing without limit on a device that receives pushes
 * for years. Dropping the oldest keys is the right trade: a re-post can only happen for a message
 * whose receipt was accepted more than [SENT_CAP] receipts ago, and the server deduplicates by
 * `(message_id, type)` anyway — the set is here to save the request, not to be the only guard.
 *
 * Every method is safe to call from any thread. [nextBatch] also *claims* the entries it returns, so
 * two drains that overlap — a foreground drain on the registration channel and a blocking drain from
 * a messaging service, say — cannot both post the same receipt. The claim is released by [markSent]
 * or [markFailed], and it is memory-only: a process that dies mid-drain leaves the entries pending
 * on disk, which is exactly what should happen.
 */
internal class ReceiptQueue(
    private val prefs: SharedPreferences?,
    private val key: String,
    private val sentKey: String,
    private val now: () -> Long,
) {
    /**
     * One receipt owed. [attempts] counts every drain that tried and failed to deliver it, network
     * failures included, as earlier builds stored it. [serverFailures] counts the retryable server
     * answers (5xx, 429), at most one per [SERVER_FAILURE_SPACING_MS] after [lastServerFailureAt], and
     * is what [MAX_SERVER_FAILURES] bounds. A receipt stored by a build without these fields starts at
     * zero, so the attempts it gathered under the older rule never evict it before it is tried again.
     */
    internal data class Receipt(
        val type: String,
        val messageId: String,
        val token: String,
        val appId: String,
        val queuedAt: Long,
        var attempts: Int = 0,
        var serverFailures: Int = 0,
        var lastServerFailureAt: Long = 0L,
    ) {
        /** Identity of the event on the wire — the server counts one of these per message. */
        val dedupeKey: String get() = "$type|$messageId"
    }

    private val lock = Any()
    private val pending = mutableListOf<Receipt>()
    private val sent = LinkedHashSet<String>()
    private val claimed = mutableSetOf<String>()
    private var pendingCapWarned = false
    private var retired = false

    init {
        load()
    }

    /** How many receipts are still owed, claimed ones included. */
    val size: Int get() = synchronized(lock) { pending.size }

    /** The dedupe set, oldest first. Exposed for the tests that assert the bound. */
    val sentKeys: List<String> get() = synchronized(lock) { sent.toList() }

    /**
     * Records a receipt as owed. Returns false — and writes nothing — when the server has already
     * taken this `type|message_id`, or when it is already waiting in the queue. [queuedAt] is the
     * moment the receipt was recorded, earlier than now for one staged before `initialize`.
     */
    fun enqueue(type: String, messageId: String, token: String, appId: String, queuedAt: Long = now()): Boolean =
        synchronized(lock) {
            if (retired) return false
            val dedupe = "$type|$messageId"
            if (dedupe in sent) return false
            if (pending.any { it.dedupeKey == dedupe }) return false
            pending += Receipt(type, messageId, token, appId, queuedAt)
            trimPendingLocked()
            persistPendingLocked()
            true
        }

    /**
     * The next receipts to send as one request: the oldest unclaimed receipt and every later one
     * that shares its token and app, up to [limit]. Grouping matters because `/v1/ingest` carries
     * `token` per event but the whole batch belongs to one subscription's worth of work — a token
     * rotation mid-queue must not merge two devices' receipts into one request.
     */
    fun nextBatch(limit: Int = BATCH_LIMIT): List<Receipt> = synchronized(lock) {
        if (retired) return emptyList()
        val head = pending.firstOrNull { it.dedupeKey !in claimed } ?: return emptyList()
        val batch = mutableListOf<Receipt>()
        for (receipt in pending) {
            if (batch.size == limit) break
            if (receipt.dedupeKey in claimed) continue
            if (receipt.token != head.token || receipt.appId != head.appId) continue
            batch += receipt
        }
        batch.forEach { claimed += it.dedupeKey }
        batch
    }

    /** The server took [batch]: drop it from the queue and remember it in the bounded dedupe set. */
    fun markSent(batch: List<Receipt>) = synchronized(lock) {
        if (retired) return
        batch.forEach { receipt ->
            claimed -= receipt.dedupeKey
            pending.removeAll { it.dedupeKey == receipt.dedupeKey }
            // Re-inserting after a remove moves the key to the end, so a key the server took twice
            // is the most recent one rather than the oldest candidate for eviction.
            sent -= receipt.dedupeKey
            sent += receipt.dedupeKey
        }
        while (sent.size > SENT_CAP) sent.remove(sent.first())
        persistPendingLocked()
        persistSentLocked()
    }

    /**
     * The request did not land but may next time: keep [batch] where it is and count the attempt,
     * and a server failure when [serverAnswered]. Then evicts each receipt of [batch] that has reached
     * [MAX_SERVER_FAILURES] or is older than [MAX_AGE_MS]. Returns how many were evicted.
     */
    fun markFailed(batch: List<Receipt>, serverAnswered: Boolean = true): Int = synchronized(lock) {
        if (retired) return 0
        val at = now()
        val cutoff = at - MAX_AGE_MS
        var evicted = 0
        batch.forEach { receipt ->
            claimed -= receipt.dedupeKey
            val entry = pending.firstOrNull { it.dedupeKey == receipt.dedupeKey } ?: return@forEach
            entry.attempts++
            if (serverAnswered &&
                (entry.serverFailures == 0 || at - entry.lastServerFailureAt >= SERVER_FAILURE_SPACING_MS)
            ) {
                entry.serverFailures++
                entry.lastServerFailureAt = at
            }
            if (entry.serverFailures >= MAX_SERVER_FAILURES || entry.queuedAt < cutoff) {
                pending.remove(entry)
                evicted++
            }
        }
        persistPendingLocked()
        evicted
    }

    /** The server refused [batch] for good: forget it without recording it as sent. */
    fun drop(batch: List<Receipt>) = synchronized(lock) {
        if (retired) return
        batch.forEach { receipt ->
            claimed -= receipt.dedupeKey
            pending.removeAll { it.dedupeKey == receipt.dedupeKey }
        }
        persistPendingLocked()
    }

    /** Forgets every receipt and every dedupe key, on disk as well as in memory. */
    fun clear() = synchronized(lock) {
        pending.clear()
        sent.clear()
        claimed.clear()
        prefs?.edit()?.remove(key)?.remove(sentKey)?.apply()
    }

    /** Releases a network claim without changing attempts or durable state. */
    fun releaseClaims(batch: List<Receipt>) = synchronized(lock) {
        batch.forEach { claimed -= it.dedupeKey }
    }

    /**
     * Makes an old process-local instance permanently read-only. In-flight responses may still
     * reach it, so every mutating method checks this bit before touching preferences.
     */
    fun retire() = synchronized(lock) {
        retired = true
        claimed.clear()
    }

    /** Retires the instance and removes the workspace's queue/history in one queue-locked step. */
    fun retireAndClear() = synchronized(lock) {
        retired = true
        pending.clear()
        sent.clear()
        claimed.clear()
        prefs?.edit()?.remove(key)?.remove(sentKey)?.apply()
    }

    private fun load() = synchronized(lock) {
        val preferences = prefs ?: return
        runCatching {
            val array = JSONArray(preferences.getString(key, null) ?: "[]")
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val type = entry.optString("type")
                val messageId = entry.optString("message_id")
                val token = entry.optString("token")
                val appId = entry.optString("app")
                if (type.isEmpty() || messageId.isEmpty() || token.isEmpty() || appId.isEmpty()) continue
                pending += Receipt(
                    type = type,
                    messageId = messageId,
                    token = token,
                    appId = appId,
                    queuedAt = entry.optLong("queued_at", now()),
                    attempts = entry.optInt("attempts", 0),
                    serverFailures = entry.optInt("server_failures", 0),
                    lastServerFailureAt = entry.optLong("last_server_failure_at", 0L),
                )
            }
        }.onFailure { OpenPush.onLog("stored receipts ignored — invalid JSON") }
        val pendingTrimmed = trimPendingLocked()
        runCatching {
            val array = JSONArray(preferences.getString(sentKey, null) ?: "[]")
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotEmpty() }?.let { sent += it }
            }
        }.onFailure { OpenPush.onLog("stored receipt history ignored — invalid JSON") }
        while (sent.size > SENT_CAP) sent.remove(sent.first())
        if (pendingTrimmed) persistPendingLocked()
    }

    private fun trimPendingLocked(): Boolean {
        var overflowed = false
        while (pending.size > PENDING_CAP) {
            val removed = pending.removeAt(0)
            claimed -= removed.dedupeKey
            overflowed = true
        }
        if (overflowed && !pendingCapWarned) {
            pendingCapWarned = true
            OpenPush.onLog("receipts over $PENDING_CAP queued — dropping the oldest")
        }
        return overflowed
    }

    private fun persistPendingLocked() {
        val editor = prefs?.edit() ?: return
        if (pending.isEmpty()) {
            editor.remove(key)
        } else {
            val array = JSONArray()
            pending.forEach { receipt ->
                array.put(
                    JSONObject()
                        .put("type", receipt.type)
                        .put("message_id", receipt.messageId)
                        .put("token", receipt.token)
                        .put("app", receipt.appId)
                        .put("queued_at", receipt.queuedAt)
                        .put("attempts", receipt.attempts)
                        .put("server_failures", receipt.serverFailures)
                        .put("last_server_failure_at", receipt.lastServerFailureAt),
                )
            }
            editor.putString(key, array.toString())
        }
        editor.apply()
    }

    private fun persistSentLocked() {
        val editor = prefs?.edit() ?: return
        if (sent.isEmpty()) editor.remove(sentKey) else editor.putString(sentKey, JSONArray(sent.toList()).toString())
        editor.apply()
    }

    internal companion object {
        /** `/v1/ingest` takes a batch; fifty is the same ceiling the custom-event queue posts under. */
        const val BATCH_LIMIT = 50

        /** How many receipts can remain owed while a device is offline. */
        const val PENDING_CAP = 500

        /** How many accepted `type|message_id` keys the dedupe set remembers. */
        const val SENT_CAP = 500

        /** Retryable server failures (5xx, 429) after which a receipt is given up. */
        const val MAX_SERVER_FAILURES = 10

        /** The least time between two server failures that both count toward [MAX_SERVER_FAILURES]. */
        const val SERVER_FAILURE_SPACING_MS = 60L * 60 * 1000

        /** How long a receipt may stay owed before a failed drain gives it up: seven days. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
