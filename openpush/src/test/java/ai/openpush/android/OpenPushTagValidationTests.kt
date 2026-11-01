package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `getTags()` reports the tags the user has, not the deletions still riding the wire, and a tag or
 * alias the server would refuse is dropped here — loudly — instead of being sent (spec U7, U8).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushTagValidationTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val clock = TestScheduler()

    @Volatile private var gate = CountDownLatch(0)

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.clearPersistedStateForTests()
        OpenPush.scheduler = clock
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        bodies.clear()
        logs.clear()
        OpenPush.onLog = { logs += it }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))

    private fun lastTags() = JSONObject(bodies.last()).getJSONObject("tags")
    private fun lastAliases() = JSONObject(bodies.last()).getJSONObject("aliases")
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    // getTags()

    @Test fun getTagsHidesAStagedDeletion() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro", "seats" to "3"))
        bodies.clear()
        // Staged, not yet sent: the coalescing window is open, so the tombstone is still on the map
        // and this is exactly the state an accessor must not report as a tag the user holds.
        OpenPush.User.removeTag("plan")
        assertEquals(mapOf("seats" to "3"), OpenPush.User.getTags())
        val done = expect(1)
        clock.advance(OpenPush.COALESCE_WINDOW_MS)
        done.awaitRequests()
        // The body still carries the deletion — hiding it locally must not stop it reaching the server.
        val sent = lastTags()
        assertEquals("", sent.getString("plan"))
        assertEquals("3", sent.getString("seats"))
        assertEquals(mapOf("seats" to "3"), OpenPush.User.getTags())
    }

    @Test fun getTagsStaysACopy() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        val snapshot = OpenPush.User.getTags()
        OpenPush.applyTagsBlocking(mapOf("seats" to "3"))
        assertEquals(mapOf("plan" to "pro"), snapshot)
        assertEquals(mapOf("plan" to "pro", "seats" to "3"), OpenPush.User.getTags())
    }

    // Tag rules

    @Test fun anInvalidTagKeyIsDroppedWithALog() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        logs.clear()
        val longKey = "k".repeat(OpenPush.TAG_KEY_LIMIT + 1)
        val longValue = "v".repeat(OpenPush.TAG_VALUE_LIMIT + 1)
        OpenPush.applyTagsBlocking(
            linkedMapOf(
                "" to "anonymous",
                "has space" to "x",
                "has\nnewline" to "x",
                longKey to "x",
                "verbose" to longValue,
                "plan" to "pro",
                // The limits themselves are inclusive, so the boundary values ride along.
                "k".repeat(OpenPush.TAG_KEY_LIMIT) to "v".repeat(OpenPush.TAG_VALUE_LIMIT),
            ),
        )
        assertEquals(
            setOf("plan", "k".repeat(OpenPush.TAG_KEY_LIMIT)),
            lastTags().keySet(),
        )
        assertEquals(setOf("plan", "k".repeat(OpenPush.TAG_KEY_LIMIT)), OpenPush.User.getTags().keys)
        assertTrue(logs.contains("tag '' dropped — a tag key cannot be empty"))
        assertTrue(logs.contains("tag 'has space' dropped — a tag key cannot contain whitespace"))
        assertTrue(logs.contains("tag 'has\nnewline' dropped — a tag key cannot contain whitespace"))
        assertTrue(logs.contains("tag '$longKey' dropped — a tag key is limited to 60 characters"))
        assertTrue(logs.contains("tag 'verbose' dropped — a tag value is limited to 300 characters"))
    }

    @Test fun anUpdateWithNothingValidSendsNothingAndOpensNoWindow() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        OpenPush.User.addTags(mapOf("has space" to "x", "" to "y"))
        assertTrue(clock.isIdle())
        clock.advance(2 * OpenPush.COALESCE_WINDOW_MS)
        assertTrue(bodies.isEmpty())
        assertTrue(OpenPush.User.getTags().isEmpty())
    }

    @Test fun anEmptyTagValueIsADeletionNotAnInvalidValue() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyTagsBlocking(mapOf("plan" to "pro"))
        bodies.clear()
        logs.clear()
        OpenPush.applyTagsBlocking(mapOf("plan" to ""))
        assertEquals("", lastTags().getString("plan"))
        assertTrue(logs.none { it.startsWith("tag 'plan' dropped") })
    }

    @Test fun removeTagBypassesTheKeyRules() {
        // A key an older build stored before these rules existed must still be removable.
        val longKey = "k".repeat(OpenPush.TAG_KEY_LIMIT + 1)
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        logs.clear()
        OpenPush.removeTagsBlocking(listOf(longKey, "has space"))
        val sent = lastTags()
        assertEquals("", sent.getString(longKey))
        assertEquals("", sent.getString("has space"))
        assertTrue(logs.none { it.contains("dropped") })
        // …and the accessor still hides both, because neither is a tag the user has.
        assertTrue(OpenPush.User.getTags().isEmpty())
    }

    @Test fun removeTagStillRefusesAnEmptyKey() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        OpenPush.User.removeTag("")
        assertTrue(clock.isIdle())
        assertTrue(logs.contains("tag '' dropped — a tag key cannot be empty"))
    }

    // Alias rules

    @Test fun anEmptyAliasIdThroughAddAliasesIsRefused() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        logs.clear()
        OpenPush.applyAliasesBlocking(linkedMapOf("crm_id" to "", "loyalty_id" to "l-7"))
        assertEquals(setOf("loyalty_id"), lastAliases().keySet())
        assertTrue(
            logs.contains("alias 'crm_id' dropped — an alias id cannot be empty; use removeAlias"),
        )
        // The deleting path is the one that may send "".
        bodies.clear()
        OpenPush.removeAliasesBlocking(listOf("loyalty_id"))
        assertEquals("", lastAliases().getString("loyalty_id"))
    }

    @Test fun aReservedAliasLabelIsRefusedLocally() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.RESERVED_ALIAS_LABELS.forEach { label ->
            bodies.clear()
            logs.clear()
            OpenPush.applyAliasesBlocking(mapOf(label to "nope"))
            assertTrue(bodies.isEmpty())
            assertTrue(logs.contains("alias '$label' dropped — the server reserves this label"))
            // A removal of a reserved label is refused too: the server never stored one.
            OpenPush.removeAliasesBlocking(listOf(label))
            assertTrue(bodies.isEmpty())
        }
        // A reserved label alongside a good one drops only itself.
        bodies.clear()
        val reserved = OpenPush.RESERVED_ALIAS_LABELS.first()
        OpenPush.applyAliasesBlocking(linkedMapOf(reserved to "nope", "crm_id" to "c-42"))
        assertEquals(setOf("crm_id"), lastAliases().keySet())
    }

    @Test fun anEmptyAliasLabelIsDroppedWithALog() {
        OpenPush.registerBlocking("provider-token")
        bodies.clear()
        logs.clear()
        OpenPush.applyAliasesBlocking(mapOf("" to "c-42"))
        assertTrue(bodies.isEmpty())
        assertTrue(logs.contains("alias '' dropped — an alias label cannot be empty"))
    }

    @Test fun moreThanTenLiveAliasesWarnsOnce() {
        OpenPush.registerBlocking("provider-token")
        OpenPush.applyAliasesBlocking((1..OpenPush.ALIAS_LIMIT).associate { "label_$it" to "id-$it" })
        assertTrue(logs.none { it.contains("alias labels are held") })
        logs.clear()
        OpenPush.applyAliasesBlocking(mapOf("label_11" to "id-11"))
        assertEquals(
            listOf("⚠ 11 alias labels are held — the server keeps at most 10 per user"),
            logs.filter { it.contains("alias labels are held") },
        )
        // Once per process: the twelfth is just as over the cap and says nothing.
        logs.clear()
        OpenPush.applyAliasesBlocking(mapOf("label_12" to "id-12"))
        assertTrue(logs.none { it.contains("alias labels are held") })
        // Every label still went out — the cap is the server's to enforce, not ours.
        assertEquals(12, lastAliases().keySet().size)
    }

    @Test fun aTombstonedAliasDoesNotCountTowardsTheCap() {
        OpenPush.registerBlocking("provider-token")
        // Ten live labels, then a removal and a replacement: still ten live, so no warning.
        OpenPush.applyAliasesBlocking((1..OpenPush.ALIAS_LIMIT).associate { "label_$it" to "id-$it" })
        OpenPush.User.removeAlias("label_1")
        logs.clear()
        OpenPush.applyAliasesBlocking(mapOf("label_11" to "id-11"))
        assertTrue(logs.none { it.contains("alias labels are held") })
        assertFalse(bodies.isEmpty())
    }
}
