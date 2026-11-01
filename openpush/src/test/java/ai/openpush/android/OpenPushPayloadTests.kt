package ai.openpush.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPayloadTests {
    @Test fun registrationAckRetainsBothDataClassGenerations() {
        val ack = RegistrationAck("sub-1", "note", listOf("reserved"), -2, listOf("email"))
        val (subscriptionId, rejection, refused, status, notCollected) = ack

        assertEquals("sub-1", subscriptionId)
        assertEquals("note", rejection)
        assertEquals(listOf("reserved"), refused)
        assertEquals(-2, status)
        assertEquals(listOf("email"), notCollected)
        assertEquals(ack, ack.copy())
        assertEquals(-2, ack.copy("sub-2", null, emptyList()).status)
        assertNotEquals(ack, ack.copy(status = 1))
        assertTrue(ack.toString().contains("status=-2"))

        RegistrationAck::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            List::class.java,
        )
        RegistrationAck::class.java.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            kotlin.jvm.internal.DefaultConstructorMarker::class.java,
        )
        RegistrationAck::class.java.getDeclaredMethod(
            "copy\$default",
            RegistrationAck::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    @Test fun messageIdIsStrict() {
        assertEquals("m1", OpenPushPayload.messageId(mapOf("op_message_id" to "m1")))
        assertNull(OpenPushPayload.messageId(mapOf("id" to "m1")))
        assertNull(OpenPushPayload.messageId(mapOf("op_message_id" to "")))
    }

    @Test fun receiptBodyCarriesEveryType() {
        val payload = JSONObject(OpenPushPayload.receiptBody(listOf("received", "confirmed"), "m1", "token", "demo"))
        assertEquals(2, payload.getJSONArray("events").length())
        assertEquals("m1", payload.getJSONArray("events").getJSONObject(0).getString("message_id"))
    }

    @Test fun actionsParseInDisplayOrderAndIgnoreMalformedRows() {
        val actions = OpenPushPayload.actions(
            mapOf(
                "op_actions" to
                    """[{"id":"save","label":"Save","icon":"bookmark","system_icon":true},""" +
                    """{"id":"","label":"Broken"},{"id":"share","label":"Share"}]""",
            ),
        )
        assertEquals(listOf("save", "share"), actions.map { it.id })
        assertEquals("Save", actions.first().label)
        assertEquals("bookmark", actions.first().icon)
        assertEquals(true, actions.first().systemIcon)
        assertEquals(emptyList<OpenPushAction>(), OpenPushPayload.actions(mapOf("op_actions" to "nope")))
    }

    @Test fun anActionMayCarryItsOwnUrl() {
        val actions = OpenPushPayload.actions(
            mapOf(
                "op_actions" to
                    """[{"id":"shop","label":"Shop","url":"  https://example.com/shop  "},""" +
                    """{"id":"later","label":"Later"},{"id":"blank","label":"Blank","url":""}]""",
            ),
        )
        assertEquals("https://example.com/shop", actions[0].url)
        assertEquals(listOf(null, null), actions.drop(1).map { it.url })
        val nulled = OpenPushPayload.actions(mapOf("op_actions" to """[{"id":"n","label":"N","url":null}]"""))
        assertEquals(null, nulled.single().url)
        // The four-argument constructor still links and leaves the url empty.
        assertEquals(null, OpenPushAction("a", "A", "", false).url)
        assertEquals(OpenPushAction("a", "A", "", false), OpenPushAction("a", "A"))
    }
}
