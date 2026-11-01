package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushInternalApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * When the engine is allowed to ask the server for its messages.
 *
 * Three rules, and each exists because the naive version is wrong: the throttle keeps a chatty host
 * from fetching on every trigger call, but it must not silence the one fetch that opens a session;
 * a fetch while the app is in the background spends a request on a list nothing can display; and a
 * server that answers 503 deserves exactly one more chance, not a loop.
 */
@OptIn(OpenPushInternalApi::class)
internal class IamFetchTests : IamTestCase() {
    /**
     * The engine's clock. The harness's throttle stays on, so a subscription-ready fetch that turns
     * up late is dropped; a test lets one of its own unforced fetches through by moving this clock
     * past the interval first, which makes every GET count below exact.
     */
    private var clock = 0L

    @Before
    fun anchorClock() {
        clock = System.currentTimeMillis()
        OpenPushInAppMessages.nowMs = { clock }
    }

    @Test
    fun aFetchFailureIsLoggedAndRetriedOnce() {
        val logs = captureLogs()
        awaitAttachFetch()
        val before = iamGets().size
        iamsStatus = 503

        pastTheThrottle()
        OpenPushInAppMessages.fetch()
        await("the failing fetch") { iamGets().size >= before + 1 }
        assertEquals("one GET for the fetch", before + 1, iamGets().size)

        assertTrue("the failure is logged", logs.any { "in-app fetch failed (503)" in it })
        assertEquals("one retry is scheduled", 1, scheduler.pending)

        scheduler.advance(30_000)
        await("the one retry") { iamGets().size >= before + 2 }
        assertEquals("one GET for the retry", before + 2, iamGets().size)

        // The retry failed too, so the engine stops asking and waits for the next session.
        assertEquals("no second retry is scheduled", 0, scheduler.pending)
        scheduler.advance(30_000)
        idle()
        assertEquals("nothing more is fetched", before + 2, iamGets().size)
    }

    @Test
    fun aSessionStartFetchIgnoresTheThrottle() {
        awaitAttachFetch()
        OpenPushInAppMessages.fetchIntervalMs = 10 * 60_000L
        val before = iamGets().size

        // Well inside the interval, so an ordinary fetch is thrown away.
        OpenPushInAppMessages.fetch()
        idle()
        assertEquals("a fetch inside the interval is throttled", before, iamGets().size)

        // Away long enough to count as a new session: that fetch is forced through.
        OpenPush.onAppBackgrounded()
        clock += 40_000
        OpenPush.onAppForegrounded()

        await("the session-start fetch") { iamGets().size >= before + 1 }
        assertEquals("one GET for the new session", before + 1, iamGets().size)
    }

    @Test
    fun aFetchIsSkippedWhileBackgrounded() {
        val logs = captureLogs()
        awaitAttachFetch()
        val before = iamGets().size

        // Past the throttle, so being in the background is the only thing that can stop this fetch.
        pastTheThrottle()
        OpenPush.onAppBackgrounded()
        OpenPushInAppMessages.fetch()
        idle()

        assertEquals("no GET while backgrounded", before, iamGets().size)
        assertTrue("the skip is logged", logs.any { "in-app fetch skipped" in it })

        // Back at once, so this is not a new session and nothing is fetched on the return itself.
        OpenPush.onAppForegrounded()
        OpenPushInAppMessages.fetch()

        await("the fetch after the return") { iamGets().size >= before + 1 }
        assertEquals("one GET after the return", before + 1, iamGets().size)
    }

    @Test
    fun anEmptySuccessBodyIsAFailedFetchThatIsRetriedOnce() = aNonListSuccessIsRetriedOnce("")

    @Test
    fun anHtmlSuccessBodyIsAFailedFetchThatIsRetriedOnce() =
        aNonListSuccessIsRetriedOnce("<html><body>Gateway login</body></html>")

    /**
     * A 2xx whose body is not a message list used to throw out of the fetch coroutine and end the
     * host's process. It is now a refused fetch: logged, retried once, and the definitions the last
     * good fetch loaded stay where they are.
     */
    private fun aNonListSuccessIsRetriedOnce(body: String) {
        val logs = captureLogs()
        fetchFixture(oneMessage("kept"))
        val before = iamGets().size
        iamsFixture = body

        pastTheThrottle()
        OpenPushInAppMessages.fetch()
        await("the unreadable fetch") { iamGets().size >= before + 1 }
        idle()

        assertTrue("the failure is logged: $logs", logs.any { "in-app fetch failed (200, not a message list)" in it })
        assertEquals("one retry is scheduled", 1, scheduler.pending)
        assertEquals(
            "the last good list is kept",
            listOf("kept"),
            OpenPushInAppMessages.engine.definitions.map {
                it.id
            },
        )

        iamsFixture = oneMessage("after")
        scheduler.advance(30_000)
        await("the retry to load the list") {
            OpenPushInAppMessages.engine.definitions.map { it.id } == listOf("after")
        }
        assertEquals("one GET for the retry", before + 2, iamGets().size)
        assertEquals("no second retry is scheduled", 0, scheduler.pending)
    }

    @Test
    fun aRowThatIsNotAnObjectIsSkippedAndAMalformedConditionDropsItsMessage() {
        val fixture = """{"in_app_messages":[
            "not a row",
            {"id":"bad","html_path":"/b","triggers":[[{"kind":"custom","property":"a","operator":"==","value":1},7]]},
            {"id":"good","html_path":"/g","layout":"center","triggers":[]}
        ]}"""
        fetchFixture(fixture)

        assertEquals(listOf("good"), OpenPushInAppMessages.engine.definitions.map { it.id })
    }

    @Test
    fun theShippingScopeLogsAFailureInsteadOfCrashing() {
        val logs = captureLogs()
        val scope = IamEngine.defaultScope()

        scope.launch { throw IllegalStateException("boom") }.joinBlocking()

        assertTrue(
            "the failure is logged: $logs",
            logs.any {
                "in-app messages: unexpected IllegalStateException — boom" in
                    it
            },
        )
        // The supervisor keeps the scope usable for the next job.
        var ran = false
        scope.launch { ran = true }.joinBlocking()
        assertTrue(ran)
    }

    private fun kotlinx.coroutines.Job.joinBlocking() = kotlinx.coroutines.runBlocking { this@joinBlocking.join() }

    private fun pastTheThrottle() {
        clock += OpenPushInAppMessages.fetchIntervalMs
    }

    private fun iamGets() = recordedRequests().filter { it.url.endsWith("/iams") }

    private fun awaitAttachFetch() = await("the attach-time fetch") { iamGets().isNotEmpty() }

    /** Log lines, on a list the transport's own thread may append to while a test reads it. */
    private fun captureLogs(): List<String> {
        val logs = CopyOnWriteArrayList<String>()
        OpenPush.onLog = { logs += it }
        return logs
    }
}
