package ai.openpush.example

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.liveupdates.OpenPushLiveUpdateResult
import ai.openpush.android.liveupdates.OpenPushLiveUpdates
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Forwards provider tokens to OpenPush, displays the notification, and posts the
 * receipts that back the delivery report.
 */
class ExampleMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "provider token rotated · ${OpenPush.shortToken(token)}")
        if (ExampleApplication.isLinked()) {
            OpenPush.register(token)
        } else {
            Log.i(TAG, "not registering yet — this device is not linked")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The server re-sends a push whose delivery it could not confirm, so the same message can
        // arrive twice. beginReceived refuses the second copy; finishReceived records the first
        // once it has been handled, shown or not.
        if (!OpenPush.Notifications.beginReceived(message.data)) {
            Log.i(TAG, "a second copy of a push already handled — dropped")
            return
        }
        try {
            deliver(message)
        } finally {
            OpenPush.Notifications.finishReceived(message.data)
        }
    }

    private fun deliver(message: RemoteMessage) {
        val data = message.data
        val messageId = OpenPush.messageId(data)
        if (messageId == null) {
            Log.i(TAG, "push has no op_message_id — it cannot be attributed")
        }
        val title = OpenPushNotification.fromData(data).title
        ExampleLastPush.record(this, messageId, title)
        ExampleEventRelay.emit(
            LabEventKind.SDK,
            "Push received",
            "${title ?: "untitled"} · ${messageId ?: "no op_message_id"}",
        )

        // "received" is the arrival at this device; "confirmed" means Android was
        // actually asked to present the notification. Reporting "confirmed" when
        // nothing was displayed would overstate delivery.
        //
        // Every delivery goes through here: the SDK posts "received" either way,
        // and only consults the foreground listeners — the ones that can call
        // preventDefault() — while this process has a visible Activity. The host
        // does not need its own foreground check.
        if (OpenPush.Notifications.processForegroundMessage(data)) {
            Log.i(TAG, "a foreground listener handled this push — nothing displayed")
            return
        }

        val liveResult = OpenPushLiveUpdates.handle(
            this,
            data,
            message.collapseKey,
            R.drawable.ic_stat_example,
            tapIntent(data),
        )
        if (liveResult.isHandled) {
            if (liveResult == OpenPushLiveUpdateResult.DISPLAYED) {
                OpenPush.Notifications.processDisplayed(data)
                Log.i(TAG, "confirmed receipt queued · ${messageId ?: "unknown"}")
            }
            Log.i(TAG, "Live Update result · $liveResult")
            return
        }

        if (display(data, messageId)) {
            OpenPush.Notifications.processDisplayed(data)
            Log.i(TAG, "confirmed receipt queued · ${messageId ?: "unknown"}")
        } else {
            Log.i(TAG, "notifications are disabled — nothing displayed, so no confirmed receipt")
        }
    }

    private fun display(data: Map<String, String>, messageId: String?): Boolean {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return false

        // Path 1 still owns the NotificationCompat renderer. Parsing once through the SDK model
        // keeps its rendering and click handling on the same composer-field interpretation.
        val notification = OpenPushNotification.fromData(data)
        val body = notification.body.orEmpty()
        val channel = notification.channelId ?: ExampleNotificationFixtures.CHANNEL_ID
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_example)
            .setContentTitle(notification.title ?: getString(R.string.app_name))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tapIntent(notification.rawData))
        notification.accentColor?.let(builder::setColor)
        notification.group?.let(builder::setGroup)
        notification.category?.let(builder::setCategory)
        notification.visibility?.let(builder::setVisibility)
        notification.ledColor?.let { builder.setLights(it, 500, 2_000) }
        OpenPush.actions(data).forEach { action ->
            val actionTap = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            notification.rawData.forEach { (key, value) -> actionTap.putExtra(key, value) }
            actionTap.putExtra(EXTRA_ACTION_ID, action.id)
            val iconPackage = if (action.systemIcon) "android" else packageName
            val icon = resources.getIdentifier(action.icon, "drawable", iconPackage)
                .takeIf { it != 0 } ?: R.drawable.ic_stat_example
            builder.addAction(
                icon,
                action.label,
                PendingIntent.getActivity(
                    this,
                    (messageId.orEmpty() + "|" + action.id).hashCode(),
                    actionTap,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val renderedNotification = builder.build()

        Log.i(
            TAG,
            "Android 16 promotion characteristics · " +
                OpenPushLiveUpdates.hasPromotableCharacteristics(renderedNotification),
        )

        return try {
            manager.notify(messageId.hashCode(), renderedNotification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun tapIntent(data: Map<String, String>): PendingIntent {
        val messageId = data[EXTRA_MESSAGE_ID]
        val tap = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        data.forEach { (key, value) -> tap.putExtra(key, value) }
        return PendingIntent.getActivity(
            this,
            messageId.hashCode(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "op_message_id"
        const val EXTRA_ACTION_ID = "op_action_id"
        private const val TAG = "OpenPushExample"
    }
}
