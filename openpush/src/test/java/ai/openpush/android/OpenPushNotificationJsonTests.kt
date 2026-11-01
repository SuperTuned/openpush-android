package ai.openpush.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The JSON form wrapper SDKs hand across their bridge: snake_case, one key per model field. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushNotificationJsonTests {
    @Test fun carriesTheCrossPlatformFieldsActionsAndRawData() {
        val data = mapOf(
            "op_message_id" to "m1",
            "title" to "Hi",
            "body" to "There",
            "image_url" to "https://cdn.example/i.png",
            "deep_link" to "game://shop",
            "op_actions" to """[{"id":"open","label":"Open"}]""",
            "promo" to "spring",
        )
        val json = OpenPushNotification.fromData(data, "collapse-1").toJson()
        assertEquals("m1", json.getString("message_id"))
        assertEquals("Hi", json.getString("title"))
        assertEquals("There", json.getString("body"))
        assertEquals("https://cdn.example/i.png", json.getString("image_url"))
        assertEquals("game://shop", json.getString("launch_url"))
        assertEquals("collapse-1", json.getString("collapse_id"))
        val action = json.getJSONArray("actions").getJSONObject(0)
        assertEquals("open", action.getString("id"))
        assertEquals("Open", action.getString("label"))
        assertFalse(action.getBoolean("system_icon"))
        assertTrue("a button without a url carries JSON null", action.isNull("url"))
        assertEquals("spring", json.getJSONObject("additional_data").getString("promo"))
        assertEquals("m1", json.getJSONObject("data").getString("op_message_id"))
    }

    @Test fun anActionUrlIsCarried() {
        val json = OpenPushNotification.fromData(
            mapOf("op_actions" to """[{"id":"shop","label":"Shop","url":"game://shop"}]"""),
        ).toJson()
        assertEquals("game://shop", json.getJSONArray("actions").getJSONObject(0).getString("url"))
    }

    @Test fun absentFieldsAreJsonNullAndCollectionsAreEmpty() {
        val json = OpenPushNotification.fromData(emptyMap()).toJson()
        assertTrue(json.isNull("message_id"))
        assertTrue(json.isNull("collapse_id"))
        assertTrue(json.isNull("launch_url"))
        assertEquals(0, json.getJSONArray("actions").length())
        assertEquals(0, json.getJSONObject("data").length())
    }
}
