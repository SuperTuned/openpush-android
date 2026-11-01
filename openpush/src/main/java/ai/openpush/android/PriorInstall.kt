package ai.openpush.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the push SDK a migrating app used before OpenPush left behind on this install: the provider
 * token it registered and the subscription id its server gave it. The server keeps that token as a
 * separate subscription, so until it hears about it the device holds two rows and every push lands
 * twice. Either field may be missing; an install with neither has nothing to report.
 */
internal data class PriorInstall(val token: String?, val subscriptionId: String?) {
    /** The `prior_install` object of the registration body. A missing field is omitted, not sent empty. */
    fun toJson(): JSONObject = JSONObject().apply {
        token?.let { put("token", it) }
        subscriptionId?.let { put("subscription_id", it) }
    }
}

/** Finds the [PriorInstall] on this device, or null when there is none. Never throws. */
internal fun interface PriorInstallSource {
    fun read(): PriorInstall?
}

/**
 * Reads the preferences file the previous push SDK kept in this app's sandbox. Read-only: the file is
 * never written, created or deleted, and anything it cannot parse reads as nothing.
 *
 * The current layout stores a JSON array of subscription models; the push entry carries the token as
 * `address` and the server id as `id`. An id the server never confirmed starts with `local-` and means
 * nothing to it. The older layout stores the id on its own, and the token inside the last synced user
 * state. A field the current layout lacks is filled from the older one.
 */
internal class SharedPreferencesPriorInstallSource(private val context: Context) : PriorInstallSource {
    override fun read(): PriorInstall? = try {
        val preferences = context.getSharedPreferences(PRIOR_PREFS_FILE, Context.MODE_PRIVATE)
        val current = currentLayout(preferences)
        val legacy = if (current?.token != null && current.subscriptionId != null) null else legacyLayout(preferences)
        PriorInstall(
            token = current?.token ?: legacy?.token,
            subscriptionId = current?.subscriptionId ?: legacy?.subscriptionId,
        ).takeIf { it.token != null || it.subscriptionId != null }
    } catch (_: Throwable) {
        null
    }

    private fun currentLayout(preferences: SharedPreferences): PriorInstall? {
        val raw = stringOrNull(preferences, PRIOR_SUBSCRIPTIONS_KEY) ?: return null
        val models = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            if (model.optString("type") != PUSH_TYPE) continue
            return PriorInstall(
                token = model.stringField("address")?.let(::usableToken),
                subscriptionId = model.stringField("id")?.let(::usableId),
            )
        }
        return null
    }

    private fun legacyLayout(preferences: SharedPreferences): PriorInstall {
        val id = stringOrNull(preferences, PRIOR_LEGACY_ID_KEY)?.let(::usableId)
        val token = stringOrNull(preferences, PRIOR_LEGACY_STATE_KEY)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.stringField("identifier")
            ?.let(::usableToken)
        return PriorInstall(token, id)
    }

    // A key holding another type throws from getString; that reads as absent.
    private fun stringOrNull(preferences: SharedPreferences, key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private fun JSONObject.stringField(name: String): String? = opt(name) as? String

    private fun usableToken(token: String): String? = token.takeIf(String::isNotBlank)

    private fun usableId(id: String): String? = id.takeIf { it.isNotBlank() && !it.startsWith(LOCAL_ID_PREFIX) }

    internal companion object {
        const val PRIOR_PREFS_FILE = "OneSignal"
        const val PRIOR_SUBSCRIPTIONS_KEY = "MODEL_STORE_subscriptions"
        const val PRIOR_LEGACY_ID_KEY = "GT_PLAYER_ID"
        const val PRIOR_LEGACY_STATE_KEY = "ONESIGNAL_USERSTATE_SYNCVALYES_CURRENT_STATE"
        private const val PUSH_TYPE = "PUSH"
        private const val LOCAL_ID_PREFIX = "local-"
    }
}
