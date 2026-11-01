package ai.openpush.android.inappmessages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bridge is the one place the message document can reach, so it is deliberately the dullest
 * class in the module: it turns an envelope into a [PresenterEvent] and hands it on. Anything it
 * cannot read is dropped rather than guessed at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class IamBridgeTests {
    private val events = mutableListOf<PresenterEvent>()
    private val bridge = IamBridge { events += it }

    @Test
    fun bridgeParsesRenderingComplete() {
        bridge.postMessage(
            """{"type":"rendering_complete","displayLocation":"top",""" +
                """"pageMetaData":{"rect":{"height":120}},"dragToDismissDisabled":false}""",
        )

        assertEquals(PresenterEvent.Rendered("top", 120, false), events.single())
    }

    @Test
    fun aRenderingCompleteWithoutMetadataStillRenders() {
        bridge.postMessage("""{"type":"rendering_complete","dragToDismissDisabled":true}""")

        assertEquals(PresenterEvent.Rendered("center", 0, true), events.single())
    }

    @Test
    fun bridgeParsesPageChange() {
        bridge.postMessage("""{"type":"page_change","pageIndex":2,"pageId":"page_3"}""")

        assertEquals(PresenterEvent.PageChanged("page_3", 2), events.single())
    }

    @Test
    fun aPageChangeWithoutAPageIdIsDropped() {
        bridge.postMessage("""{"type":"page_change","pageIndex":2}""")

        assertTrue(events.isEmpty())
    }

    @Test
    fun bridgeParsesActionTaken() {
        bridge.postMessage(
            """{"type":"action_taken","body":{"id":"c1","block_key":"b1","name":"cta",""" +
                """"url":"https://example.invalid/offer","url_target":"browser","close":true,""" +
                """"prompts":["push","location"],"tags":{"adds":{"plan":"pro","seats":"3"}},""" +
                """"outcomes":[{"name":"purchase"},{"name":"upgrade"}]}}""",
        )

        val action = (events.single() as PresenterEvent.Action).action
        assertEquals("c1", action.clickId)
        assertEquals("b1", action.blockKey)
        assertEquals("cta", action.actionId)
        assertEquals("https://example.invalid/offer", action.url)
        assertEquals("browser", action.urlTarget)
        assertTrue(action.closes)
        assertEquals(listOf("push", "location"), action.prompts)
        assertEquals(mapOf("plan" to "pro", "seats" to "3"), action.tags)
        assertEquals(listOf("purchase", "upgrade"), action.outcomes)
    }

    @Test
    fun aBareActionTakenCarriesNothingButItself() {
        bridge.postMessage("""{"type":"action_taken","body":{}}""")

        val action = (events.single() as PresenterEvent.Action).action
        assertNull(action.clickId)
        assertNull(action.blockKey)
        assertNull(action.url)
        assertTrue(action.prompts.isEmpty())
        assertTrue(action.tags.isEmpty())
        assertTrue(action.outcomes.isEmpty())
    }

    @Test
    fun unreadableEnvelopesAreDropped() {
        bridge.postMessage("not json at all")
        bridge.postMessage("""{"type":"something_else"}""")
        bridge.postMessage("""{"type":"action_taken"}""")

        assertTrue(events.isEmpty())
    }
}
