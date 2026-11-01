package ai.openpush.android.inappmessages

import android.app.Activity

/**
 * One in-app message on screen. The engine owns eligibility, receipts and lifecycle; a presenter
 * owns nothing but the surface, so the engine never touches a WebView and a test can stand in for
 * the popup.
 */
internal interface IamPresenter {
    /** Loads [url] with [headers] and reports back through [events]. */
    fun show(message: IamMessage, url: String, headers: Map<String, String>, events: (PresenterEvent) -> Unit)

    /**
     * Shows a document that is already in hand, over [activity], and reports back through [events]
     * exactly as [show] does.
     *
     * This is the developer preview path: the document comes from the app's own assets rather than
     * the server, so there is no URL to load, no request headers to send, and — because the caller
     * may be an app that has never attached a workspace — no reliance on the SDK knowing which
     * Activity is up. Everything after the load is identical, which is the point: the bridge, the
     * layout, the link handling and the teardown are the shipping ones.
     */
    fun preview(activity: Activity, message: IamMessage, html: String, events: (PresenterEvent) -> Unit)

    /** Takes the surface down without reporting a dismissal. */
    fun hide()
}

/**
 * One `action_taken` envelope, already parsed and nothing more. The document asks for things the
 * host owns — a permission prompt, tags, outcomes, a URL — and they are carried here as plain data
 * so that the engine, not the bridge, decides whether and how to do them.
 */
internal data class IamAction(
    val clickId: String? = null,
    val blockKey: String? = null,
    val actionId: String? = null,
    val url: String? = null,
    val urlTarget: String? = null,
    val closes: Boolean = false,
    /** Permission prompts the document asked for, in the order it listed them. */
    val prompts: List<String> = emptyList(),
    /** `tags.adds`: tags to set on this user. */
    val tags: Map<String, String> = emptyMap(),
    /** `outcomes[].name`: custom events to track. */
    val outcomes: List<String> = emptyList(),
)

/** Everything a presenter can tell the engine. */
internal sealed class PresenterEvent {
    data class Rendered(val location: String, val heightDp: Int, val dragToDismissDisabled: Boolean) : PresenterEvent()

    data class PageChanged(val pageId: String, val index: Int) : PresenterEvent()

    data class Action(val action: IamAction) : PresenterEvent()

    data class Failed(val reason: String) : PresenterEvent()

    data object Dismissed : PresenterEvent()
}
