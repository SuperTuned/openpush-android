package ai.openpush.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receipts recorded before OpenPush is attached, held until `initialize` binds a workspace.
 *
 * A tap on a notification can start the process and report its click before the host has called
 * `initialize` — wrapper SDKs initialize late — and a push to a killed app owes its delivery receipt
 * just as early. There is no workspace, token or [ReceiptQueue] yet, so the receipt waits here and
 * is moved into the queue by the first attach that has a token.
 *
 * Entries carry no app id or token: whichever workspace attaches first receives them. The area is
 * bounded to the newest [CAP] entries, collapses a repeated `type|message_id`, and lands in
 * SharedPreferences as soon as [prefs] can supply them, so a process death between the tap and the
 * host's `initialize` does not lose the receipt. Until then it is held in memory and merged with
 * whatever an earlier process left on disk. Every method is safe to call from any thread.
 */
internal class PendingReceipts(
    private val prefs: () -> SharedPreferences?,
    private val key: String = KEY,
    private val now: () -> Long,
) {
    /** One receipt recorded before attach, with the moment it was recorded. */
    internal data class Entry(val type: String, val messageId: String, val recordedAt: Long) {
        val dedupeKey: String get() = "$type|$messageId"
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var loaded = false

    /** How many receipts are waiting, including any an earlier process left on disk. */
    val size: Int get() = synchronized(lock) {
        loadLocked()
        entries.size
    }

    /** Stages a receipt. Returns false, and changes nothing, when the same one is already waiting. */
    fun stage(type: String, messageId: String): Boolean = synchronized(lock) {
        loadLocked()
        val entry = Entry(type, messageId, now())
        if (entries.any { it.dedupeKey == entry.dedupeKey }) return false
        entries += entry
        while (entries.size > CAP) entries.removeAt(0)
        persistLocked()
        true
    }

    /** Hands every waiting receipt to [sink], oldest first, then forgets them. */
    fun drain(sink: (Entry) -> Unit) = synchronized(lock) {
        loadLocked()
        if (entries.isEmpty()) return
        entries.forEach(sink)
        entries.clear()
        persistLocked()
    }

    // Merges what an earlier process persisted ahead of anything staged in memory before the
    // preferences became reachable, then writes the merged list back.
    private fun loadLocked() {
        if (loaded) return
        val preferences = prefs() ?: return
        loaded = true
        val stored = mutableListOf<Entry>()
        runCatching {
            val array = JSONArray(preferences.getString(key, null) ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val type = item.optString("type")
                val messageId = item.optString("message_id")
                if (type.isEmpty() || messageId.isEmpty()) continue
                stored += Entry(type, messageId, item.optLong("recorded_at", now()))
            }
        }.onFailure { OpenPush.onLog("staged receipts ignored — invalid JSON") }
        if (stored.isEmpty()) {
            if (entries.isNotEmpty()) persistLocked()
            return
        }
        val merged = (stored + entries).distinctBy { it.dedupeKey }.takeLast(CAP)
        entries.clear()
        entries += merged
        persistLocked()
    }

    private fun persistLocked() {
        val editor = (if (loaded) prefs() else null)?.edit() ?: return
        if (entries.isEmpty()) {
            editor.remove(key)
        } else {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject()
                        .put("type", entry.type)
                        .put("message_id", entry.messageId)
                        .put("recorded_at", entry.recordedAt),
                )
            }
            editor.putString(key, array.toString())
        }
        editor.apply()
    }

    internal companion object {
        /** How many receipts can wait for `initialize`; the oldest are dropped past this. */
        const val CAP = 50

        /** Not namespaced by workspace: no workspace is known when these are recorded. */
        const val KEY = "openpush.stagedReceipts"
    }
}
