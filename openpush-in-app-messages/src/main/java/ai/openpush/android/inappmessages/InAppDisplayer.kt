package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushBridge
import ai.openpush.android.OpenPushInternalApi
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupWindow

/**
 * The shipping presenter: the message document in a WebView inside a full-screen popup.
 *
 * It owns the surface and nothing else. Every decision — whether a message may be shown, what a
 * click means, what the host is told — belongs to [IamEngine]; this class reports what happened
 * through [PresenterEvent]s and waits to be told what to do next.
 *
 * Two things it does own, because only it can:
 * - **The main thread.** [show] and [hide] can be called from anywhere, so each hops to
 *   the main looper before it touches a view.
 * - **The difference between teardown and a user dismissal.** A `PopupWindow` fires the same
 *   listener either way — including when the Activity is destroyed under it — so [programmatic]
 *   marks teardown started by [hide] and only the rest is reported as a dismissal.
 */
@OptIn(OpenPushInternalApi::class)
internal class PopupPresenter : IamPresenter {
    private val handler = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null
    private var container: FrameLayout? = null
    private var web: WebView? = null
    private var events: ((PresenterEvent) -> Unit)? = null

    /** The height the layout has to place a banner within, captured while the popup fills it. */
    private var screenHeightPx = 0

    /** True while a teardown this class started is in flight, so its own listener stays quiet. */
    private var programmatic = false

    override fun show(
        message: IamMessage,
        url: String,
        headers: Map<String, String>,
        events: (PresenterEvent) -> Unit,
    ) = onMain {
        val activity = OpenPushBridge.currentActivity
        if (activity == null) {
            this.events = events
            events(PresenterEvent.Failed("no visible activity"))
            return@onMain
        }
        build(activity, events) { view -> view.loadUrl(url, headers) }
    }

    /**
     * The same surface, loading a document the caller already has.
     *
     * The document is passed to the WebView as data with a null base URL rather than as a
     * `file:///android_asset/…` URL: [harden] shuts file access off for every document this SDK
     * shows, and a preview is not a reason to open it again. A null base URL also leaves the
     * document at a unique opaque origin, so it can reach neither the app's files nor the network
     * by relative path — which is exactly what a self-contained preview fixture needs.
     */
    override fun preview(activity: Activity, message: IamMessage, html: String, events: (PresenterEvent) -> Unit) =
        onMain {
            build(activity, events) { view ->
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }

    private fun build(activity: Activity, events: (PresenterEvent) -> Unit, load: (WebView) -> Unit) {
        this.events = events
        programmatic = false
        // The document reports its display location and its height in `rendering_complete`, so
        // everything here is what the surface looks like *before* it has anything to show:
        // full-screen, transparent, and taking no focus, so nothing flashes over the app and the
        // back key still belongs to the Activity until a modal asks for it.
        val report = layoutWatching(events)
        // Hosts such as Unity ship android:hardwareAccelerated="false" on their activity. A
        // transparent WebView in a software window paints an opaque white box, so the window is
        // switched to hardware acceleration before the popup attaches.
        // By Android design the flag stays on this window for the activity's lifetime (not clearable once attached).
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        )
        val view = WebView(activity)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.harden()
        WebView.setWebContentsDebuggingEnabled(OpenPushBridge.isDebuggable)
        view.webViewClient = IamDocumentClient(report)
        view.addJavascriptInterface(IamBridge(report), IamBridge.NAME)
        val host = FrameLayout(activity)
        host.setBackgroundColor(Color.TRANSPARENT)
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val window = PopupWindow(
            host,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            false,
        )
        window.setOnDismissListener {
            if (!programmatic) events(PresenterEvent.Dismissed)
        }
        popup = window
        container = host
        web = view
        val decor = activity.window.decorView
        screenHeightPx = decor.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        load(view)
        // Anchored at the top-left corner, not centred: a `PopupWindow`'s gravity is fixed at this
        // call and cannot be changed afterwards, so [IamLayout] places a banner by offset instead.
        // While the popup fills the screen the two are the same picture.
        window.showAtLocation(decor, Gravity.TOP or Gravity.START, 0, 0)
    }

