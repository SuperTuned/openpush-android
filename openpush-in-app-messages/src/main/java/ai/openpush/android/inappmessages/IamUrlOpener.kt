package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * The one place a link from a message document is opened.
 *
 * Two rules, and both are about not letting a remote document steer the host app:
 * - **Only `http` and `https`.** A message is server-authored HTML; a `javascript:`, `intent:` or
 *   `file:` URL in an action is either a mistake or an attempt to reach something the document has
 *   no business reaching, so it is logged and dropped rather than handed to the system.
 * - **Nothing throws.** A device with no browser, a disabled package, a URL nothing resolves —
 *   every one of them is a dead link, not a crashed host app.
 *
 * `url_target == "webview"` asks for the link to open *in* the app rather than in a separate
 * browser task. A Custom Tab is that: the user's own browser, over this Activity, with the app's
 * back stack intact. When no browser on the device supports Custom Tabs there is nothing to fall
 * back to but the browser itself.
 */
internal object IamUrlOpener {
    /** `url_target` asking to stay inside the app. */
    private const val IN_APP_TARGET = "webview"

    private val WEB_SCHEMES = setOf("http", "https")

    /** Whether [uri] is something this opener will open. */
    fun isWebLink(uri: Uri): Boolean = uri.scheme?.lowercase() in WEB_SCHEMES

    /** Returns whether [url] was handed to something that will show it. */
    fun open(activity: Activity, url: String, target: String?): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || !isWebLink(uri)) {
            OpenPush.onLog("✗ in-app link ignored — only http(s) links are opened")
            return false
        }
        return runCatching {
            if (target == IN_APP_TARGET && CustomTabsClient.getPackageName(activity, emptyList()) != null) {
                CustomTabsIntent.Builder().build().launchUrl(activity, uri)
            } else {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            true
        }.getOrElse {
            OpenPush.onLog("✗ in-app link could not be opened — nothing on the device handles it")
            false
        }
    }
}
