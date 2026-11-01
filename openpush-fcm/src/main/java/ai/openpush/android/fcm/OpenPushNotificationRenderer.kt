package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushAction
import ai.openpush.android.OpenPushNotification
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Draws a push the way the composer described it.
 *
 * Every field the composer can set has a line here — channel, accent, group, category, visibility,
 * LED, sound, small icon, big picture, large icon and up to three action buttons — and the
 * `confirmed` receipt is posted only once the platform has accepted the notification, so "the user
 * could have seen it" is what the dashboard reports rather than "the device received the data".
 *
 * A push with no `op_message_id` is not drawn at all: it came from another sender sharing the host's
 * provider integration, and rendering it would put this SDK's trampoline on someone else's
 * notification. Subclass and override [render] to draw something else entirely.
 */
open class OpenPushNotificationRenderer @JvmOverloads constructor(
    private val context: Context,
    private val imageLoader: OpenPushImageLoader = OpenPushImageLoader(),
) {
    private val postedNotifications = OpenPushPostedNotifications(context)

    /**
     * Renders a delivered data map.
     *
     * @return true when a notification was posted.
     */
    open fun render(data: Map<String, String>): Boolean = render(OpenPushNotification.fromData(data)) != null

    /**
     * Renders a parsed push.
     *
     * @return the posted notification id, or null when nothing was posted.
     */
    open fun render(notification: OpenPushNotification): Int? {
        val messageId = notification.messageId ?: run {
            OpenPush.onLog("notification dropped — no op_message_id")
            return null
        }
        if (notification.title.isNullOrEmpty() && notification.body.isNullOrEmpty()) {
            OpenPush.onLog("notification dropped — rendered title and body are both empty")
            return null
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val channelId = ensureChannel(
            manager,
            notification.channelId?.takeIf { it.isNotBlank() }
                ?: meta()?.getString(META_CHANNEL_ID)
                ?: DEFAULT_CHANNEL_ID,
        )
        // A push that opted into replacing posts under its collapse key as the tag and an id fixed
        // for that key, so a newer push with the same key replaces the shown one. Every other push
        // keeps its per-message id and stacks.
        val replaceTag = replaceKey(notification)
        val id = replaceTag?.let(::replaceNotificationIdFor) ?: notificationId(messageId)
        val builder = newBuilder(channelId)
            .setSmallIcon(smallIcon(notification.smallIcon))
            .setContentTitle(notification.title?.ifEmpty { null } ?: appLabel())
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setContentIntent(
                OpenPushClickActivity.pendingIntent(
                    context,
                    notification.rawData,
                    id,
                    notification.collapseKey,
                ),
            )
        notification.accentColor?.let { builder.setColor(it) }
        // A named group opts into SDK grouping; reconcileGroupSummaries adds its summary once a
        // second active child exists.
        notification.group?.let { builder.setGroup(it) }
        notification.category?.let { builder.setCategory(it) }
        notification.visibility?.let { builder.setVisibility(it) }
        notification.ledColor?.let { builder.setLights(it, LED_ON_MS, LED_OFF_MS) }
        soundUri(notification.sound)?.let { builder.setSound(it) }
        val largeIconValue = notification.largeIconUrl?.trim()?.takeIf { it.isNotEmpty() }
        val (bigPicture, remoteLargeIcon) = imageLoader.withTotalBudget {
            val picture = notification.bigPictureUrl?.let(imageLoader::load)
            val icon = largeIconValue?.takeIf { it.startsWith("https://", ignoreCase = true) }
                ?.let(imageLoader::load)
            picture to icon
        }
        // A value without a scheme names a host drawable; a missing or failed one falls back to
        // the bundled default, so a remote outage still shows the app's own large icon.
        val unfetchedScheme = largeIconValue?.contains("://") == true &&
            !largeIconValue.startsWith("https://", ignoreCase = true)
        if (unfetchedScheme) OpenPush.onLog("large icon skipped — only https urls are fetched")
        val largeIcon = remoteLargeIcon
            ?: largeIconValue?.takeUnless { it.contains("://") }?.let { spelled ->
                largeIconFromResource(spelled)
                    ?: null.also {
                        OpenPush.onLog("large icon \"$spelled\" is not a host drawable — using the default")
                    }
            }
            ?: defaultLargeIcon()
        if (bigPicture != null) {
            builder.setStyle(
                Notification.BigPictureStyle().bigPicture(bigPicture).setSummaryText(notification.body),
            )
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(notification.body.orEmpty()))
        }
        largeIcon?.let { builder.setLargeIcon(scaleToLargeIconArea(it)) }
        notification.actions.forEach { action ->
            val extras = notification.rawData + (OpenPush.ACTION_ID_KEY to action.id)
            val requestCode = "$messageId|${action.id}".hashCode()
            builder.addAction(
                Notification.Action.Builder(
                    actionIcon(action),
                    action.label,
                    OpenPushClickActivity.pendingIntent(
                        context,
                        extras,
                        requestCode,
                        notification.collapseKey,
                    ),
                ).build(),
            )
        }
        return runCatching {
            manager.notify(replaceTag, id, builder.build())
            runCatching {
                postedNotifications.record(id, messageId, notification.group, tag = replaceTag)
            }.onFailure {
                OpenPush.onLog("posted notification registry write failed: ${it.message}")
            }
            runCatching {
                reconcileGroupSummaries(manager, notification.group, channelId)
            }.onFailure {
                OpenPush.onLog("group summary refresh failed: ${it.message}")
            }
            // Posted after notify, so "confirmed" means the platform accepted the notification.
            OpenPush.Notifications.processDisplayed(notification)
            id
        }.onFailure { OpenPush.onLog("notification dropped — notify failed: ${it.message}") }.getOrNull()
    }

    /** The notification id a message occupies, so an update to the same message replaces it. */
    internal fun notificationId(messageId: String): Int = notificationIdFor(messageId)

    /**
     * The collapse key a push asked to replace under, or null when it stacks as usual. Only a push
     * carrying `op_android_replace` = `"1"` and a collapse key opts in; the server normally moves
     * `op_collapse_id` into the provider's collapse key, so that is read first.
     */
    internal fun replaceKey(notification: OpenPushNotification): String? {
        if (notification.rawData[KEY_REPLACE] != "1") return null
        return notification.collapseKey?.takeIf { it.isNotBlank() }
            ?: notification.rawData[KEY_COLLAPSE_ID]?.takeIf { it.isNotBlank() }
            ?: null.also { OpenPush.onLog("op_android_replace ignored — the push has no collapse key") }
    }

    /** Removes stale SDK summaries, then posts or refreshes the rendered child's group summary. */
    private fun reconcileGroupSummaries(manager: NotificationManager, renderedGroup: String?, channelId: String) {
        val entries = postedNotifications.entries()
        val active = manager.activeNotifications
        val activeById = active.associateBy { it.id }
        val activeChildren = entries.filter { activeById.containsKey(it.notificationId) }
        val childCountByGroup = activeChildren.mapNotNull { it.group }.groupingBy { it }.eachCount()

        active.forEach { status ->
            val group = status.notification.group ?: return@forEach
            if (
                status.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
                status.id == summaryNotificationId(group) &&
                childCountByGroup.getOrDefault(group, 0) < MIN_CHILDREN_FOR_SUMMARY
            ) {
                manager.cancel(status.id)
            }
        }

        val group = renderedGroup ?: return
        val groupChildren = activeChildren.filter { it.group == group }
        if (groupChildren.size < MIN_CHILDREN_FOR_SUMMARY) return

        val style = Notification.InboxStyle()
        groupChildren.takeLast(MAX_SUMMARY_LINES).forEach { entry ->
            val body = activeById[entry.notificationId]?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TEXT)
                ?: ""
            style.addLine(body)
        }
        val summary = newBuilder(channelId)
            .setSmallIcon(smallIcon())
            .setContentTitle(appLabel())
            .setContentText("${groupChildren.size} notifications")
            .setGroup(group)
            .setGroupSummary(true)
            .setStyle(style)
        defaultLargeIcon()?.let { summary.setLargeIcon(scaleToLargeIconArea(it)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            summary.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        }
        manager.notify(summaryNotificationId(group), summary.build())
    }

    /**
     * Resolves a host resource by name. Overridden in tests; the production answer is the host's
     * own resource table, which the SDK never adds to.
     */
    internal open fun resourceId(name: String, type: String, packageName: String): Int =
        runCatching { context.resources.getIdentifier(name, type, packageName) }.getOrDefault(0)

    /**
     * The small icon, in the composer's order of precedence: a drawable the payload named, then the
     * `ai.openpush.notification_icon` manifest value, then a bundled default status icon (see
     * [DEFAULT_SMALL_ICONS]), then the app icon, then a platform fallback so the notification is never silently refused for
     * want of one.
     */
    internal fun smallIcon(name: String? = null): Int {
        val fromPayload = name?.trim()?.takeIf { it.isNotEmpty() }?.let { spelled ->
            drawableOrMipmap(spelled).takeIf { it != 0 }
                ?: 0.also { OpenPush.onLog("small icon \"$spelled\" is not a host drawable — using the default") }
        }
        return fromPayload?.takeIf { it != 0 }
            ?: meta()?.getInt(META_SMALL_ICON, 0)?.takeIf { it != 0 }
            ?: DEFAULT_SMALL_ICONS.firstNotNullOfOrNull { drawableOrMipmap(it).takeIf { id -> id != 0 } }
            ?: context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
    }

    /** A host drawable named [name], or a mipmap of that name, or 0. */
    private fun drawableOrMipmap(name: String): Int =
        resourceId(name, "drawable", context.packageName).takeIf { it != 0 }
            ?: resourceId(name, "mipmap", context.packageName)

    /** The host drawable or mipmap named [name] as a bitmap, or null when there is none. */
    internal fun largeIconFromResource(name: String): Bitmap? =
        drawableOrMipmap(name).takeIf { it != 0 }?.let(::bitmapForResource)

    /**
     * The large icon used when a push names none or its own cannot be shown: the
     * `ai.openpush.notification_large_icon` manifest value, then a bundled default (see
     * [DEFAULT_LARGE_ICONS]). Null when the host bundles none.
     */
    internal fun defaultLargeIcon(): Bitmap? =
        meta()?.getInt(META_LARGE_ICON, 0)?.takeIf { it != 0 }?.let(::bitmapForResource)
            ?: DEFAULT_LARGE_ICONS.firstNotNullOfOrNull(::largeIconFromResource)

    /**
     * Draws a resource into a bitmap. Vector and adaptive drawables work as well as PNGs.
     * Overridden in tests, where the module has no resources of its own.
     */
    internal open fun bitmapForResource(id: Int): Bitmap? = runCatching {
        val drawable = context.getDrawable(id) ?: return@runCatching null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return@runCatching drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: largeIconArea().first
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: largeIconArea().second
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }.onFailure { OpenPush.onLog("large icon resource $id could not be drawn: ${it.message}") }.getOrNull()

    /**
     * Shrinks [bitmap] to fit the platform's large-icon area, keeping its aspect ratio. A bitmap that
     * already fits is returned as is: the system never needs a large icon bigger than this, and an
     * oversized one costs memory in every notification that carries it.
     */
    internal fun scaleToLargeIconArea(bitmap: Bitmap): Bitmap = runCatching {
        val (maxWidth, maxHeight) = largeIconArea()
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return@runCatching bitmap
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }.getOrDefault(bitmap)

    private fun largeIconArea(): Pair<Int, Int> =
        context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width) to
            context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)

    /**
     * A named `raw` resource in the host package, or the device default when the host ships no such
     * sound. Null means "say nothing", which leaves the channel's own sound in charge — and on
     * API 26 and up the channel's sound wins over this one for any channel already created.
     */
    private fun soundUri(name: String?): Uri? {
        val spelled = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val id = resourceId(spelled, "raw", context.packageName)
        if (id != 0) return Uri.parse("android.resource://${context.packageName}/$id")
        OpenPush.onLog("sound \"$spelled\" is not a host raw resource — using the device default")
        return Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /** A platform drawable when the composer asked for one, else a host drawable, else none. */
    private fun actionIcon(action: OpenPushAction): Int {
        val spelled = action.icon.trim().takeIf { it.isNotEmpty() } ?: return 0
        val packageName = if (action.systemIcon) "android" else context.packageName
        return resourceId(spelled, "drawable", packageName)
    }

    /** A builder on [channelId]; below API 26 there are no channels and the id is unused. */
    private fun newBuilder(channelId: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

    /**
     * Never recreates a channel the host declared: its importance and sound are the host's. Below
     * API 26 there are no channels, so the id is returned untouched.
     */
    private fun ensureChannel(manager: NotificationManager, id: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(id) == null) {
            val name = if (id == DEFAULT_CHANNEL_ID) appLabel() else id
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        return id
    }

    private fun meta() = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull()

    private fun appLabel() = context.applicationInfo.loadLabel(context.packageManager).toString()

    companion object {
        init {
            installOpenPushShadeManager()
        }

        const val META_CHANNEL_ID = "ai.openpush.notification_channel_id"
        const val META_SMALL_ICON = "ai.openpush.notification_icon"
        const val META_LARGE_ICON = "ai.openpush.notification_large_icon"
        const val DEFAULT_CHANNEL_ID = "openpush_default"

        /** Data key: `"1"` asks for this push to replace a shown one with the same collapse key. */
        const val KEY_REPLACE = "op_android_replace"

        /** Data key a host forwarding the raw OpenPush map may carry the collapse key under. */
        internal const val KEY_COLLAPSE_ID = "op_collapse_id"

        /**
         * The notification id this renderer posts a message under. It is derived from the message
         * id alone and never changes, so a host can cancel or replace what the SDK posted:
         *
         *     NotificationManagerCompat.from(context)
         *         .cancel(OpenPushNotificationRenderer.notificationIdFor(messageId))
         *
         * Two different message ids can collide, as any hash can; the id is stable for one message,
         * not unique across all of them.
         */
        @JvmStatic
        fun notificationIdFor(messageId: String): Int = messageId.hashCode()

        /**
         * The notification id a push sent with `op_android_replace` posts under; its tag is the
         * collapse key itself. Both are fixed for one collapse key, so each newer push replaces the
         * shown one. A host cancels it with
         *
         *     NotificationManagerCompat.from(context)
         *         .cancel(collapseKey, OpenPushNotificationRenderer.replaceNotificationIdFor(collapseKey))
         */
        @JvmStatic
        fun replaceNotificationIdFor(collapseKey: String): Int = "replace|$collapseKey".hashCode()

        /**
         * Bundled status icons tried after the manifest value: OpenPush's own name, then the name a
         * host migrating from another provider usually still bundles, so its icon keeps working.
         */
        private val DEFAULT_SMALL_ICONS = listOf("ic_stat_openpush_default", "ic_stat_onesignal_default")

        /** Bundled large icons tried when a push has none of its own, in the same order. */
        private val DEFAULT_LARGE_ICONS = listOf("ic_openpush_large_icon_default", "ic_onesignal_large_icon_default")

        private const val LED_ON_MS = 500
        private const val LED_OFF_MS = 2000
        private const val MAX_SUMMARY_LINES = 5
    }
}
