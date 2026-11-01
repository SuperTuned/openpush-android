package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine keeps two collections: `definitions`, which only a fetch replaces, and `queue`, which
 * `evaluate()` re-derives from them. A message that is not eligible today is never thrown away, and
 * a message that has already been displayed waits for the trigger generation to move before it is
 * shown again.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamEngineQueueTests : IamTestCase() {

    @Test
    fun anIneligibleMessageStaysInDefinitionsAndPresentsWhenItsTriggerArrives() {
        fetchAndAwaitShow(fixture(message("a", triggers = levelGreaterThan(10)), message("b")))

        assertEquals(listOf("a", "b"), definitionIds())
        assertEquals("b", presenter.shows.single().message.id)

        OpenPushInAppMessages.addTrigger("level", 5)

        assertEquals(1, presenter.shows.size)
        assertEquals(listOf("a", "b"), definitionIds())

        presenter.emit(PresenterEvent.Dismissed)
        assertEquals(1, presenter.shows.size)

        OpenPushInAppMessages.addTrigger("level", 12)

        assertEquals(listOf("b", "a"), presenter.shows.map { it.message.id })
        assertEquals(listOf("a", "b"), definitionIds())
    }

    @Test
    fun aFetchReplacesTheDefinitions() {
        fetchAndAwaitShow(fixture(message("a", triggers = levelGreaterThan(10)), message("b")))
        assertEquals(listOf("a", "b"), definitionIds())

        fetchAndAwaitDefinitions(fixture(message("c"), message("c")), listOf("c"))

        assertFalse("a" in definitionIds())
        assertFalse("b" in definitionIds())
        assertEquals(1, definitionIds().count { it == "c" })
    }

    @Test
    fun aMessagePastItsEndTimeIsNotQueued() {
        val seconds = System.currentTimeMillis() / 1000
        fetchAndAwaitShow(
            fixture(
                message("expired", endTime = "${seconds - 60}"),
                message("expired_iso", endTime = "\"2020-01-01T00:00:00Z\""),
                message("live", endTime = "${seconds + 3600}"),
            ),
        )

        assertEquals("live", presenter.shows.single().message.id)
        assertEquals(listOf("expired", "expired_iso", "live"), definitionIds())
        assertFalse("expired" in queueIds())
        assertFalse("expired_iso" in queueIds())
    }

    @Test
    fun anEveryTriggerMessageDoesNotLoopAfterItsOwnDismissal() {
        fetchAndAwaitShow(fixture(message("a", redisplay = everyTrigger)))

        presenter.emit(PresenterEvent.Dismissed)

        assertEquals(1, presenter.shows.size)
        assertTrue(queueIds().isEmpty())

        OpenPushInAppMessages.addTrigger("anything", 1)

        assertEquals(2, presenter.shows.size)
    }

    @Test
    fun aNewSessionRearmsDisplay() {
        var clock = System.currentTimeMillis()
        OpenPushInAppMessages.nowMs = { clock }
        fetchAndAwaitShow(fixture(message("a", redisplay = everyTrigger)))

        presenter.emit(PresenterEvent.Dismissed)
        assertEquals(1, presenter.shows.size)

        val generation = OpenPushInAppMessages.engine.triggerGeneration
        OpenPush.onAppBackgrounded()
        clock += 31_000
        OpenPush.onAppForegrounded()

        await("the message to be re-presented") { presenter.shows.size == 2 }
        assertTrue(OpenPushInAppMessages.engine.triggerGeneration > generation)
    }

    private val everyTrigger = """{"limit":999999,"delay":0}"""

    private fun definitionIds() = OpenPushInAppMessages.engine.definitions.map { it.id }

    private fun queueIds() = OpenPushInAppMessages.engine.queue.map { it.id }

    private fun fetchAndAwaitShow(fixture: String) {
        val before = presenter.shows.size
        iamsFixture = fixture
        // Forced: the harness throttles every unforced fetch so a late one cannot interfere.
        OpenPushInAppMessages.engine.fetch(force = true)
        await("an in-app message on screen") { presenter.shows.size > before }
    }

    private fun fetchAndAwaitDefinitions(fixture: String, ids: List<String>) {
        iamsFixture = fixture
        // Forced: the harness throttles every unforced fetch so a late one cannot interfere.
        OpenPushInAppMessages.engine.fetch(force = true)
        await("definitions $ids") { definitionIds() == ids }
    }

    private fun fixture(vararg rows: String) = """{"in_app_messages":[${rows.joinToString(",")}]}"""

    private fun message(
        id: String,
        triggers: String = "[]",
        redisplay: String? = null,
        endTime: String? = null,
    ): String = buildString {
        append("""{"id":"$id","html_path":"/v1/apps/demo/iams/$id/html",""")
        append(""""layout":"center","triggers":$triggers""")
        if (redisplay != null) append(""","redisplay":$redisplay""")
        if (endTime != null) append(""","end_time":$endTime""")
        append("}")
    }

    private fun levelGreaterThan(value: Int) =
        """[[{"id":"c1","kind":"custom","property":"level","operator":"greater","value":$value}]]"""
}
