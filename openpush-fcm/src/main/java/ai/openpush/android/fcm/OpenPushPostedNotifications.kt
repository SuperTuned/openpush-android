package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import ai.openpush.android.ShadeManager
import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject

/** The bounded, durable child index; generated group summaries are intentionally not recorded. */
internal class OpenPushPostedNotifications(
    context: Context,
    private val manager: NotificationManager =
        requireNotNull(context.applicationContext.getSystemService(NotificationManager::class.java)),
    private val activeIds: () -> Set<Int> = { manager.activeNotifications.mapTo(mutableSetOf()) { it.id } },
) {
    internal data class Entry(
        val notificationId: Int,
        val messageId: String,
        val group: String?,
        val postedAt: Long,
        val tag: String? = null,
    )

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun record(
        notificationId: Int,
        messageId: String,
        group: String?,
        postedAt: Long = System.currentTimeMillis(),
        tag: String? = null,
    ) = synchronized(LOCK) {
        val entries = read()
        prune(entries)
        entries[notificationId] = Entry(notificationId, messageId, group, postedAt, tag)
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.values.minWithOrNull(compareBy<Entry> { it.postedAt }.thenBy { it.notificationId })
                ?: break
            entries.remove(oldest.notificationId)
        }
        write(entries)
    }

    fun entries(): List<Entry> = synchronized(LOCK) {
        val entries = read()
        if (prune(entries)) write(entries)
        entries.values.sortedWith(compareBy<Entry> { it.postedAt }.thenBy { it.notificationId })
    }

    fun clearAll() = synchronized(LOCK) {
        manager.cancelAll()
        preferences.edit().remove(ENTRIES_KEY).apply()
    }

    fun remove(notificationId: Int) = synchronized(LOCK) {
        val entries = read()
        val pruned = prune(entries)
        val removed = entries.remove(notificationId)
        manager.cancel(notificationId)
        // A replacing push is posted under its collapse key as the tag, which an untagged cancel misses.
        removed?.tag?.let { manager.cancel(it, notificationId) }
        if (removed != null) {
            removed.group?.let { group ->
                if (entries.values.count { it.group == group } < MIN_CHILDREN_FOR_SUMMARY) {
                    manager.cancel(summaryNotificationId(group))
                }
            }
        }
        if (removed != null || pruned) {
            write(entries)
        }
    }

    fun removeGroup(group: String) = synchronized(LOCK) {
        val entries = read()
        prune(entries)
        val matching = entries.values.filter { it.group == group }
        matching.forEach {
            manager.cancel(it.tag, it.notificationId)
            entries.remove(it.notificationId)
        }
        manager.cancel(summaryNotificationId(group))
        write(entries)
    }

    private fun prune(entries: MutableMap<Int, Entry>): Boolean {
        val active = runCatching(activeIds).getOrElse {
            OpenPush.onLog("posted notification registry could not inspect the shade: ${it.message}")
            return false
        }
        return entries.keys.retainAll(active)
    }

    private fun read(): MutableMap<Int, Entry> {
        val raw = preferences.getString(ENTRIES_KEY, null) ?: return linkedMapOf()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { idKey ->
                    val value = json.optJSONObject(idKey) ?: return@forEach
                    val notificationId = idKey.toIntOrNull() ?: return@forEach
                    val messageId = value.optString(MESSAGE_ID_KEY).takeIf { it.isNotEmpty() } ?: return@forEach
                    put(
                        notificationId,
                        Entry(
                            notificationId = notificationId,
                            messageId = messageId,
                            group = value.optString(GROUP_KEY).takeIf { it.isNotEmpty() },
                            postedAt = value.optLong(POSTED_AT_KEY),
                            tag = value.optString(TAG_KEY).takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }.toMutableMap()
        }.getOrElse {
            OpenPush.onLog("posted notification registry ignored invalid JSON")
            linkedMapOf()
        }
    }

    private fun write(entries: Map<Int, Entry>) {
        if (entries.isEmpty()) {
            preferences.edit().remove(ENTRIES_KEY).apply()
            return
        }
        val json = JSONObject()
        entries.values.forEach { entry ->
            json.put(
                entry.notificationId.toString(),
                JSONObject()
                    .put(MESSAGE_ID_KEY, entry.messageId)
                    .put(POSTED_AT_KEY, entry.postedAt)
                    .also { value -> entry.group?.let { value.put(GROUP_KEY, it) } }
                    .also { value -> entry.tag?.let { value.put(TAG_KEY, it) } },
            )
        }
        preferences.edit().putString(ENTRIES_KEY, json.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "openpush.posted"
        const val ENTRIES_KEY = "notifications"
        const val MESSAGE_ID_KEY = "messageId"
        const val GROUP_KEY = "group"
        const val POSTED_AT_KEY = "postedAt"
        const val TAG_KEY = "tag"
        const val MAX_ENTRIES = 100
        val LOCK = Any()
    }
}

internal const val MIN_CHILDREN_FOR_SUMMARY = 2

internal fun summaryNotificationId(group: String): Int = "summary|$group".hashCode()

@OptIn(OpenPushInternalApi::class)
internal object OpenPushFcmShadeManager : ShadeManager {
    override fun clearAll(context: Context) = OpenPushPostedNotifications(context).clearAll()

    override fun remove(context: Context, notificationId: Int) =
        OpenPushPostedNotifications(context).remove(notificationId)

    override fun removeGroup(context: Context, group: String) = OpenPushPostedNotifications(context).removeGroup(group)
}

@OptIn(OpenPushInternalApi::class)
internal fun installOpenPushShadeManager() {
    OpenPushBridge.shadeManager = OpenPushFcmShadeManager
}
