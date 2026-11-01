package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the host is told about a tap, and in what shape.
 *
 * The rule the first two tests are about: the click *receipt* is deduplicated — the server counts a
 * click id once — but the click *listener* is not. A second tap on the same button is a second
 * thing the user did, and a host that routes on it would silently lose the repeat if the engine
 * folded the two together. [InAppMessageClickEvent.firstClick] is what carries the distinction, and
 * it is about the message, not the button: it is true only for the very first click id recorded
 * against this message.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamEngineClickTests : IamTestCase() {

    @Test
    fun theClickListenerFiresForEveryActionWithAnId() {
        fetchFixture(oneMessage("iam_1"))
        val events = recordClicks()

        tap(clickId = "c1", actionId = "buy")
        tap(clickId = "c1", actionId = "buy")

        assertEquals(listOf("buy", "buy"), events.map { it.actionId })
        assertEquals(1, clickReceipts().size)
    }

    @Test
    fun firstClickIsTrueOnlyOnce() {
        fetchFixture(oneMessage("iam_1"))
        val events = recordClicks()

        tap(clickId = "c1")
        tap(clickId = "c2")
        tap(clickId = "c1")

        assertEquals(listOf(true, false, false), events.map { it.firstClick })
        // The receipt carries the same fact, for the two click ids the server has not seen yet.
        assertEquals(listOf(true, false), clickReceipts().map { it.getBoolean("first_click") })
    }

    @Test
    fun aClickCarriesThePageItWasMadeOn() {
        fetchFixture(oneMessage("iam_1"))
        val events = recordClicks()

        tap(clickId = "c0")
        presenter.emit(PresenterEvent.PageChanged("page_b", 1))
        idle()
        tap(clickId = "c1")

        assertNull(events[0].pageId)
        assertEquals("page_b", events[1].pageId)
    }

    @Test
    fun lifecycleEventsCarryATypedStage() {
        val events = mutableListOf<InAppMessageLifecycleEvent>()
        OpenPushInAppMessages.addLifecycleListener { events += it }

        fetchFixture(oneMessage("iam_1"))
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()
        presenter.emit(PresenterEvent.Dismissed)
        idle()

        assertEquals(
            listOf(
                InAppLifecycleStage.WILL_DISPLAY,
                InAppLifecycleStage.DID_DISPLAY,
                InAppLifecycleStage.WILL_DISMISS,
                InAppLifecycleStage.DID_DISMISS,
            ),
            events.map { it.stage },
        )
        assertEquals(
            listOf("willDisplay", "didDisplay", "willDismiss", "didDismiss"),
            events.map { it.phase },
        )
    }

    @Test
    fun theTwoArgumentLifecycleEventMapsItsPhase() {
        assertEquals(
            InAppLifecycleStage.WILL_DISMISS,
            InAppMessageLifecycleEvent("iam_1", "willDismiss").stage,
        )
        assertEquals(InAppLifecycleStage.UNKNOWN, InAppMessageLifecycleEvent("iam_1", "somethingElse").stage)
    }

    @Test
    fun publicEventsRetainBothDataClassGenerations() {
        val lifecycle = InAppMessageLifecycleEvent("iam_1", "didDisplay", InAppLifecycleStage.DID_DISPLAY)
        val click = InAppMessageClickEvent("iam_1", "buy", null, null, true, true, "page_2")

        assertEquals(lifecycle, lifecycle.copy())
        assertEquals(InAppLifecycleStage.UNKNOWN, lifecycle.copy("iam_2", "futurePhase").stage)
        assertNotEquals(lifecycle, lifecycle.copy(stage = InAppLifecycleStage.WILL_DISPLAY))
        assertEquals(click, click.copy())
        assertTrue(click.copy("iam_2", "save", null, null, false).firstClick)
        assertEquals("page_2", click.copy("iam_2", "save", null, null, false).pageId)
        assertEquals("iam_1", click.component1())
        assertEquals("page_2", click.component7())

        InAppMessageLifecycleEvent::class.java.getDeclaredMethod(
            "copy\$default",
            InAppMessageLifecycleEvent::class.java,
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        InAppMessageClickEvent::class.java.getDeclaredMethod(
            "copy\$default",
            InAppMessageClickEvent::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
    }

    private fun tap(clickId: String, actionId: String? = null) {
        presenter.emit(PresenterEvent.Action(IamAction(clickId = clickId, actionId = actionId)))
        idle()
    }

    private fun recordClicks(): List<InAppMessageClickEvent> {
        val events = mutableListOf<InAppMessageClickEvent>()
        OpenPushInAppMessages.addClickListener { events += it }
        return events
    }

    private fun clickReceipts(): List<JSONObject> =
        ingestRequests().map { JSONObject(it.body!!).getJSONArray("events").getJSONObject(0) }
            .filter { it.getString("type") == "iam_click" }
}
