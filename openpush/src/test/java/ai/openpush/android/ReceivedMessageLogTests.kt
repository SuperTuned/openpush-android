package ai.openpush.android

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceivedMessageLogTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private var now = 1_000_000L

    @Before fun reset() {
        OpenPush.resetForTests()
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPushBridge.receivedMessageLog = null
    }

    @After fun restore() {
        OpenPushBridge.receivedMessageLog = null
    }

    private fun log(maxEntries: Int = PreferencesReceivedMessageLog.MAX_ENTRIES) =
        PreferencesReceivedMessageLog(context, clock = { now }, maxEntries = maxEntries)

    private fun push(id: String) = mapOf("op_message_id" to id, "title" to "hi")

    // ---- the durable record ---------------------------------------------------------------------

    @Test fun aRecordedIdIsKnownAndAnotherIsNot() {
        val log = log()
        assertFalse(log.contains("m1"))
        log.record("m1")
        assertTrue(log.contains("m1"))
        assertFalse(log.contains("m2"))
    }

    @Test fun theRecordSurvivesANewProcess() {
        log().record("m1")
        // A fresh instance has nothing in memory, as after the app was killed.
        assertTrue(log().contains("m1"))
    }

    @Test fun anIdExpiresSevenDaysAfterItWasRecorded() {
        log().record("m1")
        now += PreferencesReceivedMessageLog.RETENTION_MILLIS - 1
        assertTrue(log().contains("m1"))
        now += 1
        assertFalse(log().contains("m1"))
    }

    @Test fun expiredIdsArePrunedOnWrite() {
        log().record("old")
        now += PreferencesReceivedMessageLog.RETENTION_MILLIS
        log().record("new")
        val stored = context.getSharedPreferences(PreferencesReceivedMessageLog.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(PreferencesReceivedMessageLog.ENTRIES_KEY, null)!!
        assertFalse(stored.contains("old"))
        assertTrue(stored.contains("new"))
    }

    @Test fun theOldestIdIsEvictedPastTheCap() {
        val log = log(maxEntries = 3)
        listOf("a", "b", "c", "d").forEach {
            log.record(it)
            now += 1
        }
        val reloaded = log(maxEntries = 3)
        assertFalse("the oldest id was evicted", reloaded.contains("a"))
        assertTrue(listOf("b", "c", "d").all(reloaded::contains))
    }

    @Test fun anUnreadableRecordFailsOpen() {
        context.getSharedPreferences(PreferencesReceivedMessageLog.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PreferencesReceivedMessageLog.ENTRIES_KEY, 7).commit()

        val log = log()
        assertFalse(log.contains("m1"))
        assertTrue(logs.any { it.contains("unreadable") })
        log.record("m1")
        assertTrue("a write replaces the unreadable value", log().contains("m1"))
    }

    @Test fun invalidJsonFailsOpen() {
        context.getSharedPreferences(PreferencesReceivedMessageLog.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString(PreferencesReceivedMessageLog.ENTRIES_KEY, "{not json").commit()

        assertFalse(log().contains("m1"))
        assertTrue(logs.any { it.contains("invalid JSON") })
    }

    @Test fun aBrokenStoreFailsOpenButKeepsTheIdForThisProcess() {
        val broken = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, _, _ -> throw IllegalStateException("disk unavailable") } as SharedPreferences
        val log = PreferencesReceivedMessageLog(context, clock = { now }, preferences = { broken })

        assertFalse(log.contains("m1"))
        log.record("m1")
        assertTrue(log.contains("m1"))
        assertTrue(logs.any { it.contains("could not be saved") })
    }

    // ---- begin / finish -------------------------------------------------------------------------

    @Test fun aRepeatIsRefusedOnceTheFirstCopyFinished() {
        assertTrue(ReceivedMessages.begin(context, push("m1")))
        ReceivedMessages.finish(context, push("m1"), handled = true)

        assertFalse(ReceivedMessages.begin(context, push("m1")))
        assertTrue(logs.contains("duplicate push m1 ignored"))
        assertTrue(ReceivedMessages.begin(context, push("m2")))
    }

    @Test fun aCopyArrivingWhileTheFirstIsInFlightIsRefused() {
        assertTrue(ReceivedMessages.begin(context, push("m1")))
        assertFalse("nothing is recorded yet", PreferencesReceivedMessageLog.shared(context).contains("m1"))

        assertFalse(ReceivedMessages.begin(context, push("m1")))
        assertTrue(logs.any { it.contains("still being handled") })
    }

    @Test fun anUnfinishedHandlingRecordsNothingSoALaterCopyIsAdmitted() {
        assertTrue(ReceivedMessages.begin(context, push("m1")))
        ReceivedMessages.finish(context, push("m1"), handled = false)

        assertTrue(ReceivedMessages.begin(context, push("m1")))
    }

    @Test fun concurrentCopiesOfOneIdHaveExactlyOneWinner() {
        val threads = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val wins = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.execute {
                    start.await()
                    if (ReceivedMessages.begin(context, push("same"))) {
                        wins.incrementAndGet()
                        ReceivedMessages.finish(context, push("same"), handled = true)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, wins.get())
    }

    @Test fun foreignMessagesAndLiveUpdatesAreNeverTracked() {
        val calls = mutableListOf<String>()
        OpenPushBridge.receivedMessageLog = object : ReceivedMessageLog {
            override fun contains(messageId: String): Boolean = true.also { calls += "contains $messageId" }
            override fun record(messageId: String) {
                calls += "record $messageId"
            }
        }
        val foreign = mapOf("title" to "theirs")
        val live = mapOf("op_message_id" to "journey-node", "live_notification" to "{}")

        repeat(2) {
            assertTrue(ReceivedMessages.begin(context, foreign))
            ReceivedMessages.finish(context, foreign, handled = true)
            assertTrue(ReceivedMessages.begin(context, live))
            ReceivedMessages.finish(context, live, handled = true)
        }

        assertTrue(calls.isEmpty())
    }

    @Test fun aFailingLogFailsOpen() {
        OpenPushBridge.receivedMessageLog = object : ReceivedMessageLog {
            override fun contains(messageId: String): Boolean = throw IllegalStateException("disk unavailable")
            override fun record(messageId: String) = throw IllegalStateException("disk unavailable")
        }

        assertTrue(ReceivedMessages.begin(context, push("m1")))
        ReceivedMessages.finish(context, push("m1"), handled = true)
        assertTrue("the in-flight guard was still released", ReceivedMessages.begin(context, push("m1")))
        assertTrue(logs.any { it.startsWith("duplicate check failed for push m1") })
        assertTrue(logs.any { it.startsWith("push m1 could not be recorded") })
    }

    @Test fun withoutAContextOnlyTheInFlightGuardApplies() {
        assertTrue(ReceivedMessages.begin(null, push("m1")))
        assertFalse(ReceivedMessages.begin(null, push("m1")))
        ReceivedMessages.finish(null, push("m1"), handled = true)
        assertTrue(ReceivedMessages.begin(null, push("m1")))
    }

    // ---- the public adapter ---------------------------------------------------------------------

    @Test fun thePublicPairDedupesAfterInitialize() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, """{"id":"sub_1"}""") }

        assertTrue(OpenPush.Notifications.beginReceived(push("m1")))
        OpenPush.Notifications.finishReceived(push("m1"))
        assertFalse(OpenPush.Notifications.beginReceived(push("m1")))
        assertTrue("a foreign push is always admitted", OpenPush.Notifications.beginReceived(mapOf("title" to "x")))
    }
}