    /**
     * Sizes and positions the surface for the location the document reported.
     *
     * Called for every `rendering_complete`, including the repeats a multi-page document sends: a
     * page can be a different height from the one before it.
     */
    fun applyLayout(location: String, heightDp: Int, dragDisabled: Boolean) = onMain {
        val window = popup ?: return@onMain
        val host = container ?: return@onMain
        val view = web ?: return@onMain
        // `dragToDismissDisabled` governs a swipe-to-dismiss gesture this presenter does not offer;
        // a banner is dismissed by the document's own control or by a tap outside it.
        IamLayout.apply(window, host, view, location, heightDp, screenHeightPx)
    }

    /**
     * Wraps the engine's sink so a `rendering_complete` lays the surface out on its way past. The
     * event reaches the engine either way — the presenter owns the window, the engine owns what the
     * event means — and this runs on the WebView's thread, so [applyLayout] hops to the main one.
     */
    private fun layoutWatching(events: (PresenterEvent) -> Unit): (PresenterEvent) -> Unit = { event ->
        if (event is PresenterEvent.Rendered) {
            applyLayout(event.location, event.heightDp, event.dragToDismissDisabled)
        }
        events(event)
    }

    override fun hide() = onMain { tearDown() }

    /** Takes the surface down silently: the listener must not read this as the user's doing. */
    private fun tearDown() {
        programmatic = true
        popup?.let { window -> runCatching { window.dismiss() } }
        popup = null
        web?.let { view ->
            runCatching {
                view.removeJavascriptInterface(IamBridge.NAME)
                view.loadUrl("about:blank")
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
        web = null
        container = null
        screenHeightPx = 0
        events = null
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }
}

/**
 * Keeps the authored close control outside the card's scrolling element.
 *
 * Existing in-app documents put `.op-close` inside `.op-card`; because that card owns vertical
 * scrolling, the control leaves the viewport with the content. Moving the first control to the
 * non-scrolling `.op-root` preserves its icon, margins, click metadata and registered handler.
 * Carousel pages repeat the same campaign-level close control, so the redundant copies are hidden.
 * Documents without OpenPush's standard markup are left untouched.
 */
internal object IamCloseControl {
    const val PIN_SCRIPT = """
        (function(){
          var root=document.querySelector('.op-root');
          if(!root||root.getAttribute('data-op-close-pinned')==='true')return;
          var buttons=root.querySelectorAll('.op-card > .op-close');
          if(!buttons.length)return;
          var close=buttons[0];
          root.style.position='relative';
          close.style.position='absolute';
          close.style.top='12px';
          close.style.right='12px';
          close.style.zIndex='2147483647';
          root.appendChild(close);
          for(var i=1;i<buttons.length;i++)buttons[i].style.display='none';
          root.setAttribute('data-op-close-pinned','true');
        })();
    """

    fun pin(view: WebView) {
        view.evaluateJavascript(PIN_SCRIPT, null)
    }
}

/**
 * WebView navigation plus the document-level close-control repair.
 *
 * The document is server-rendered and talks to the host through [IamBridge], so a navigation is
 * either the document itself or a link the user followed. A link leaves for the browser rather
 * than replacing the message; a main-frame load that failed is a message that will never render,
 * and the engine needs to hear about it.
 */
internal class IamDocumentClient(private val events: (PresenterEvent) -> Unit) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        IamCloseControl.pin(view)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        if (request.isRedirect) return false
        val target = request.url ?: return false
        if (!IamUrlOpener.isWebLink(target)) return false
        val activity = view.context as? Activity ?: return false
        return IamUrlOpener.open(activity, target.toString(), null)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        events(PresenterEvent.Failed("load error ${error.errorCode}"))
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame) return
        events(PresenterEvent.Failed("http ${errorResponse.statusCode}"))
    }
}

/**
 * The document is remote content in a WebView that holds a bridge into the host, so it gets the
 * narrowest settings that still let it render: scripts on, every local and cross-origin door shut,
 * no plaintext sub-resource on an https page, and a debuggable inspector only in a debuggable app.
 *
 * The two `…FromFileURLs` switches are deprecated because API 30 defaults them off, but this SDK
 * supports API 24, where they default *on* — so they are set explicitly rather than assumed.
 */
@Suppress("DEPRECATION")
private fun WebSettings.harden() {
    javaScriptEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
}
