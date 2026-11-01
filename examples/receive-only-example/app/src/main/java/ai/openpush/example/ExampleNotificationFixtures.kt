package ai.openpush.example

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import ai.openpush.android.OpenPushPayload
import ai.openpush.android.fcm.OpenPushNotificationRenderer
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ExampleNotificationFixtures {
    const val CHANNEL_ID = "openpush_feature_lab"
    const val SOUND_CHANNEL_ID = "openpush_feature_lab_sound"

    fun createChannels(context: Context) {
        // Channels exist from API 26; below it NotificationCompat ignores the channel id.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val quiet = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "OpenPush feature lab notification fixtures"
            setSound(null, null)
            enableVibration(false)
        }
        val sound = NotificationChannel(
            SOUND_CHANNEL_ID,
            context.getString(R.string.notification_sound_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "OpenPush sound fixture"
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(uri, attributes)
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(quiet, sound))
    }

    @SuppressLint("MissingPermission")
    fun post(
        context: Context,
        example: NotificationExample,
        customDraft: CustomNotificationDraft? = null,
    ): Result<String> = runCatching {
        val manager = NotificationManagerCompat.from(context)
        check(manager.areNotificationsEnabled()) {
            "Notifications are disabled. Grant permission or enable them in Android settings."
        }

        val custom = (customDraft ?: CustomNotificationDraft.Example).normalized()
        if (example == NotificationExample.CUSTOM) {
            check(custom.isValid) { "Add a title, description, data key, and data value." }
        }
        val messageId = "local-${example.name.lowercase()}-${System.currentTimeMillis()}"
        val data = payload(example, custom, messageId)
        if (example == NotificationExample.GROUPED) {
            return@runCatching postGrouped(context, data, messageId)
        }
        val notification = OpenPushNotification.fromData(data)
        check(OpenPushPayload.messageId(data) == notification.messageId)
        check(OpenPushPayload.actions(data) == notification.actions)
        val description = notification.body.orEmpty()
        val builder = NotificationCompat.Builder(context, notification.channelId ?: CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_example)
            .setContentTitle(notification.title)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setAutoCancel(true)
            .setContentIntent(tapIntent(context, notification.rawData))
        notification.accentColor?.let(builder::setColor)
        notification.group?.let(builder::setGroup)
        notification.category?.let(builder::setCategory)
        notification.visibility?.let(builder::setVisibility)
        notification.ledColor?.let { builder.setLights(it, 500, 2_000) }

        when (example) {
            NotificationExample.SIMPLE -> Unit
            NotificationExample.IMAGE -> {
                check(notification.imageUrl != null) { "The Image fixture must carry image_url." }
                val image = BitmapFactory.decodeResource(context.resources, R.drawable.feature_announcement)
                check(image != null) { "The bundled feature-announcement image could not be decoded." }
                builder
                    .setLargeIcon(image)
                    .setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(image)
                            .bigLargeIcon(null as android.graphics.Bitmap?),
                    )
            }
            NotificationExample.BUTTON -> {
                notification.actions.forEach { action ->
                    builder.addAction(
                        R.drawable.ic_stat_example,
                        action.label,
                        actionIntent(context, notification.rawData, action.id),
                    )
                }
            }
            NotificationExample.CUSTOM -> {
                builder
                    .setSubText("${custom.dataKey} · ${custom.dataValue}")
                    .setStyle(
                        NotificationCompat.InboxStyle()
                            .addLine("${custom.dataKey} = ${custom.dataValue}")
                            .addLine(custom.description)
                            .setSummaryText("App-defined routing payload"),
                    )
            }
            NotificationExample.SOUND -> Unit
            NotificationExample.GROUPED -> error("Grouped fixtures use the FCM renderer path")
        }

        manager.notify(messageId.hashCode(), builder.build())
        "${example.title} posted"
    }

    private fun body(example: NotificationExample): String = when (example) {
        NotificationExample.SIMPLE ->
            "A baseline notification for foreground and click listener testing."
        NotificationExample.IMAGE ->
            "Expand this notification to inspect the bundled OpenPush image."
        NotificationExample.BUTTON ->
            "Use Open or Save to verify action identifiers in the click listener."
        NotificationExample.CUSTOM ->
            "This payload includes screen, order_id, and campaign fields for app routing."
        NotificationExample.SOUND ->
            "This fixture uses Android's default notification sound and vibration channel."
        NotificationExample.GROUPED ->
            "The FCM renderer groups this pair and creates a summary for the second child."
    }

    /** A local fixture expressed in the same data keys the composer sends over FCM. */
    private fun payload(
        example: NotificationExample,
        custom: CustomNotificationDraft,
        messageId: String,
    ): Map<String, String> = buildMap {
        put(ExampleMessagingService.EXTRA_MESSAGE_ID, messageId)
        put("title", if (example == NotificationExample.CUSTOM) custom.title else "OpenPush · ${example.title}")
        put("body", if (example == NotificationExample.CUSTOM) custom.description else body(example))
        // Match server link tracking: web URLs already contain campaign tags.
        put(
            "deep_link",
            if (example == NotificationExample.CUSTOM) {
                "https://openpush.ai/?utm_source=openpush&utm_medium=push&utm_campaign=Feature+Lab#overview"
            } else {
                "openpush://feature-lab/${example.name.lowercase()}"
            },
        )
        put(EXTRA_EXAMPLE, example.name.lowercase())
        put("op_android_channel", if (example == NotificationExample.SOUND) SOUND_CHANNEL_ID else CHANNEL_ID)
        put("op_android_accent", "#6750A4")
        put("op_android_category", NotificationCompat.CATEGORY_MESSAGE)
        put("op_android_visibility", "private")
        put("op_android_small_icon", "ic_stat_example")
        put("op_android_led", "#6750A4")
        when (example) {
            NotificationExample.IMAGE -> {
                // The host-owned renderer uses the bundled copy so this fixture remains offline,
                // while the model and click event still demonstrate the composer's image_url key.
                put("image_url", "https://openpush.ai/examples/feature-announcement.png")
            }
            NotificationExample.BUTTON -> put(
                "op_actions",
                """[{"id":"open","label":"Open","icon":"ic_stat_example","system_icon":false},""" +
                    """{"id":"save","label":"Save","icon":"ic_stat_example","system_icon":false}]""",
            )
            NotificationExample.CUSTOM -> put(custom.dataKey, custom.dataValue)
            NotificationExample.SOUND -> put("op_android_sound", "default")
            NotificationExample.GROUPED -> put("op_android_group", GROUP_KEY)
            NotificationExample.SIMPLE -> Unit
        }
    }

    private fun postGrouped(context: Context, firstData: Map<String, String>, messageId: String): String {
        // This sample owns normal notification rendering. The grouped fixture deliberately exercises
        // the optional FCM renderer so its posted-id registry and summary behavior are observable.
        val renderer = OpenPushNotificationRenderer(context)
        val first = firstData + ("body" to "Grouped fixture · first child")
        val second = firstData + mapOf(
            ExampleMessagingService.EXTRA_MESSAGE_ID to "$messageId-second",
            "body" to "Grouped fixture · second child",
        )
        check(renderer.render(first)) { "The first grouped notification was not posted." }
        check(renderer.render(second)) { "The second grouped notification was not posted." }
        return "Grouped pair posted through the FCM renderer"
    }

    private fun tapIntent(context: Context, data: Map<String, String>): PendingIntent {
        val messageId = data.getValue(ExampleMessagingService.EXTRA_MESSAGE_ID)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        data.forEach { (key, value) -> intent.putExtra(key, value) }
        return PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, data: Map<String, String>, actionId: String): PendingIntent {
        val messageId = data.getValue(ExampleMessagingService.EXTRA_MESSAGE_ID)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        data.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.putExtra(OpenPush.ACTION_ID_KEY, actionId)
        return PendingIntent.getActivity(
            context,
            "$messageId|$actionId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val EXTRA_EXAMPLE = "example"
    const val GROUP_KEY = "feature-lab-grouped"
    const val EXTRA_CUSTOM_DATA = "openpush_custom"
}
