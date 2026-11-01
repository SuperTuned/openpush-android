package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `session_time` and `min_time_since` are the two trigger kinds no host call can ever satisfy —
 * only the clock can. The engine therefore keeps exactly one wake-up outstanding, set to the
 * earliest instant at which one of them could become true, and re-evaluates when it arrives.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamDynamicTriggerTests : IamTestCase() {
    /**
     * The engine's clock. Anchored here rather than at construction because the superclass's
     * `@Before` — which runs first — takes the engine's session start from the real clock, and the
     * test clock must not be behind it or a deadline measured from the session start would land
     * after the instant this test advances to.
     */
    private var clock = 0L

    @Before
    fun useTestClock() {
        clock = System.currentTimeMillis()
        OpenPushInAppMessages.nowMs = { clock }
    }

    @Test
    fun aSessionTimeTriggerFiresWhenTheDeadlinePasses() {
        fetchAndAwaitDefinitions(fixture(message("a", sessionTime("greater", 30))), listOf("a"))

        assertEquals(emptyList<String>(), shownIds())
        assertEquals(1, scheduler.pending)

        advance(30_001)

        assertEquals(listOf("a"), shownIds())
    }

    @Test
    fun aMinTimeSinceTriggerReevaluatesAfterTheLastDismissal() {
        fetchAndAwaitDefinitions(
            fixture(message("a"), message("b", minTimeSince("greater", 30))),
            listOf("a", "b"),
        )

        // Never dismissed is already infinitely long ago, so `b` is eligible from the start and
        // nothing is waiting on the clock — it is only queued behind `a`.
        assertEquals(listOf("a"), shownIds())
        assertEquals(0, scheduler.pending)

        presenter.emit(PresenterEvent.Dismissed)
        idle()

        // The dismissal put `b` out of reach and armed the wake-up that will bring it back.
        assertEquals(listOf("a"), shownIds())
        assertEquals(1, scheduler.pending)

        advance(30_001)

        assertEquals(listOf("a", "b"), shownIds())
    }

    @Test
    fun onlyOneTimerIsOutstanding() {
        fetchAndAwaitDefinitions(
            fixture(
                message("a", sessionTime("greater", 30)),
                message("b", sessionTime("greater", 60)),
                message("c", sessionTime("greater", 90)),
            ),
            listOf("a", "b", "c"),
        )

        assertEquals(1, scheduler.pending)

        OpenPushInAppMessages.addTrigger("x", 1)
        OpenPushInAppMessages.addTrigger("y", 2)
        OpenPushInAppMessages.clearTriggers()

        assertEquals(1, scheduler.pending)

        advance(30_001)

        assertEquals(listOf("a"), shownIds())
        assertEquals(1, scheduler.pending)
    }

    @Test
    fun aDismissalOrTriggerChangeReschedules() {
        fetchAndAwaitDefinitions(
            fixture(
                message("late", sessionTime("greater", 90)),
                message("soon", sessionTime("greater", 30)),
            ),
            listOf("late", "soon"),
        )
        val engine = OpenPushInAppMessages.engine

        // One timer, set to the earlier of the two deadlines.
        assertEquals(1, scheduler.pending)
        assertTrue(engine.nextDynamicDeadlineMs(clock)!! - clock <= 30_000)

        // A trigger mutation replaces the outstanding timer rather than adding a second one.
        OpenPushInAppMessages.addTrigger("level", 1)
        assertEquals(1, scheduler.pending)

        advance(30_001)

        assertEquals(listOf("soon"), shownIds())
        // `soon`'s deadline is behind us now; what is outstanding is `late`'s.
        assertEquals(1, scheduler.pending)
        assertTrue(engine.nextDynamicDeadlineMs(clock)!! - clock in 59_000..60_000)

        // A dismissal reschedules too, and the replacement still fires.
        presenter.emit(PresenterEvent.Dismissed)
        idle()
        assertEquals(1, scheduler.pending)

        advance(60_000)

        assertEquals(listOf("soon", "late"), shownIds())
    }

    private fun advance(millis: Long) {
        clock += millis
        scheduler.advance(millis)
        idle()
    }

    private fun shownIds() = presenter.shows.map { it.message.id }

    private fun definitionIds() = OpenPushInAppMessages.engine.definitions.map { it.id }

    private fun fetchAndAwaitDefinitions(fixture: String, ids: List<String>) {
        iamsFixture = fixture
        // Forced: the harness throttles every unforced fetch so a late one cannot interfere.
        OpenPushInAppMessages.engine.fetch(force = true)
        await("definitions $ids") { definitionIds() == ids }
        idle()
    }

    private fun fixture(vararg rows: String) = """{"in_app_messages":[${rows.joinToString(",")}]}"""

    private fun message(id: String, triggers: String = "[]"): String =
        """{"id":"$id","html_path":"/v1/apps/demo/iams/$id/html",""" +
            """"layout":"center","triggers":$triggers}"""

    private fun sessionTime(op: String, seconds: Int) = clockTrigger("session_time", op, seconds)

    private fun minTimeSince(op: String, seconds: Int) = clockTrigger("min_time_since", op, seconds)

    private fun clockTrigger(kind: String, op: String, seconds: Int) =
        """[[{"id":"c1","kind":"$kind","operator":"$op","value":$seconds}]]"""
}
