package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the engine does with what the presenter tells it.
 *
 * The rule the whole class is about: a message counts as displayed when its document says it has
 * rendered, not when the surface is asked to come up. Everything that follows — the impression, the
 * `didDisplay` callback, the display-duration timer, the generation stamp that stops a redisplay
 * loop — hangs off that one event, and the paths where rendering never happens (a failed load, a
 * pause) leave no trace behind.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamEnginePresentationTests : IamTestCase() {

    @Test
    fun impressionAndDidDisplayFireOnRenderedOnly() {
        val phases = recordPhases()
        fetchFixture(oneMessage("iam_1"))

        assertEquals(listOf("willDisplay"), phases)
        assertTrue(ingestRequests().isEmpty())

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        assertEquals(listOf("willDisplay", "didDisplay"), phases)
        assertEquals(listOf("iam_impression"), ingestRequests().map(::typeOf))

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        assertEquals(listOf("willDisplay", "didDisplay"), phases)
    }

    @Test
    fun aFailedLoadMovesOnToTheNextMessageAndRecordsNothing() {
        val phases = recordPhases()
        fetchFixture(twoMessages())
        assertEquals("the first message goes up first", listOf("a"), shownIds())

        presenter.emit(PresenterEvent.Failed("net::ERR_NAME_NOT_RESOLVED"))
        idle()

        assertTrue("no impression for a page that never rendered", ingestRequests().isEmpty())
        assertNull("no redisplay record", OpenPushInAppMessages.state.redisplay["a"])
        assertFalse("a failed load is not a user dismissal", "a" in OpenPushInAppMessages.state.dismissed)
        assertFalse("no willDismiss for a failed load", "willDismiss" in phases)
        assertFalse("no didDismiss for a failed load", "didDismiss" in phases)
        assertEquals("the surface is taken down once", 1, presenter.hides)
        assertEquals("the next message replaces the failed one", listOf("a", "b"), shownIds())
    }

    @Test
    fun aFailedLoadDoesNotCountADisplay() {
        val phases = recordPhases()
        fetchFixture(oneMessage("a", displayDurationSeconds = 5))

        presenter.emit(PresenterEvent.Failed("net::ERR_NAME_NOT_RESOLVED"))
        idle()

        assertEquals("only the attempt is announced, never didDisplay", listOf("willDisplay"), phases)
        assertNull("no generation stamp", OpenPushInAppMessages.state.displayedGeneration["a"])
        assertNull("no redisplay record", OpenPushInAppMessages.state.redisplay["a"])
        assertTrue("no impression", ingestRequests().isEmpty())
        assertEquals("no display timer for a page that never rendered", 0, scheduler.pending)
    }

    /**
     * One message only, so nothing else is on screen to block `presentNext`: if abandonment did
     * nothing, the failed message would come straight back and the first assertion would fail.
     */
    @Test
    fun anAbandonedMessageStaysAbandonedUntilTheNextFetch() {
        fetchFixture(oneMessage("a"))

        presenter.emit(PresenterEvent.Failed("net::ERR_NAME_NOT_RESOLVED"))
        idle()
        assertEquals("the failed message does not come straight back", listOf("a"), shownIds())
        assertNull("nothing is on screen", OpenPushInAppMessages.engine.current)

        OpenPushInAppMessages.addTrigger("anything", 1)
        idle()
        assertEquals("a re-evaluation does not bring it back", listOf("a"), shownIds())

        // A fetch clears the abandoned set by design: the next document may well load.
        OpenPushInAppMessages.engine.fetch(force = true)
        await("the message to be shown again") { presenter.shows.size > 1 }
        assertEquals("a fetch gives it another chance", listOf("a", "a"), shownIds())
    }

    @Test
    fun pausedHidesAndReturnsTheMessageToTheQueueHead() {
        val phases = recordPhases()
        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        OpenPushInAppMessages.paused = true
        idle()

        assertEquals(1, presenter.hides)
        assertFalse("willDismiss" in phases)
        assertFalse("didDismiss" in phases)
        assertNull(OpenPushInAppMessages.state.redisplay["a"])
        assertFalse("a" in OpenPushInAppMessages.state.dismissed)
        assertNull(OpenPushInAppMessages.engine.current)
        assertEquals(1, presenter.shows.size)

        OpenPushInAppMessages.paused = false
        idle()

        assertEquals(listOf("a", "a"), presenter.shows.map { it.message.id })
    }

    @Test
    fun aUserDismissalRunsTheDismissPath() {
        val phases = recordPhases()
        fetchFixture(twoMessages())
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        presenter.emit(PresenterEvent.Dismissed)

        assertTrue("a" in OpenPushInAppMessages.state.dismissed)
        assertNull(OpenPushInAppMessages.engine.current)
        assertEquals(1, presenter.shows.size)

        idle()

        // Listeners are posted to the main thread, so they are read after the idle. The dismissal's
        // two phases still land before the next message's willDisplay: one queue, in order.
        assertEquals(listOf("willDisplay", "didDisplay", "willDismiss", "didDismiss", "willDisplay"), phases)
        assertEquals(listOf("a", "b"), presenter.shows.map { it.message.id })
    }

    @Test
    fun theDisplayTimerStartsOnRenderedAndAPauseCancelsIt() {
        fetchFixture(oneMessage("iam_1", displayDurationSeconds = 5))

        assertEquals(0, scheduler.pending)

        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()
        assertEquals(1, scheduler.pending)

        OpenPushInAppMessages.paused = true
        idle()
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun theDisplayTimerTakesTheMessageDownWithoutADismissal() {
        val phases = recordPhases()
        fetchFixture(oneMessage("iam_1", displayDurationSeconds = 5))
        presenter.emit(PresenterEvent.Rendered("center", 320, false))
        idle()

        scheduler.advance(5_000)
        idle()

        assertEquals(listOf("willDisplay", "didDisplay", "willDismiss", "didDismiss"), phases)
        assertFalse("iam_1" in OpenPushInAppMessages.state.dismissed)
        assertEquals(1, OpenPushInAppMessages.state.redisplay["iam_1"]?.quantity)
    }

    @Test
    fun anActionAppliesItsHostEffectsAndThenCloses() {
        fetchFixture(oneMessage("iam_1"))

        presenter.emit(
            PresenterEvent.Action(
                IamAction(
                    clickId = "c1",
                    closes = true,
                    tags = mapOf("plan" to "pro"),
                    outcomes = listOf("purchase"),
                ),
            ),
        )
        idle()

        assertEquals("pro", OpenPush.User.getTags()["plan"])
        assertTrue(ingestRequests().map(::typeOf).contains("iam_click"))
        assertNull(OpenPushInAppMessages.engine.current)
        await("the outcome to be tracked") {
            recordedRequests().any { it.url.endsWith("/v1/apps/demo/events") }
        }
    }

    /**
     * The engine is main-thread-confined, so a host that pauses or moves a trigger from a worker
     * thread changes nothing until the looper runs the post. Asserting *before* the idle is the
     * half that matters: it proves the worker thread never reached the display state itself.
     */
    @Test
    fun aHostThreadReachesTheEngineThroughTheMainLooper() {
        fetchFixture(oneMessage("iam_1"))
        assertEquals(1, presenter.shows.size)
        val generationBefore = OpenPushInAppMessages.engine.triggerGeneration

        val worker = Thread {
            OpenPushInAppMessages.paused = true
            OpenPushInAppMessages.addTrigger("tier", "vip")
        }
        worker.start()
        worker.join(5_000)
        assertFalse("the worker thread never finished", worker.isAlive)

        // Nothing has run on the looper yet, so the engine has not moved.
        assertEquals(0, presenter.hides)
        assertEquals(generationBefore, OpenPushInAppMessages.engine.triggerGeneration)
        assertEquals("iam_1", OpenPushInAppMessages.engine.current?.id)

        idle()

        assertTrue(OpenPushInAppMessages.paused)
        assertEquals(1, presenter.hides)
        assertNull(OpenPushInAppMessages.engine.current)
        // Paused, so the message is owed the head of the queue rather than shown again.
        assertEquals(listOf("iam_1"), OpenPushInAppMessages.engine.queue.map { it.id })
        assertEquals(1, presenter.shows.size)
        assertTrue(OpenPushInAppMessages.engine.triggerGeneration > generationBefore)
    }

    private fun recordPhases(): List<String> {
        val phases = mutableListOf<String>()
        OpenPushInAppMessages.addLifecycleListener { phases += it.phase }
        return phases
    }

    private fun shownIds() = presenter.shows.map { it.message.id }

    private fun typeOf(request: Recorded): String =
        JSONObject(request.body!!).getJSONArray("events").getJSONObject(0).getString("type")

    private fun oneMessage(id: String, displayDurationSeconds: Int): String =
        """{"in_app_messages":[{"id":"$id","html_path":"/v1/apps/demo/iams/$id/html",""" +
            """"layout":"center","triggers":[],"display_duration":$displayDurationSeconds}]}"""

    private fun twoMessages(): String = """{"in_app_messages":[""" +
        """{"id":"a","html_path":"/v1/apps/demo/iams/a/html","layout":"center","triggers":[]},""" +
        """{"id":"b","html_path":"/v1/apps/demo/iams/b/html","layout":"center","triggers":[]}]}"""
}
