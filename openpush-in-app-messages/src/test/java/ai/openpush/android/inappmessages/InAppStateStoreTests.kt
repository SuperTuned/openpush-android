package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(OpenPushInternalApi::class)
internal class InAppStateStoreTests : IamTestCase() {

    @Test
    fun stateLoadsAtAttachNotAtProcessStart() {
        val key = "t-${UUID.randomUUID()}"
        seed(key, """{"dismissed":["iam_9"]}""")

        assertFalse("iam_9" in OpenPushInAppMessages.state.dismissed)

        attach(key)

        assertTrue("iam_9" in OpenPushInAppMessages.state.dismissed)
    }

    @Test
    fun stateIsKeyedByTokenKey() {
        val mine = "t-${UUID.randomUUID()}"
        val theirs = "t-${UUID.randomUUID()}"
        seed(mine, """{"dismissed":["iam_9"]}""")

        attach(mine)
        assertTrue("iam_9" in OpenPushInAppMessages.state.dismissed)

        attach(theirs)
        assertFalse("iam_9" in OpenPushInAppMessages.state.dismissed)
    }

    @Test
    fun purgeDropsEntriesOlderThan180Days() {
        val now = 400L * 24 * 60 * 60 * 1000
        val prefs = context.getSharedPreferences(InAppStateStore.PREFS, 0)
        val store = InAppStateStore(prefs, "state.purge") { now }
        store.withLock {
            redisplay["stale"] = InAppStateStore.Redisplay(now - 181L * 24 * 60 * 60 * 1000, 1)
            redisplay["fresh"] = InAppStateStore.Redisplay(now - 10L * 24 * 60 * 60 * 1000, 1)
            dismissed += listOf("stale", "fresh")
            impressions += listOf("stale", "fresh")
            clicked += listOf("stale#c1", "fresh#c1")
            pageViews += listOf("stale|p1", "fresh|p1")
            displayedGeneration["stale"] = 1L
            displayedGeneration["fresh"] = 1L
        }

        store.purge()

        assertEquals(setOf("fresh"), store.redisplay.keys)
        assertEquals(setOf("fresh"), store.dismissed)
        assertEquals(setOf("fresh"), store.impressions)
        assertEquals(setOf("fresh#c1"), store.clicked)
        assertEquals(setOf("fresh|p1"), store.pageViews)
        assertEquals(setOf("fresh"), store.displayedGeneration.keys)
    }

    @Test
    fun saveAndLoadRoundTripEveryField() {
        val prefs = context.getSharedPreferences(InAppStateStore.PREFS, 0)
        val written = InAppStateStore(prefs, "state.round") { 1_000L }
        written.withLock {
            dismissed += "iam_1"
            clicked += "iam_1#c1"
            impressions += "iam_1"
            pageViews += "iam_1|p1"
            redisplay["iam_1"] = InAppStateStore.Redisplay(900L, 2)
            displayedGeneration["iam_1"] = 7L
            lastDismissed = 950L
        }
        written.save()

        val read = InAppStateStore(prefs, "state.round") { 1_000L }
        read.load()

        assertEquals(setOf("iam_1"), read.dismissed)
        assertEquals(setOf("iam_1#c1"), read.clicked)
        assertEquals(setOf("iam_1"), read.impressions)
        assertEquals(setOf("iam_1|p1"), read.pageViews)
        assertEquals(900L, read.redisplay["iam_1"]?.at)
        assertEquals(2, read.redisplay["iam_1"]?.quantity)
        assertEquals(7L, read.displayedGeneration["iam_1"])
        assertEquals(950L, read.lastDismissed)
    }

    private fun seed(key: String, json: String) {
        context.getSharedPreferences(InAppStateStore.PREFS, 0).edit()
            .putString("state.$key", JSONObject(json).toString())
            .commit()
    }
}
