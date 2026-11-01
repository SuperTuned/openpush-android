package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * The in-app messaging surface a host app talks to. Every member here forwards to [IamEngine],
 * which owns the definitions, the display queue and the state store; this object is a name, not a
 * place where decisions are made.
 */
object OpenPushInAppMessages {
    /** The one engine for this process. Created eagerly so it can register its listeners. */
    internal val engine = IamEngine()

    /** Suspends and resumes display. Pausing takes down whatever is on screen. */
    @JvmStatic
    var paused: Boolean
        get() = engine.paused
        set(value) {
            engine.paused = value
        }

    @JvmStatic
    fun addTrigger(key: String, value: Any) = engine.addTrigger(key, value)

    @JvmStatic
    fun addTriggers(values: Map<String, Any>) = engine.addTriggers(values)

    @JvmStatic
    fun removeTrigger(key: String) = engine.removeTrigger(key)

    @JvmStatic
    fun removeTriggers(keys: Collection<String>) = engine.removeTriggers(keys)

    @JvmStatic
    fun clearTriggers() = engine.clearTriggers()

    @JvmStatic
    fun addLifecycleListener(listener: (InAppMessageLifecycleEvent) -> Unit): UUID =
        engine.addLifecycleListener(listener)

    @JvmStatic
    fun removeLifecycleListener(id: UUID) = engine.removeLifecycleListener(id)

    @JvmStatic
    fun addClickListener(listener: (InAppMessageClickEvent) -> Unit): UUID = engine.addClickListener(listener)

    @JvmStatic
    fun removeClickListener(id: UUID) = engine.removeClickListener(id)

    /** Asks the server for this subscription's messages, at most once per fetch interval. */
    @JvmStatic
    fun fetch() = engine.fetch()

    /**
     * Shows an in-app document bundled in the app's own assets, over [activity], through the same
     * presenter a server-sent message uses — the same WebView and its hardening, the same
     * JavaScript bridge, the same layout rules and the same link handling.
     *
     * For building and demonstrating in-app messages, not for production display. Nothing is
     * recorded: no receipt is posted, no impression, dismissal, redisplay or generation state is
     * written, and the real display queue is neither consumed nor blocked. Lifecycle and click
     * listeners do fire, for the message id `"preview"`. An action asking for a permission prompt,
     * a tag or an outcome is ignored with a log, because a bundled fixture must not be able to
     * write to the live user; a `url` action opens for real.
     *
     * [assetPath] is relative to the app's `assets/` directory. A document that cannot be read is
     * logged and nothing is shown.
     */
    @OpenPushInternalApi
    @JvmStatic
    fun previewLocalDocument(activity: Activity, assetPath: String) = engine.previewLocalDocument(activity, assetPath)

    // Seams. Production values are the only ones that ship; the tests replace them wholesale.
    internal var scope: CoroutineScope
        get() = engine.scope
        set(value) {
            engine.scope = value
        }

    internal var scheduler: IamScheduler
        get() = engine.scheduler
        set(value) {
            engine.scheduler = value
        }

    internal var presenterFactory: () -> IamPresenter
        get() = engine.presenterFactory
        set(value) {
            engine.presenterFactory = value
        }

    internal var nowMs: () -> Long
        get() = engine.nowMs
        set(value) {
            engine.nowMs = value
        }

    internal var fetchIntervalMs: Long
        get() = engine.fetchIntervalMs
        set(value) {
            engine.fetchIntervalMs = value
        }

    internal var onMain: (() -> Unit) -> Unit
        get() = engine.onMain
        set(value) {
            engine.onMain = value
        }

    /** The workspace's persisted state. Available from the first attach onwards. */
    internal val state: InAppStateStore
        get() = engine.store ?: error("in-app state is not loaded — attach OpenPush first")

    /** Returns the process singleton to a just-loaded state. Not part of the published surface. */
    internal fun resetForTests() = engine.resetForTests()
}

@Suppress("PropertyName")
val OpenPush.InAppMessages: OpenPushInAppMessages get() = OpenPushInAppMessages
