package ai.openpush.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The durable record of OpenPush message ids this device has finished handling. The server retries
 * a send whose outcome it could not see, and the provider has no idempotency key, so a push that did
 * arrive the first time can arrive again. Install a replacement through
 * [OpenPushBridge.receivedMessageLog].
 */
@OpenPushInternalApi
interface ReceivedMessageLog {
    /** True when [messageId] was recorded and has not yet expired. */
    fun contains(messageId: String): Boolean

    /** Records [messageId] as handled, now. */
    fun record(messageId: String)
}

/**
 * Admits each OpenPush message id once. [begin] refuses an id another thread is still handling (an
 * in-memory guard, so two copies delivered together cannot both pass) or one the log already holds;
 * [finish] records the id once handling is over and only then releases the guard, so there is no
 * moment at which a copy could slip between the two. Recording at the end, not on arrival, means a
 * process that dies mid-handling has not used the id up, and a later copy can still show the push.
 *
 * Live Updates are let through unrecorded: a journey can resend one message id for a Live Update on
 * a later run, and the renderer's sequence gate already drops a repeated frame. Storage failures
 * fail open — a push is never dropped because its id could not be checked.
 */
@OptIn(OpenPushInternalApi::class)
internal object ReceivedMessages {
    private const val LIVE_UPDATE_KEY = "live_notification"

    // Ids admitted by begin and not yet finished. Guarded by itself.
    private val inFlight = HashSet<String>()

    fun begin(context: Context?, data: Map<String, String>): Boolean {
        val messageId = trackedId(data) ?: return true
        synchronized(inFlight) {
            if (messageId in inFlight) {
                OpenPush.onLog("duplicate push $messageId ignored — the first copy is still being handled")
                return false
            }
            val seen = runCatching { log(context)?.contains(messageId) == true }.getOrElse {
                OpenPush.onLog("duplicate check failed for push $messageId — handling it: ${it.message}")
                false
            }
            if (seen) {
                OpenPush.onLog("duplicate push $messageId ignored")
                return false
            }
            inFlight += messageId
        }
        return true
    }

    fun finish(context: Context?, data: Map<String, String>, handled: Boolean) {
        val messageId = trackedId(data) ?: return
        try {
            if (handled) {
                val log = log(context)
                if (log == null) {
                    OpenPush.onLog(
                        "push $messageId not recorded — no context before initialize; repeats are caught while it is handled",
                    )
                } else {
                    runCatching { log.record(messageId) }
                        .onFailure { OpenPush.onLog("push $messageId could not be recorded: ${it.message}") }
                }
            }
        } finally {
            synchronized(inFlight) { inFlight -= messageId }
        }
    }

    internal fun resetForTests() = synchronized(inFlight) { inFlight.clear() }

    private fun trackedId(data: Map<String, String>): String? =
        if (data.containsKey(LIVE_UPDATE_KEY)) null else OpenPushPayload.messageId(data)

    private fun log(context: Context?): ReceivedMessageLog? =
        OpenPushBridge.receivedMessageLog ?: context?.let(PreferencesReceivedMessageLog::shared)
}

/**
 * [ReceivedMessageLog] kept as one JSON object of id to record time in SharedPreferences — the store
 * the SDK already uses, and small at [maxEntries] ids, so a table would buy nothing. The in-memory
 * copy is loaded once and is authoritative for the process; each record is written back
 * synchronously, because the provider can stop a killed app's process right after the delivery.
 * Ids expire [retentionMillis] after they were recorded and are pruned on write; [maxEntries] is
 * only a safety bound that evicts the oldest.
 *
 * Storage failures fail open: an unreadable record is treated as empty and a failed write is only
 * logged, leaving the id known to this process alone.
 */
@OptIn(OpenPushInternalApi::class)
internal class PreferencesReceivedMessageLog(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = RETENTION_MILLIS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val preferences: () -> SharedPreferences = {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    },
) : ReceivedMessageLog {
    internal val application: Context = context.applicationContext ?: context

    // Message id to the time it was recorded. Guarded by LOCK.
    private val recorded = HashMap<String, Long>()
    private var loaded = false

    override fun contains(messageId: String): Boolean = synchronized(LOCK) {
        loadOnce()
        val recordedAt = recorded[messageId] ?: return false
        !isExpired(recordedAt, clock())
    }

    override fun record(messageId: String) = synchronized(LOCK) {
        loadOnce()
        val now = clock()
        recorded.values.removeAll { isExpired(it, now) }
        recorded[messageId] = now
        while (recorded.size > maxEntries) {
            val oldest =
                recorded.entries.minWithOrNull(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    ?: break
            recorded.remove(oldest.key)
        }
        persist()
    }

    private fun isExpired(recordedAt: Long, now: Long) = now - recordedAt >= retentionMillis

    private fun loadOnce() {
        if (loaded) return
        loaded = true
        val raw = runCatching { preferences().getString(ENTRIES_KEY, null) }.getOrElse {
            OpenPush.onLog("received message record unreadable — starting empty: ${it.message}")
            return
        } ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { id ->
                val recordedAt = json.optLong(id, -1L)
                if (id.isNotEmpty() && recordedAt >= 0) recorded[id] = recordedAt
            }
        }.onFailure { OpenPush.onLog("received message record ignored invalid JSON") }
    }

    private fun persist() {
        val written = runCatching {
            val json = JSONObject()
            recorded.forEach { (id, recordedAt) -> json.put(id, recordedAt) }
            preferences().edit().putString(ENTRIES_KEY, json.toString()).commit()
        }.getOrElse { false }
        if (!written) {
            OpenPush.onLog(
                "received message record could not be saved; repeats are caught in this process only",
            )
        }
    }

    internal companion object {
        const val PREFERENCES_NAME = "openpush.received"
        const val ENTRIES_KEY = "messages"
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 1000
        private val LOCK = Any()

        @Volatile private var shared: PreferencesReceivedMessageLog? = null

        /** One instance per application context, so the loaded copy is shared by every caller. */
        fun shared(context: Context): PreferencesReceivedMessageLog {
            val application = context.applicationContext ?: context
            shared?.takeIf { it.application === application }?.let { return it }
            return synchronized(LOCK) {
                shared?.takeIf { it.application === application }
                    ?: PreferencesReceivedMessageLog(application).also { shared = it }
            }
        }
    }
}
