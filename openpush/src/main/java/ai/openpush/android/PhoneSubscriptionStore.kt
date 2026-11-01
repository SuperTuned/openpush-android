package ai.openpush.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one app's phone numbers need on disk: the writes the server has not accepted yet, so an
 * offline add or remove survives process death, and the subscription id the server returned for each
 * (platform, number) pair. Keys are namespaced by app id, so switching workspaces neither sends one
 * app's numbers to another nor loses them. Without preferences nothing is written: pending writes then
 * live only in [PhoneSubscriptions]' memory, and no subscription id is kept.
 *
 * Not thread-safe: [PhoneSubscriptions] calls it under its own lock.
 */
internal class PhoneSubscriptionStore(private val prefs: SharedPreferences?, appId: String) {
    private val pendingKey = "openpush.phone.$appId.pending"
    private val idsKey = "openpush.phone.$appId.subscriptionIds"

    fun loadPending(): List<PhoneSubscriptionWrite> {
        val raw = prefs?.getString(pendingKey, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(PhoneSubscriptionWrite::fromJson)
        }
    }

    fun savePending(writes: Collection<PhoneSubscriptionWrite>) {
        val editor = prefs?.edit() ?: return
        if (writes.isEmpty()) {
            editor.remove(pendingKey).apply()
        } else {
            editor.putString(pendingKey, JSONArray(writes.map(PhoneSubscriptionWrite::toJson)).toString()).apply()
        }
    }

    /** The subscription id the server last returned for [key] (see [PhoneSubscriptionWrite.key]). */
    fun subscriptionId(key: String): String? = ids().optString(key).takeIf(String::isNotEmpty)

    fun recordSubscriptionId(key: String, id: String) {
        val editor = prefs?.edit() ?: return
        editor.putString(idsKey, ids().put(key, id).toString()).apply()
    }

    private fun ids(): JSONObject =
        prefs?.getString(idsKey, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
}
