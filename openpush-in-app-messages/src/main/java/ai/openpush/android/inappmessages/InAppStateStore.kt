package ai.openpush.android.inappmessages

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the engine must remember about in-app messages between launches.
 *
 * One instance per workspace: the [key] carries the configuration's `tokenKey`, so two apps sharing
 * a process — or one app switching workspaces — never read each other's dismissals. It is built at
 * attach, not at process start, because the `tokenKey` is not known before then.
 */
internal class InAppStateStore(
    private val prefs: SharedPreferences,
    private val key: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Redisplay(var at: Long, var quantity: Int)

    private val lock = Any()

    val dismissed = mutableSetOf<String>()

    /** `"<messageId>#<clickId>"`, so one set answers both dedupe and "was this the first click". */
    val clicked = mutableSetOf<String>()

    val impressions = mutableSetOf<String>()

    /** `"<messageId>|<pageId>"` — a page id can itself contain a `#`, a click id cannot. */
    val pageViews = mutableSetOf<String>()

    val redisplay = mutableMapOf<String, Redisplay>()

    val displayedGeneration = mutableMapOf<String, Long>()

    var lastDismissed: Long? = null

    fun <T> withLock(block: InAppStateStore.() -> T): T = synchronized(lock) { block() }

    fun load() = withLock {
        val raw = prefs.getString(key, null) ?: return@withLock
        runCatching {
            val root = JSONObject(raw)
            readInto(root, "dismissed", dismissed)
            readInto(root, "clicked", clicked)
            readInto(root, "impressions", impressions)
            readInto(root, "pageViews", pageViews)
            root.optJSONObject("redisplay")?.let { values ->
                values.keys().forEach { messageId ->
                    val record = values.optJSONObject(messageId) ?: return@forEach
                    redisplay[messageId] = Redisplay(record.optLong("at"), record.optInt("quantity"))
                }
            }
            root.optJSONObject("displayedGeneration")?.let { values ->
                values.keys().forEach { messageId ->
                    displayedGeneration[messageId] = values.optLong(messageId)
                }
            }
            lastDismissed = root.optLong("lastDismissed").takeIf { it > 0 }
        }
        Unit
    }

    fun save() = withLock {
        val redisplayJson = JSONObject()
        redisplay.forEach { (messageId, record) ->
            redisplayJson.put(messageId, JSONObject().put("at", record.at).put("quantity", record.quantity))
        }
        val generationJson = JSONObject()
        displayedGeneration.forEach { (messageId, generation) -> generationJson.put(messageId, generation) }
        val root = JSONObject()
            .put("dismissed", JSONArray(dismissed.toList()))
            .put("clicked", JSONArray(clicked.toList()))
            .put("impressions", JSONArray(impressions.toList()))
            .put("pageViews", JSONArray(pageViews.toList()))
            .put("redisplay", redisplayJson)
            .put("displayedGeneration", generationJson)
            .put("lastDismissed", lastDismissed ?: 0)
        prefs.edit().putString(key, root.toString()).apply()
    }

    /**
     * Drops every message the engine has not touched in [RETENTION_MS]. The redisplay record is the
     * one entry that carries a timestamp, so it decides: once it ages out, the dedupe sets lose that
     * message too rather than growing without bound.
     */
    fun purge() = withLock {
        val cutoff = now() - RETENTION_MS
        val expired = redisplay.filterValues { it.at < cutoff }.keys.toSet()
        if (expired.isNotEmpty()) {
            redisplay.keys.removeAll(expired)
            dismissed.removeAll(expired)
            impressions.removeAll(expired)
            displayedGeneration.keys.removeAll(expired)
            clicked.removeAll { it.substringBefore('#') in expired }
            pageViews.removeAll { it.substringBefore('|') in expired }
        }
        if ((lastDismissed ?: 0) < cutoff) lastDismissed = null
        Unit
    }

    private fun readInto(root: JSONObject, name: String, target: MutableSet<String>) {
        val values = root.optJSONArray(name) ?: return
        for (index in 0 until values.length()) target += values.getString(index)
    }

    companion object {
        /** SharedPreferences file; the workspace lives in the entry key, not the file name. */
        const val PREFS = "openpush.iam"

        const val RETENTION_MS = 180L * 24 * 60 * 60 * 1000
    }
}
