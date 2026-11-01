package ai.openpush.android

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushNotificationModelTests {
    @Before fun quietLogs() {
        OpenPush.onLog = {}
    }

    private val full = mapOf(
        "op_message_id" to "m1",
        "title" to "Order shipped",
        "body" to "It is on its way",
        "image_url" to "https://example.test/hero.png",
        "deep_link" to "app://orders/7",
        "op_android_channel" to "promos",
        "op_android_accent" to "#FF3366",
        "op_android_large_icon" to "https://example.test/icon.png",
        "op_android_big_picture" to "https://example.test/big.png",
        "op_android_group" to "orders",
        "op_android_category" to "transport",
        "op_android_visibility" to "public",
        "op_android_sound" to "chime",
        "op_android_small_icon" to "ic_push",
        "op_android_led" to "#8800FF00",
        "op_actions" to """[{"id":"open","label":"Open"},{"id":"reply","label":"Reply"}]""",
        "order_id" to "A-1",
    )

    @Test fun serverUtmUrlIsPreservedWithoutDecodingOrRetagging() {
        val url =
            "https://example.com/offers?utm_source=owned&utm_medium=push" +
                "&utm_campaign=Launch+day&utm_term=a%2Bb&coupon=&coupon=SAVE#details"
        val notification = OpenPushNotification.fromData(mapOf("op_message_id" to "utm-1", "deep_link" to url))
        assertEquals(url, notification.launchUrl)
        assertEquals(url, notification.rawData["deep_link"])
        assertFalse(notification.additionalData.containsKey("utm_source"))
    }

    @Test fun everyComposerFieldIsParsed() {
        val notification = OpenPushNotification.fromData(full, collapseKey = "delivery-42")

        assertEquals("m1", notification.messageId)
        assertTrue(notification.isOpenPush)
        assertEquals("Order shipped", notification.title)
        assertEquals("It is on its way", notification.body)
        assertEquals("https://example.test/hero.png", notification.imageUrl)
        assertEquals("app://orders/7", notification.launchUrl)
        assertEquals("promos", notification.channelId)
        assertEquals(0xFFFF3366.toInt(), notification.accentColor)
        assertEquals("https://example.test/icon.png", notification.largeIconUrl)
        assertEquals("https://example.test/big.png", notification.bigPictureUrl)
        assertEquals("orders", notification.group)
        assertEquals("transport", notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertEquals("chime", notification.sound)
        assertEquals("ic_push", notification.smallIcon)
        assertEquals(0x8800FF00.toInt(), notification.ledColor)
        assertEquals(listOf("open", "reply"), notification.actions.map { it.id })
        assertEquals(listOf("Open", "Reply"), notification.actions.map { it.label })
        assertEquals("delivery-42", notification.collapseKey)
        assertEquals(mapOf("order_id" to "A-1"), notification.additionalData)
        assertEquals(full, notification.rawData)

        // A six-digit colour is opaque, with or without the hash.
        assertEquals(
            0xFFFF3366.toInt(),
            OpenPushNotification.fromData(mapOf("op_android_accent" to "ff3366")).accentColor,
        )

        // Eight digits are ARGB, so the composer can send a translucent accent.
        assertEquals(
            0x80FF3366.toInt(),
            OpenPushNotification.fromData(mapOf("op_android_accent" to "#80FF3366")).accentColor,
        )
        assertEquals(
            0x00FF3366,
            OpenPushNotification.fromData(mapOf("op_android_accent" to "00ff3366")).accentColor,
        )

        // Named and numeric visibilities agree.
        assertEquals(
            Notification.VISIBILITY_PRIVATE,
            OpenPushNotification.fromData(mapOf("op_android_visibility" to "private")).visibility,
        )
        assertEquals(
            Notification.VISIBILITY_SECRET,
            OpenPushNotification.fromData(mapOf("op_android_visibility" to "secret")).visibility,
        )
        assertEquals(
            Notification.VISIBILITY_SECRET,
            OpenPushNotification.fromData(mapOf("op_android_visibility" to "-1")).visibility,
        )
        assertEquals(
            Notification.VISIBILITY_PUBLIC,
            OpenPushNotification.fromData(mapOf("op_android_visibility" to "1")).visibility,
        )
        assertNull(OpenPushNotification.fromData(mapOf("op_android_visibility" to "2")).visibility)
        assertNull(OpenPushNotification.fromData(mapOf("op_android_visibility" to "loud")).visibility)

        // The big picture falls back to the composer's image when no Android-specific one was set.
        val fallback = OpenPushNotification.fromData(full - "op_android_big_picture")
        assertEquals("https://example.test/hero.png", fallback.bigPictureUrl)
        assertNull(OpenPushNotification.fromData(mapOf("op_message_id" to "m1")).bigPictureUrl)

        // The collapse key is the provider's, not the data map's.
        assertNull(OpenPushNotification.fromData(full).collapseKey)
    }

    @Test fun additionalDataExcludesReservedAndOpKeys() {
        val notification = OpenPushNotification.fromData(
            mapOf(
                "title" to "hi",
                "body" to "there",
                "op_message_id" to "m1",
                "op_android_channel" to "promos",
                "deep_link" to "app://x",
                "order_id" to "A-1",
            ),
        )

        assertEquals(mapOf("order_id" to "A-1"), notification.additionalData)
    }

    @Test fun aForeignMapIsNotOpenPush() {
        val notification = OpenPushNotification.fromData(mapOf("title" to "hi", "body" to "from elsewhere"))

        assertFalse(notification.isOpenPush)
        assertNull(notification.messageId)
        // Everything else still parses, so a host that wants to render it can.
        assertEquals("hi", notification.title)
        assertEquals("from elsewhere", notification.body)

        // An empty id is no id, the same rule the receipts use.
        assertFalse(OpenPushNotification.fromData(mapOf("op_message_id" to "")).isOpenPush)
    }

    @Test fun malformedColorsAreNull() {
        val bad = listOf("", "#", "xyz", "#GGGGGG", "#FF33", "#FF33660", "#FF3366FF00", "0xFF3366")
        bad.forEach { value ->
            assertNull(
                "accepted $value",
                OpenPushNotification.fromData(mapOf("op_android_accent" to value)).accentColor,
            )
            assertNull(
                "accepted $value",
                OpenPushNotification.fromData(mapOf("op_android_led" to value)).ledColor,
            )
        }
        assertNull(OpenPushNotification.fromData(emptyMap()).accentColor)
        assertNull(OpenPushNotification.fromData(emptyMap()).ledColor)
    }

    @Test fun onlyAFlaggedPushWithoutTextIsBackground() {
        assertTrue(OpenPushNotification.fromData(mapOf("op_message_id" to "m1", "op_background" to "1")).isBackground)
        assertTrue(
            OpenPushNotification.fromData(
                mapOf("op_message_id" to "m1", "op_background" to "1", "title" to "", "body" to ""),
            ).isBackground,
        )
        assertFalse(OpenPushNotification.fromData(mapOf("op_background" to "1", "body" to "hi")).isBackground)
        assertFalse(OpenPushNotification.fromData(mapOf("op_background" to "0")).isBackground)
        assertFalse(OpenPushNotification.fromData(mapOf("op_background" to "true")).isBackground)
        assertFalse(OpenPushNotification.fromData(emptyMap()).isBackground)
        assertFalse(
            "the flag is a control key, not host data",
            OpenPushNotification.fromData(mapOf("op_background" to "1")).additionalData.containsKey("op_background"),
        )
    }
}
