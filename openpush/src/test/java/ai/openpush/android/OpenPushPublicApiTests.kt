package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One test per **shipped public** entry point, driving the real `fun` the host app calls and awaiting
 * its effect on the wire.
 *
 * The rest of the suite drives the `internal` `*Blocking` twins, which defeat the async dispatch and
 * keep those tests fast — but they also mean the public functions themselves were never executed: their
 * bodies could have been emptied without turning the suite red. These tests close that hole, so the
 * `*Blocking` tests can stay as the fast path for behaviour and these stay as the proof that the
 * published surface is wired to it. Assertions are on the request body or the returned effect, never on
 * a log line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPublicApiTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)
    private val clock = TestScheduler()

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onLog = {}
        bodies.clear()
        OpenPush.clearToken()
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
        OpenPush.clearToken()
        bodies.clear()
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    /** Arms the transport to count the next [requests] requests, and returns the latch to await. */
    private fun expect(requests: Int) = CountDownLatch(requests).also { gate = it }

    private fun CountDownLatch.awaitRequests() =
        assertTrue("timed out waiting for the request(s)", await(2, TimeUnit.SECONDS))

    /** Gets a provider token onto the device through the public [OpenPush.register]. */
    private fun registered() {
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        bodies.clear()
    }

    private fun lastBody() = JSONObject(bodies.last())
    private fun lastEvent() = lastBody().getJSONArray("events").getJSONObject(0)

    // OpenPush

    // The deprecated two-argument form keeps its wire shape for the apps and wrappers that call it.
    @Suppress("DEPRECATION")
    @Test
    fun registerPostsTheProviderToken() {
        val done = expect(1)
        OpenPush.register("provider-token", "external")
        done.awaitRequests()
        assertEquals("provider-token", lastBody().getString("token"))
        assertEquals("external", lastBody().getString("external_id"))
    }

    @Test fun registerPostsTheDeviceCountryCode() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.Builder().setLanguage("en").setRegion("IN").build())
            val done = expect(1)
            OpenPush.register("country-token")
            done.awaitRequests()
            assertEquals("IN", lastBody().getString("country"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun receiptPostsTheEventAndThenRunsTheContinuation() {
        registered()
        val ran = CountDownLatch(1)
        OpenPush.receipt("received", "m1") { ran.countDown() }
        assertTrue("the receipt continuation never ran", ran.awaitOnMain(2, TimeUnit.SECONDS))
        assertEquals("received", lastEvent().getString("type"))
        assertEquals("m1", lastEvent().getString("message_id"))
    }

    @Test fun loginPostsTheExternalIdAndReportsTheOutcome() {
        registered()
        val result = AtomicReference<IdentityResult>()
        val done = CountDownLatch(1)
        OpenPush.login("user-7", "hash-9") {
            result.set(it)
            done.countDown()
        }
        assertTrue("the login callback never ran", done.awaitOnMain(2, TimeUnit.SECONDS))
        assertEquals(IdentityResult.Success, result.get())
        assertEquals("user-7", lastBody().getString("external_id"))
        assertEquals("hash-9", lastBody().getString("external_id_auth_hash"))
    }

    @Test fun logoutPostsAnExplicitEmptyExternalIdAndReportsTheOutcome() {
        registered()
        OpenPush.loginBlocking("user-7")
        val result = AtomicReference<IdentityResult>()
        val done = CountDownLatch(1)
        OpenPush.logout {
            result.set(it)
            done.countDown()
        }
        assertTrue("the logout callback never ran", done.awaitOnMain(2, TimeUnit.SECONDS))
        assertEquals(IdentityResult.Success, result.get())
        assertTrue(lastBody().has("external_id"))
        assertEquals("", lastBody().getString("external_id"))
    }

    // OpenPush.User

    @Test fun addTagPostsTheSingleTag() {
        registered()
        val done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        clock.advance(300)
        done.awaitRequests()
        assertEquals("pro", lastBody().getJSONObject("tags").getString("plan"))
    }

    @Test fun addTagsPostsEveryTagInOneRequest() {
        registered()
        val done = expect(1)
        OpenPush.User.addTags(mapOf("plan" to "pro", "seats" to "3"))
        clock.advance(300)
        done.awaitRequests()
        assertEquals(1, bodies.size)
        val tags = lastBody().getJSONObject("tags")
        assertEquals("pro", tags.getString("plan"))
        assertEquals("3", tags.getString("seats"))
    }

    @Test fun removeTagPostsAnEmptyValueForTheKey() {
        registered()
        val done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        OpenPush.User.removeTag("plan")
        clock.advance(300)
        done.awaitRequests()
        assertEquals(1, bodies.size)
        assertEquals("", lastBody().getJSONObject("tags").getString("plan"))
    }

    @Test fun addAliasPostsTheSingleAlias() {
        registered()
        val done = expect(1)
        OpenPush.User.addAlias("crm_id", "c-42")
        clock.advance(300)
        done.awaitRequests()
        assertEquals("c-42", lastBody().getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun addAliasesPostsEveryAliasInOneRequest() {
        registered()
        val done = expect(1)
        OpenPush.User.addAliases(mapOf("crm_id" to "c-42", "loyalty_id" to "l-7"))
        clock.advance(300)
        done.awaitRequests()
        assertEquals(1, bodies.size)
        val aliases = lastBody().getJSONObject("aliases")
        assertEquals("c-42", aliases.getString("crm_id"))
        assertEquals("l-7", aliases.getString("loyalty_id"))
    }

    @Test fun removeAliasPostsAnEmptyValueForTheLabel() {
        registered()
        val done = expect(1)
        OpenPush.User.addAlias("crm_id", "c-42")
        OpenPush.User.removeAlias("crm_id")
        clock.advance(300)
        done.awaitRequests()
        assertEquals(1, bodies.size)
        assertEquals("", lastBody().getJSONObject("aliases").getString("crm_id"))
    }

    // OpenPush.PushSubscription

    @Test fun optOutPostsStatusMinusTwoAndTheAccessorAgrees() {
        registered()
        val done = expect(1)
        OpenPush.PushSubscription.optOut()
        done.awaitRequests()
        assertEquals(-2, lastBody().getInt("status"))
        assertFalse(OpenPush.PushSubscription.optedIn)
    }

    @Test fun optInPostsStatusOne() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        registered()
        OpenPush.optOutBlocking()
        val done = expect(1)
        OpenPush.PushSubscription.optIn()
        done.awaitRequests()
        assertEquals(1, lastBody().getInt("status"))
    }

    // OpenPush.Notifications

    @Test fun processForegroundMessageReachesListenersAndPostsAReceivedReceipt() {
        registered()
        // Foreground listeners and preventDefault() only apply while an Activity is visible.
        Robolectric.buildActivity(Activity::class.java).setup()
        val done = expect(1)
        val handle = OpenPush.Notifications.addForegroundLifecycleListener { it.preventDefault() }
        val prevented = try {
            OpenPush.Notifications.processForegroundMessage(mapOf("op_message_id" to "m1", "title" to "hi"))
        } finally {
            OpenPush.Notifications.removeForegroundLifecycleListener(handle)
        }
        assertTrue("preventDefault() was not honoured", prevented)
        done.awaitRequests()
        assertEquals("received", lastEvent().getString("type"))
        assertEquals("m1", lastEvent().getString("message_id"))
    }

    @Test fun processDisplayedPostsAConfirmedReceiptForAHostRenderer() {
        registered()
        val done = expect(1)

        OpenPush.Notifications.processDisplayed(mapOf("op_message_id" to "m-displayed"))

        done.awaitRequests()
        assertEquals("confirmed", lastEvent().getString("type"))
        assertEquals("m-displayed", lastEvent().getString("message_id"))
    }

    @Test fun processClickReachesListenersAndPostsAClickedReceipt() {
        registered()
        val done = expect(1)
        val seen = AtomicReference<ClickEvent>()
        val handle = OpenPush.Notifications.addClickListener { seen.set(it) }
        try {
            OpenPush.Notifications.processClick(mapOf("op_message_id" to "m1", "deep_link" to "app://x"))
            done.awaitRequests()
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
        assertEquals("m1", seen.get().messageId)
        assertEquals("app://x", seen.get().data["deep_link"])
        assertEquals("clicked", lastEvent().getString("type"))
    }
}
