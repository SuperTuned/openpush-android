package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushPayload
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * The invisible trampoline a tapped notification opens. It reports the click — including the id of
 * the action that was tapped, which the renderer put in the extras — and then either opens the
 * push's launch URL, or brings the app up the way the launcher icon does, carrying the
 * notification's extras into the host's own entry point.
 *
 * A push with a launch URL opens that URL straight away and does not start the app, so a browser
 * link is not held up behind the app's own start. A tapped action button with its own `url` opens
 * that instead of the push's launch URL; a button without one behaves like the body. A custom
 * scheme reaches the host's own intent filter the same way. Set the manifest meta-data
 * [META_SUPPRESS_LAUNCH_URLS] to `true` to open the app instead and handle the URL in a click
 * listener, where [ai.openpush.android.ClickEvent.actionUrl] carries the button's link.
 */
class OpenPushClickActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val enveloped = extras?.get(EXTRA_RAW_DATA) as? Bundle
        val data = if (enveloped != null) {
            enveloped.stringMap()
        } else {
            // Accept click intents produced by earlier SDK builds and simple host tests. New
            // renderer intents use the envelope, where a legitimate raw key can have either of
            // these internal spellings without colliding with transport metadata.
            extras?.let {
                extras.keySet()
                    .filterNot { it == EXTRA_COLLAPSE_KEY }
                    .mapNotNull { key -> extras.getString(key)?.let { key to it } }
                    .toMap()
            }.orEmpty()
        }
        val actionUrl = data[OpenPush.ACTION_ID_KEY]?.let { actionId ->
            OpenPushPayload.actions(data).firstOrNull { it.id == actionId }?.url
        }
        val urlOpened = openLaunchUrl(actionUrl ?: data[ai.openpush.android.OpenPushNotification.KEY_LAUNCH_URL])
        val notification = ai.openpush.android.OpenPushNotification.fromData(
            if (urlOpened) data + (OpenPush.LAUNCH_URL_OPENED_KEY to "true") else data,
            intent.getStringExtra(EXTRA_COLLAPSE_KEY),
        )
        OpenPush.Notifications.processClick(notification)
        if (urlOpened) {
            finish()
            return
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            // Launcher semantics: resume the existing task as the home-screen icon would, rather
            // than CLEAR_TOP, which tore down the back stack above the launch Activity and lost
            // whatever the host had open. RESET_TASK_IF_NEEDED applies the manifest's own rules.
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            data.forEach { (key, value) -> launch.putExtra(key, value) }
            startActivity(launch)
        }
        finish()
    }

    /** Opens [url] in whatever handles it; false when there is none, it is suppressed, or it fails. */
    private fun openLaunchUrl(url: String?): Boolean {
        val spelled = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (launchUrlsSuppressed()) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(spelled)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            OpenPush.onLog("launch url has no handler on this device — opening the app instead")
            false
        } catch (e: RuntimeException) {
            OpenPush.onLog("launch url could not be opened (${e.message}) — opening the app instead")
            false
        }
    }

    private fun launchUrlsSuppressed(): Boolean = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData?.getBoolean(META_SUPPRESS_LAUNCH_URLS, false) == true
    }.getOrDefault(false)

    companion object {
        /** Manifest meta-data: `true` opens the app on tap even when the push has a launch URL. */
        const val META_SUPPRESS_LAUNCH_URLS = "ai.openpush.suppress_launch_urls"

        internal fun pendingIntent(
            context: Context,
            data: Map<String, String>,
            requestCode: Int,
            collapseKey: String? = null,
        ): PendingIntent {
            val tap = Intent(context, OpenPushClickActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(
                    EXTRA_RAW_DATA,
                    Bundle().apply { data.forEach { (key, value) -> putString(key, value) } },
                )
            collapseKey?.let { tap.putExtra(EXTRA_COLLAPSE_KEY, it) }
            return PendingIntent.getActivity(
                context,
                requestCode,
                tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal const val EXTRA_COLLAPSE_KEY = "ai.openpush.android.fcm.extra.COLLAPSE_KEY"
        internal const val EXTRA_RAW_DATA = "ai.openpush.android.fcm.extra.RAW_DATA"

        private fun Bundle.stringMap(): Map<String, String> =
            keySet().mapNotNull { key -> getString(key)?.let { key to it } }.toMap()
    }
}
