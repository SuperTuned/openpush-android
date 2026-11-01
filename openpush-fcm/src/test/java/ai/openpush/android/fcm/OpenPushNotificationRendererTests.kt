package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushNotification
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushNotificationRendererTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val urls = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush::class.java.declaredMethods
            .first { it.name.startsWith("resetForTests") }
            .invoke(OpenPush)
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        urls.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
        context.getSharedPreferences("openpush.posted", Context.MODE_PRIVATE).edit().clear().commit()
        manager.cancelAll()
        shadowOf(manager).notificationChannels.forEach {
            manager.deleteNotificationChannel((it as NotificationChannel).id)
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun payload(vararg extra: Pair<String, String>) =
        mapOf("op_message_id" to "m1", "title" to "hi", "body" to "there") + extra

    private fun render(
        data: Map<String, String> = payload(),
        renderer: OpenPushNotificationRenderer = OpenPushNotificationRenderer(context),
    ): Int? = renderer.render(OpenPushNotification.fromData(data))

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    /** A loader that never touches the network: it hands back whatever the test decided. */
    private class FakeLoader(private val bitmap: Bitmap?) : OpenPushImageLoader({ null }) {
        val requested = mutableListOf<String>()
        override fun load(url: String): Bitmap? {
            requested += url
            return bitmap
        }
    }

    /** Stands in for host resources: the module ships none, so lookups are answered from a map. */
    private fun rendererWithResources(
        resources: Map<Pair<String, String>, Int>,
        loader: OpenPushImageLoader = OpenPushImageLoader { null },
        bitmaps: Map<Int, Bitmap> = emptyMap(),
    ) = object : OpenPushNotificationRenderer(context, loader) {
        override fun resourceId(name: String, type: String, packageName: String) = resources[name to type] ?: 0
        override fun bitmapForResource(id: Int): Bitmap? = bitmaps[id]
    }

    /** The large icon a notification carries, as the bitmap it was built from. */
    private fun largeIconOf(notification: Notification): Bitmap? = notification.getLargeIcon()?.let { icon ->
        icon.javaClass.getMethod("getBitmap").invoke(icon) as Bitmap?
    }

    private fun setSmallIconMeta(value: Int) {
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        info.applicationInfo!!.metaData =
            Bundle().apply { putInt(OpenPushNotificationRenderer.META_SMALL_ICON, value) }
    }

    private fun clearMeta() {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData = null
    }

    // ---- channels ----------------------------------------------------------------------------

    @Test fun theDefaultChannelIsCreatedWhenTheHostDeclaredNone() {
        assertNull(manager.getNotificationChannel(OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID))

        assertTrue(OpenPushNotificationRenderer(context).render(payload()))

        assertNotNull(manager.getNotificationChannel(OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID))
        assertEquals(OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID, posted().channelId)
    }

    @Test fun theChannelFromThePayloadIsCreatedOnDemandAndUsed() {
        assertNotNull(render(payload("op_android_channel" to "promos")))

        val channel = manager.getNotificationChannel("promos")
        assertNotNull(channel)
        assertEquals("promos", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals("promos", posted().channelId)
        assertNull(
            "the default channel is not created when the payload names its own",
            manager.getNotificationChannel(OpenPushNotificationRenderer.DEFAULT_CHANNEL_ID),
        )
    }

    @Test fun aHostDeclaredChannelIsNotRecreated() {
        manager.createNotificationChannel(
            NotificationChannel("promos", "Host promos", NotificationManager.IMPORTANCE_HIGH),
        )

        assertNotNull(render(payload("op_android_channel" to "promos")))

        val channel = manager.getNotificationChannel("promos")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Host promos", channel.name)
    }

    // ---- composer fields ---------------------------------------------------------------------

    @Test fun accentGroupCategoryVisibilityAndLedAreApplied() {
        assertNotNull(
            render(
                payload(
                    "op_android_accent" to "#FF3366",
                    "op_android_group" to "orders",
                    "op_android_category" to Notification.CATEGORY_PROMO,
                    "op_android_visibility" to "secret",
                    "op_android_led" to "#8800FF00",
                ),
            ),
        )

        val notification = posted()
        assertEquals(0xFFFF3366.toInt(), notification.color)
        assertEquals("orders", notification.group)
        assertEquals(Notification.CATEGORY_PROMO, notification.category)
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(0x8800FF00.toInt(), notification.ledARGB)
    }

    @Test fun anArgbAccentIsAcceptedAndThePlatformDrawsItOpaque() {
        assertNotNull(render(payload("op_android_accent" to "#80FF3366")))

        // The renderer hands the parsed ARGB to setColor; Notification.Builder sanitizes every
        // accent to opaque, so the colour shows and only its alpha is dropped.
        assertEquals(0xFFFF3366.toInt(), posted().color)
    }

    @Test fun anIosBadgeIncrementIsIgnored() {
        val data = payload("op_badge_increment" to "3")

        assertNotNull(render(data))

        // Launchers own Android badges: the key changes nothing and is not host data either.
        assertEquals(0, posted().number)
        assertFalse(OpenPushNotification.fromData(data).additionalData.containsKey("op_badge_increment"))
    }

    @Test fun aPushWithoutTheseFieldsLeavesThemAlone() {
        assertNotNull(render())

        val notification = posted()
        assertNull(notification.group)
        assertNull(notification.category)
        assertNull("no sound field means the channel's own sound", notification.sound)
    }

    @Test fun aSoundResourceNameResolvesToTheHostPackage() {
        val renderer = rendererWithResources(mapOf(("chime" to "raw") to 4242))

        assertNotNull(renderer.render(OpenPushNotification.fromData(payload("op_android_sound" to "chime"))))

        assertEquals(
            "android.resource://${context.packageName}/4242",
            posted().sound.toString(),
        )
    }

    @Test fun anUnknownSoundNameFallsBackToTheDeviceDefault() {
        val renderer = rendererWithResources(emptyMap())

        assertNotNull(renderer.render(OpenPushNotification.fromData(payload("op_android_sound" to "nope"))))

        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, posted().sound)
        assertTrue(logs.any { it.contains("nope") })
    }

    @Suppress("DEPRECATION")
    @Test
    fun smallIconPrecedenceIsPayloadThenMetaThenAppIcon() {
        setSmallIconMeta(777)

        val withPayload = rendererWithResources(mapOf(("star" to "drawable") to 4242))
        assertNotNull(
            withPayload.render(OpenPushNotification.fromData(payload("op_android_small_icon" to "star"))),
        )
        assertEquals(4242, posted().icon)

        manager.cancelAll()
        val unknownName = rendererWithResources(emptyMap())
        assertNotNull(
            unknownName.render(OpenPushNotification.fromData(payload("op_android_small_icon" to "star"))),
        )
        assertEquals("an unresolvable payload name falls through to the manifest", 777, posted().icon)

        manager.cancelAll()
        clearMeta()
        assertNotNull(render())
        val fallback = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        assertEquals(fallback, posted().icon)
    }

    // ---- images ------------------------------------------------------------------------------

    @Test fun bigPictureUsesTheLoadedBitmap() {
        val loader = FakeLoader(bitmap())
        val renderer = OpenPushNotificationRenderer(context, loader)

        assertNotNull(
            renderer.render(
                OpenPushNotification.fromData(payload("op_android_big_picture" to "https://cdn/p.png")),
            ),
        )

        assertEquals(listOf("https://cdn/p.png"), loader.requested)
        val notification = posted()
        assertEquals(
            Notification.BigPictureStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertNotNull(notification.extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertEquals("there", notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
    }

    @Test fun aBigPictureThatCannotBeLoadedStillPostsAsBigText() {
        val loader = FakeLoader(null)
        val renderer = OpenPushNotificationRenderer(context, loader)

        assertNotNull(
            renderer.render(
                OpenPushNotification.fromData(payload("op_android_big_picture" to "https://cdn/p.png")),
            ),
        )

        assertEquals(listOf("https://cdn/p.png"), loader.requested)
        val notification = posted()
        assertEquals(
            Notification.BigTextStyle::class.java.name,
            notification.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals("there", notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test fun theLegacyImageUrlKeyIsAlsoABigPicture() {
        val loader = FakeLoader(bitmap())
        val renderer = OpenPushNotificationRenderer(context, loader)

        assertNotNull(
            renderer.render(OpenPushNotification.fromData(payload("image_url" to "https://cdn/i.png"))),
        )

        assertEquals(listOf("https://cdn/i.png"), loader.requested)
    }

    @Test fun largeIconIsSetWhenLoaded() {
        val loader = FakeLoader(bitmap())
        val renderer = OpenPushNotificationRenderer(context, loader)

        assertNotNull(
            renderer.render(
                OpenPushNotification.fromData(payload("op_android_large_icon" to "https://cdn/l.png")),
            ),
        )

        assertEquals(listOf("https://cdn/l.png"), loader.requested)
        assertNotNull(posted().getLargeIcon())
    }

    @Test fun aLargeIconThatCannotBeLoadedIsSimplyAbsent() {
        val renderer = OpenPushNotificationRenderer(context, FakeLoader(null))

        assertNotNull(
            renderer.render(
                OpenPushNotification.fromData(payload("op_android_large_icon" to "https://cdn/l.png")),
            ),
        )

        assertNull(posted().getLargeIcon())
    }

    @Test fun aLargeIconNamedInThePayloadIsAHostDrawable() {
        val icon = bitmap()
        val loader = FakeLoader(null)
        val renderer = rendererWithResources(mapOf(("ic_big" to "drawable") to 11), loader, mapOf(11 to icon))

        assertNotNull(renderer.render(OpenPushNotification.fromData(payload("op_android_large_icon" to "ic_big"))))

        assertTrue("a resource name is never fetched", loader.requested.isEmpty())
        assertTrue(icon.sameAs(largeIconOf(posted())))
    }

    @Test fun aLargeIconNameAlsoResolvesAsAMipmap() {
        val icon = bitmap()
        val renderer = rendererWithResources(mapOf(("ic_big" to "mipmap") to 12), bitmaps = mapOf(12 to icon))

        assertNotNull(renderer.render(OpenPushNotification.fromData(payload("op_android_large_icon" to "ic_big"))))

        assertNotNull(largeIconOf(posted()))
    }

    @Test fun withNoLargeIconTheBundledDefaultIsUsed() {
        val openpush = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val migrated = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val both = rendererWithResources(
            mapOf(
                ("ic_openpush_large_icon_default" to "drawable") to 21,
                ("ic_onesignal_large_icon_default" to "drawable") to 22,
            ),
            bitmaps = mapOf(21 to openpush, 22 to migrated),
        )
        assertNotNull(both.render(OpenPushNotification.fromData(payload())))
        assertEquals("the OpenPush name wins", 3, largeIconOf(posted())!!.width)

        manager.cancelAll()
        val migratedOnly = rendererWithResources(
            mapOf(("ic_onesignal_large_icon_default" to "drawable") to 22),
            bitmaps = mapOf(22 to migrated),
        )
        assertNotNull(migratedOnly.render(OpenPushNotification.fromData(payload())))
        assertEquals("a migrated app's bundled icon is honoured", 4, largeIconOf(posted())!!.width)
    }

    @Test fun aLargeIconThatFailsFallsBackToTheBundledDefault() {
        val fallback = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val resources = mapOf(("ic_openpush_large_icon_default" to "drawable") to 21)
        val failedUrl = rendererWithResources(resources, FakeLoader(null), mapOf(21 to fallback))
        assertNotNull(
            failedUrl.render(OpenPushNotification.fromData(payload("op_android_large_icon" to "https://cdn/l.png"))),
        )
        assertEquals(3, largeIconOf(posted())!!.width)

        manager.cancelAll()
        val unknownName = rendererWithResources(resources, bitmaps = mapOf(21 to fallback))
        assertNotNull(unknownName.render(OpenPushNotification.fromData(payload("op_android_large_icon" to "nope"))))
        assertEquals(3, largeIconOf(posted())!!.width)
        assertTrue(logs.any { it.contains("nope") })
    }

    @Test fun aPlainHttpLargeIconIsNeitherFetchedNorTreatedAsAName() {
        val loader = FakeLoader(bitmap())
        val renderer = rendererWithResources(emptyMap(), loader)

        assertNotNull(
            renderer.render(OpenPushNotification.fromData(payload("op_android_large_icon" to "http://cdn/l.png"))),
        )

        assertTrue(loader.requested.isEmpty())
        assertNull(posted().getLargeIcon())
    }

    @Test fun aResourceIsDrawnIntoABitmap() {
        val drawn = OpenPushNotificationRenderer(context).bitmapForResource(android.R.drawable.ic_dialog_info)

        assertNotNull(drawn)
        assertTrue(drawn!!.width > 0 && drawn.height > 0)
    }

    @Test fun aLargeIconIsScaledDownToTheLargeIconAreaOnly() {
        val renderer = OpenPushNotificationRenderer(context)
        val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)

        val scaled = renderer.scaleToLargeIconArea(Bitmap.createBitmap(width * 4, height * 2, Bitmap.Config.ARGB_8888))
        assertEquals(width, scaled.width)
        assertEquals(height / 2, scaled.height)

        val small = bitmap()
        assertTrue("never scaled up", renderer.scaleToLargeIconArea(small) === small)
    }

    @Suppress("DEPRECATION")
    @Test
    fun theSmallIconFallsBackToABundledDefaultBeforeTheAppIcon() {
        clearMeta()
        val migrated = rendererWithResources(mapOf(("ic_stat_onesignal_default" to "drawable") to 31))
        assertNotNull(migrated.render(OpenPushNotification.fromData(payload())))
        assertEquals(31, posted().icon)

        manager.cancelAll()
        val both = rendererWithResources(
            mapOf(("ic_stat_openpush_default" to "drawable") to 30, ("ic_stat_onesignal_default" to "drawable") to 31),
        )
        assertNotNull(both.render(OpenPushNotification.fromData(payload())))
        assertEquals(30, posted().icon)

        manager.cancelAll()
        setSmallIconMeta(777)
        assertNotNull(both.render(OpenPushNotification.fromData(payload())))
        assertEquals("the manifest value still wins", 777, posted().icon)
    }

    @Test fun bigPictureAndLargeIconUseOneRendererDeadline() {
        var now = 0L
        val deadlines = mutableListOf<Long>()
        val loader = OpenPushImageLoader(
            fetch = { _, deadline, _ ->
                deadlines += deadline
                now += if (deadlines.size == 1) 6_000_000_000L else 4_000_000_000L
                ByteArray(64) { 1 }
            },
            nanoTime = { now },
        )
        val renderer = OpenPushNotificationRenderer(context, loader)

        assertNotNull(
            renderer.render(
                OpenPushNotification.fromData(
                    payload(
                        "op_android_big_picture" to "https://cdn/p.png",
                        "op_android_large_icon" to "https://cdn/l.png",
                    ),
                ),
            ),
        )

        assertEquals(listOf(10_000_000_000L, 10_000_000_000L), deadlines)
        assertNotNull(posted().extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE))
        assertNull(posted().getLargeIcon())
    }

    // ---- actions and the tap intent ------------------------------------------------------------

    @Test fun actionsAreRenderedWithTheActionIdExtra() {
        val actions = """[{"id":"yes","label":"Accept"},{"id":"no","label":"Decline"}]"""

        assertNotNull(render(payload("op_actions" to actions)))

        val notification = posted()
        assertEquals(2, notification.actions.size)
        assertEquals("Accept", notification.actions[0].title)
        assertEquals("Decline", notification.actions[1].title)

        notification.actions.forEachIndexed { index, action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            val raw = intent.getBundleExtra(OpenPushClickActivity.EXTRA_RAW_DATA)!!
            assertEquals(
                OpenPushClickActivity::class.java.name,
                intent.component!!.className,
            )
            assertEquals(
                listOf("yes", "no")[index],
                raw.getString(OpenPush.ACTION_ID_KEY),
            )
            assertEquals("m1", raw.getString("op_message_id"))
        }
    }

    @Test fun theBodyTapCarriesTheRawDataAndNoActionId() {
        assertNotNull(render(payload("deep_link" to "app://order/7")))

        val intent = shadowOf(posted().contentIntent).savedIntent
        val raw = intent.getBundleExtra(OpenPushClickActivity.EXTRA_RAW_DATA)!!
        assertEquals(OpenPushClickActivity::class.java.name, intent.component!!.className)
        assertEquals("app://order/7", raw.getString("deep_link"))
        assertNull(raw.getString(OpenPush.ACTION_ID_KEY))
    }

    @Test fun theNotificationIdIsTheMessageIdHash() {
        val renderer = OpenPushNotificationRenderer(context)

        assertEquals("m1".hashCode(), renderer.render(OpenPushNotification.fromData(payload())))
        assertEquals("m1".hashCode(), renderer.notificationId("m1"))
    }

    @Test fun notificationIdIsStableForAMessageId() {
        val id = OpenPushNotificationRenderer.notificationIdFor("m1")

        // Same answer every call, from the companion and from the instance alike, so a host can
        // cancel what the renderer posted without having seen the render call.
        assertEquals(id, OpenPushNotificationRenderer.notificationIdFor("m1"))
        assertEquals(id, OpenPushNotificationRenderer(context).notificationId("m1"))
        assertEquals(id, render(payload()))
        // and it is the id the platform actually holds the notification under
        assertNotNull(shadowOf(manager).getNotification(id))
        assertNotEquals(id, OpenPushNotificationRenderer.notificationIdFor("m2"))
    }

    // ---- replacing by collapse key (op_android_replace) ---------------------------------------

    private fun renderWithCollapseKey(collapseKey: String?, vararg data: Pair<String, String>): Int? =
        OpenPushNotificationRenderer(context).render(OpenPushNotification.fromData(payload(*data), collapseKey))

    @Test fun aReplacingPushReplacesTheShownOneWithTheSameCollapseKey() {
        val first =
            renderWithCollapseKey("score", "op_message_id" to "m1", "body" to "1-0", "op_android_replace" to "1")
        val second = renderWithCollapseKey(
            "score",
            "op_message_id" to "m2",
            "body" to "2-0",
            "op_android_replace" to "1",
        )

        val id = OpenPushNotificationRenderer.replaceNotificationIdFor("score")
        assertEquals(id, first)
        assertEquals(id, second)
        val active = manager.activeNotifications.single()
        assertEquals("score", active.tag)
        assertEquals(id, active.id)
        assertEquals("2-0", active.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun differentCollapseKeysDoNotReplaceEachOther() {
        renderWithCollapseKey("score-a", "op_message_id" to "m1", "op_android_replace" to "1")
        renderWithCollapseKey("score-b", "op_message_id" to "m2", "op_android_replace" to "1")

        assertEquals(setOf("score-a", "score-b"), manager.activeNotifications.map { it.tag }.toSet())
    }

    @Test fun withoutTheFlagPushesWithACollapseKeyStackAsBefore() {
        val first = renderWithCollapseKey("score", "op_message_id" to "m1")
        val second = renderWithCollapseKey("score", "op_message_id" to "m2", "op_android_replace" to "0")

        assertEquals(OpenPushNotificationRenderer.notificationIdFor("m1"), first)
        assertEquals(OpenPushNotificationRenderer.notificationIdFor("m2"), second)
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.all { it.tag == null })
    }

    @Test fun theFlagWithoutACollapseKeyStacksAndLogs() {
        val first = renderWithCollapseKey(null, "op_message_id" to "m1", "op_android_replace" to "1")
        val second = renderWithCollapseKey("", "op_message_id" to "m2", "op_android_replace" to "1")

        assertEquals(OpenPushNotificationRenderer.notificationIdFor("m1"), first)
        assertEquals(OpenPushNotificationRenderer.notificationIdFor("m2"), second)
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(logs.any { it.contains("op_android_replace ignored") })
    }

    @Test fun aCollapseIdInTheDataMapIsUsedWhenTheProviderGaveNone() {
        renderWithCollapseKey(null, "op_message_id" to "m1", "op_android_replace" to "1", "op_collapse_id" to "lives")
        renderWithCollapseKey(null, "op_message_id" to "m2", "op_android_replace" to "1", "op_collapse_id" to "lives")

        val active = manager.activeNotifications.single()
        assertEquals("lives", active.tag)
        assertEquals(OpenPushNotificationRenderer.replaceNotificationIdFor("lives"), active.id)
    }

    @Test fun aReplacingNotificationCanBeRemovedByIdAndByGroup() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        val id = renderWithCollapseKey("score", "op_message_id" to "m1", "op_android_replace" to "1")!!

        OpenPush.Notifications.remove(id)
        assertTrue(manager.activeNotifications.isEmpty())

        renderWithCollapseKey(
            "score",
            "op_message_id" to "m2",
            "op_android_replace" to "1",
            "op_android_group" to "matches",
        )
        OpenPush.Notifications.removeGroup("matches")
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun postedIdsAreRecordedAndRemoveGroupCancelsOnlyThatGroup() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        assertNotNull(render(payload("op_message_id" to "order-1", "op_android_group" to "orders")))
        assertNotNull(render(payload("op_message_id" to "order-2", "op_android_group" to "orders")))
        assertNotNull(render(payload("op_message_id" to "news-1", "op_android_group" to "news")))

        OpenPush.Notifications.removeGroup("orders")

        assertEquals(
            listOf(OpenPushNotificationRenderer.notificationIdFor("news-1")),
            manager.activeNotifications.map { it.id },
        )
        assertEquals(
            listOf("news-1"),
            OpenPushPostedNotifications(context).entries().map { it.messageId },
        )
    }

    @Test fun groupedNotificationsGetASummaryAtTwo() {
        val group = "orders"
        val summaryId = summaryNotificationId(group)

        val firstId = render(
            payload("op_message_id" to "order-1", "body" to "first", "op_android_group" to group),
        )!!
        assertNull(shadowOf(manager).getNotification(summaryId))

        val secondId = render(
            payload("op_message_id" to "order-2", "body" to "second", "op_android_group" to group),
        )!!

        assertEquals(group, shadowOf(manager).getNotification(firstId).group)
        assertEquals(group, shadowOf(manager).getNotification(secondId).group)
        val summary = shadowOf(manager).getNotification(summaryId)
        assertNotNull(summary)
        assertEquals(group, summary.group)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.groupAlertBehavior)
        assertEquals(
            listOf("first", "second"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map(CharSequence::toString),
        )
        assertEquals(2, OpenPushPostedNotifications(context).entries().size)
    }

    @Test fun theSummaryIsRefreshedOnTheThirdChild() {
        val group = "orders"
        (1..3).forEach { number ->
            assertNotNull(
                render(
                    payload(
                        "op_message_id" to "order-$number",
                        "body" to "body-$number",
                        "op_android_group" to group,
                    ),
                ),
            )
        }

        val summary = shadowOf(manager).getNotification(summaryNotificationId(group))
        assertEquals("3 notifications", summary.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            listOf("body-1", "body-2", "body-3"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map(CharSequence::toString),
        )
        assertEquals(4, manager.activeNotifications.size)
    }

    @Test fun removeGroupCancelsChildrenAndSummary() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        val group = "orders"
        val childIds = (1..2).map { number ->
            render(
                payload("op_message_id" to "order-$number", "op_android_group" to group),
            )!!
        }
        assertNotNull(shadowOf(manager).getNotification(summaryNotificationId(group)))

        OpenPush.Notifications.removeGroup(group)

        childIds.forEach { assertNull(shadowOf(manager).getNotification(it)) }
        assertNull(shadowOf(manager).getNotification(summaryNotificationId(group)))
        assertTrue(OpenPushPostedNotifications(context).entries().isEmpty())
    }

    @Test fun removingOneOfTwoGroupedChildrenAlsoRemovesTheSummary() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        val group = "orders"
        val firstId = render(payload("op_message_id" to "order-1", "op_android_group" to group))!!
        val secondId = render(payload("op_message_id" to "order-2", "op_android_group" to group))!!
        assertNotNull(shadowOf(manager).getNotification(summaryNotificationId(group)))

        OpenPush.Notifications.remove(firstId)

        assertNull(shadowOf(manager).getNotification(firstId))
        assertNotNull(shadowOf(manager).getNotification(secondId))
        assertNull(shadowOf(manager).getNotification(summaryNotificationId(group)))
        assertEquals(listOf("order-2"), OpenPushPostedNotifications(context).entries().map { it.messageId })
    }

    @Test fun systemDismissalRemovesTheOrphanedSummaryAtTheNextRender() {
        val group = "orders"
        val childIds = (1..2).map { number ->
            render(
                payload("op_message_id" to "order-$number", "op_android_group" to group),
            )!!
        }
        childIds.forEach(manager::cancel)
        assertNotNull(shadowOf(manager).getNotification(summaryNotificationId(group)))

        val ungroupedId = render(payload("op_message_id" to "news-1"))!!

        assertNull(shadowOf(manager).getNotification(summaryNotificationId(group)))
        assertNotNull(shadowOf(manager).getNotification(ungroupedId))
        assertEquals(listOf("news-1"), OpenPushPostedNotifications(context).entries().map { it.messageId })
    }

    @Test fun groupedSummariesContainOnlyTheLastFiveChildBodies() {
        val group = "orders"
        (1..6).forEach { number ->
            render(
                payload(
                    "op_message_id" to "order-$number",
                    "body" to "body-$number",
                    "op_android_group" to group,
                ),
            )
        }

        val summary = shadowOf(manager).getNotification(summaryNotificationId(group))
        assertEquals(
            (2..6).map { "body-$it" },
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map(CharSequence::toString),
        )
    }

    // ---- receipts and refusals -----------------------------------------------------------------

    @Test fun confirmedIsPostedAfterNotify() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            urls += it.url
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        val registered = CountDownLatch(1).also { gate = it }
        OpenPush.register("provider-token")
        assertTrue(registered.await(2, TimeUnit.SECONDS))
        bodies.clear()
        urls.clear()

        val confirmed = CountDownLatch(1).also { gate = it }
        assertNotNull(render())
        assertTrue(confirmed.await(2, TimeUnit.SECONDS))

        assertTrue(urls.single().endsWith("/v1/ingest"))
        val event = JSONObject(bodies.single()).getJSONArray("events").getJSONObject(0)
        assertEquals("confirmed", event.getString("type"))
        assertEquals("m1", event.getString("message_id"))
    }

    @Test fun nothingIsPostedWithoutAMessageId() {
        assertFalse(OpenPushNotificationRenderer(context).render(mapOf("title" to "hi", "body" to "there")))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(
            "no channel is created for a push OpenPush did not send",
            shadowOf(manager).notificationChannels.isEmpty(),
        )
        assertTrue(logs.any { it.contains("op_message_id") })
    }

    @Test fun nothingIsPostedWhenTitleAndBodyAreBothEmpty() {
        assertFalse(OpenPushNotificationRenderer(context).render(mapOf("op_message_id" to "m1")))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(logs.any { it.contains("title and body") })
    }

    @Test fun anEmptyTitleFallsBackToTheAppLabel() {
        assertNotNull(render(mapOf("op_message_id" to "m1", "body" to "there")))

        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        assertEquals(label, posted().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
