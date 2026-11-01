package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(OpenPushInternalApi::class)
internal class InAppReceiptTests : IamTestCase() {
    @Test
    fun aMissingReceiptPrerequisiteIsNamedInTheLog() {
        fetchFixture(oneMessage("iam_1"))
        val message = presenter.shows.single().message
        val logs = mutableListOf<String>()
        OpenPush.onLog = { logs += it }

        OpenPush.clearToken()
        OpenPushInAppMessages.engine.receipt(message, "iam_impression", emptyMap()) {}
        assertTrue(logs.toString(), logs.any { it.contains("provider token is unavailable") })

        OpenPush::class.java.getDeclaredMethod("resetForTests").invoke(null)
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPushInAppMessages.engine.receipt(message, "iam_impression", emptyMap()) {}
        assertTrue(logs.toString(), logs.any { it.contains("app id is unavailable") })
    }

    @Test
    fun anImpressionReceiptUsesTheIngestEnvelopeWithAppMessageIdAndToken() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Rendered("center", 320, false))

        val ingest = ingestRequests().single()
        val event = JSONObject(ingest.body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("iam_impression", event.getString("type"))
        assertEquals("demo", event.getString("app"))
        assertEquals("iam_1", event.getString("message_id"))
        assertEquals("provider-token", event.getString("token"))
    }

    @Test
    fun aPageImpressionReceiptCarriesThePageId() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        presenter.emit(PresenterEvent.PageChanged("page_2", 1))

        val event = JSONObject(ingestRequests().last().body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("iam_page_impression", event.getString("type"))
        assertEquals("iam_1", event.getString("message_id"))
        assertEquals("page_2", event.getString("page_id"))
    }

    @Test
    fun aClickReceiptCarriesClickIdBlockKeyAndFirstClick() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c1", blockKey = "b1")))

        val event = JSONObject(ingestRequests().single().body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("iam_click", event.getString("type"))
        assertEquals("iam_1", event.getString("message_id"))
        assertEquals("provider-token", event.getString("token"))
        assertEquals("c1", event.getString("click_id"))
        assertEquals("b1", event.getString("block_key"))
        assertTrue(event.getBoolean("first_click"))

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c1", blockKey = "b1")))
        assertEquals(1, ingestRequests().size)
    }

    @Test
    fun theSecondDistinctClickIsNotAFirstClick() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c1", blockKey = "b1")))
        presenter.emit(PresenterEvent.Action(IamAction(clickId = "c2", blockKey = "b2")))

        val event = JSONObject(ingestRequests().last().body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("c2", event.getString("click_id"))
        assertFalse(event.getBoolean("first_click"))
    }

    @Test
    fun aFailedReceiptRollsTheDedupeBack() {
        ingestStatus = 503
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Rendered("center", 320, false))

        assertEquals(1, ingestRequests().size)
        assertFalse("iam_1" in OpenPushInAppMessages.state.impressions)

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        assertEquals(2, ingestRequests().size)
    }

    @Test
    fun anImpressionIsSentOnceWhileTheReceiptIsAccepted() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        presenter.emit(PresenterEvent.Rendered("center", 320, false))

        assertEquals(1, ingestRequests().size)
        assertTrue("iam_1" in OpenPushInAppMessages.state.impressions)
    }
}
