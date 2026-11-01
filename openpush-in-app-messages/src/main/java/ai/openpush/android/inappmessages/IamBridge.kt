package ai.openpush.android.inappmessages

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * `window.OpenPushAndroid`, the one object the message document can reach from JavaScript.
 *
 * It is deliberately the dullest class in the module. A page loaded into the WebView is not trusted
 * code, and [postMessage] is called on a WebView-owned thread, so this class reads an envelope,
 * turns it into a [PresenterEvent] and hands it on. It touches no view, keeps no state, sends
 * nothing, and prompts for nothing — the engine decides what an action means, on the main thread.
 * An envelope it cannot read is dropped rather than guessed at.
 *
 * The three envelopes are `openpush/server`'s: `rendering_complete`, `page_change`, `action_taken`.
 */
internal class IamBridge(private val events: (PresenterEvent) -> Unit) {
    @JavascriptInterface
    fun postMessage(raw: String) {
        val event = parse(raw) ?: return
        events(event)
    }

    private fun parse(raw: String): PresenterEvent? {
        val envelope = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return when (envelope.optString("type")) {
            "rendering_complete" -> PresenterEvent.Rendered(
                envelope.optString("displayLocation").takeIf(String::isNotEmpty) ?: "center",
                envelope.optJSONObject("pageMetaData")?.optJSONObject("rect")?.optInt("height") ?: 0,
                envelope.optBoolean("dragToDismissDisabled"),
            )
            "page_change" -> envelope.optString("pageId")
                .takeIf(String::isNotEmpty)
                ?.let { PresenterEvent.PageChanged(it, envelope.optInt("pageIndex")) }
            "action_taken" -> envelope.optJSONObject("body")
                ?.let { PresenterEvent.Action(action(it)) }
            else -> null
        }
    }

    private fun action(body: JSONObject): IamAction = IamAction(
        clickId = body.optString("id").takeIf(String::isNotEmpty),
        blockKey = body.optString("block_key").takeIf(String::isNotEmpty),
        actionId = body.optString("name").takeIf(String::isNotEmpty),
        url = body.optString("url").takeIf(String::isNotEmpty),
        urlTarget = body.optString("url_target").takeIf(String::isNotEmpty),
        closes = body.optBoolean("close"),
        prompts = strings(body, "prompts"),
        tags = tags(body),
        outcomes = outcomes(body),
    )

    private fun strings(body: JSONObject, name: String): List<String> {
        val values = body.optJSONArray(name) ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotEmpty) }
    }

    private fun tags(body: JSONObject): Map<String, String> {
        val adds = body.optJSONObject("tags")?.optJSONObject("adds") ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        adds.keys().forEach { key -> values[key] = adds.optString(key) }
        return values
    }

    private fun outcomes(body: JSONObject): List<String> {
        val values = body.optJSONArray("outcomes") ?: return emptyList()
        return (0 until values.length()).mapNotNull {
            values.optJSONObject(it)?.optString("name")?.takeIf(String::isNotEmpty)
        }
    }

    companion object {
        /**
         * The JavaScript object name. `consumer-rules.pro` keeps this class's
         * `@JavascriptInterface` methods, so renaming either must change the other.
         */
        const val NAME = "OpenPushAndroid"
    }
}
