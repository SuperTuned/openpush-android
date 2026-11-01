package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.liveupdates.OpenPushLiveUpdatePayload
import ai.openpush.android.liveupdates.OpenPushLiveUpdateResult
import ai.openpush.android.liveupdates.OpenPushLiveUpdateState
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

open class OpenPushMessagingService : FirebaseMessagingService() {
    protected open fun renderer() = OpenPushNotificationRenderer(this)

    // The message being handled on this thread, so the foreign-push branch can hand the host the
    // RemoteMessage it actually received. Thread-scoped because the provider dispatches deliveries
    // on a pool: two messages can be in flight on one service instance at the same time.
    private val inFlight = ThreadLocal<RemoteMessage?>()

    override fun onNewToken(token: String) {
        if (!OpenPush.isInitialized) return
        OpenPush.register(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        inFlight.set(message)
        try {
            handleData(message.data, message.collapseKey)
        } finally {
            inFlight.remove()
        }
    }

    internal fun handleData(data: Map<String, String>) = handleData(data, null)

    internal open fun handleData(data: Map<String, String>, collapseKey: String?) {
        val notification = OpenPushNotification.fromData(data, collapseKey)
        // A map without an op_message_id came from another sender sharing this provider
        // integration. It is not ours to report on, to render, or to attach our trampoline to.
        if (!notification.isOpenPush) {
            val message = inFlight.get()
            if (message == null) OpenPush.onLog(FOREIGN_LOG) else onForeignMessage(message)
            return
        }
        handleOnce(data) {
            // processForegroundMessage only returns true for a push that arrived while the app was
            // foregrounded *and* a listener called preventDefault(). A background delivery therefore
            // always falls through to the renderer below — its receipt was still posted, but the host's
            // foreground listeners never saw it, so nothing was in a position to suppress the display.
            // It also returns true for a background (data-only) push, which it hands to the
            // background-message listeners and which is never drawn.
            if (OpenPush.Notifications.processForegroundMessage(notification)) return@handleOnce
            if (handleLiveUpdate(data, collapseKey).isHandled) return@handleOnce
            renderer().render(notification)
        }
    }

    /**
     * A data message this SDK did not send, delivered because the host shares one provider
     * integration between OpenPush and its own pushes. Override to handle those yourself; the
     * default only logs. Nothing has been reported or rendered by the time this runs.
     */
    protected open fun onForeignMessage(message: RemoteMessage) {
        OpenPush.onLog(FOREIGN_LOG)
    }

    /** Override only to supply an alternative host renderer in a custom FCM integration. */
    protected open fun handleLiveUpdate(data: Map<String, String>, collapseKey: String?): OpenPushLiveUpdateResult =
        liveUpdate(this, data, collapseKey, renderer())

    companion object {
        private const val FOREIGN_LOG =
            "a push OpenPush did not send — ignored; override onForeignMessage to handle your own"

        /**
         * Runs the delivery pipeline for a host that declares its own `FirebaseMessagingService`:
         * forward every `onMessageReceived` here and OpenPush pushes are reported, routed to the
         * Live Update renderer where they are one, and drawn — while the host's own data messages
         * fall straight through, untouched, for the host's service to handle. A second copy of a
         * push already handled on this device is dropped before any of that.
         */
        @JvmStatic
        fun handle(context: Context, message: RemoteMessage) {
            val data = message.data
            val notification = OpenPushNotification.fromData(data, message.collapseKey)
            if (!notification.isOpenPush) {
                OpenPush.onLog("a push OpenPush did not send — ignored; it is your service's own")
                return
            }
            handleOnce(data) {
                if (OpenPush.Notifications.processForegroundMessage(notification)) return@handleOnce
                val renderer = OpenPushNotificationRenderer(context)
                if (liveUpdate(context, data, message.collapseKey, renderer).isHandled) return@handleOnce
                renderer.render(notification)
            }
        }

        /**
         * Runs [deliver] for the first copy of a push only — a copy the server re-sent after an
         * ambiguous provider response is dropped before listeners, receipts or the renderer see it.
         * The id is recorded once [deliver] returns, shown or not; if it throws, nothing is recorded
         * and a later copy can still show the push.
         */
        private inline fun handleOnce(data: Map<String, String>, deliver: () -> Unit) {
            if (!OpenPush.Notifications.beginReceived(data)) return
            var handled = false
            try {
                deliver()
                handled = true
            } finally {
                OpenPush.Notifications.finishReceived(data, handled)
            }
        }

        private fun liveUpdate(
            context: Context,
            data: Map<String, String>,
            collapseKey: String?,
            renderer: OpenPushNotificationRenderer,
        ): OpenPushLiveUpdateResult {
            val requestCode = runCatching {
                val payload = OpenPushLiveUpdatePayload.parse(data, collapseKey)
                OpenPushLiveUpdateState.stableNotificationId(context.packageName, payload.instanceId)
            }.getOrDefault(0)
            val click = OpenPushClickActivity.pendingIntent(context, data, requestCode, collapseKey)
            return OpenPushLiveUpdates.handle(context, data, collapseKey, renderer.smallIcon(), click)
        }
    }
}
