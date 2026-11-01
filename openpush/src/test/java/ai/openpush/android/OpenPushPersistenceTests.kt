package ai.openpush.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushPersistenceTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)
    private val clock = TestScheduler()

    @Before fun reset() {
        OpenPush.scheduler = clock
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        OpenPush.onLog = {}
        bodies.clear()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
    }

    @After fun restoreClock() {
        OpenPush.scheduler = OpenPush.RealScheduler
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }

    /**
     * Waits for the request(s) **and then for the accept path that applies them**. The latch is
     * counted down inside the transport, which fires while the response is still being written
     * back, so awaiting it alone leaves a cold start racing the registration it followed.
     */
    private fun CountDownLatch.awaitRequests() {
        assertTrue("timed out waiting for the request(s)", await(2, TimeUnit.SECONDS))
        OpenPush.settleForTests()
    }

    private fun attach(appId: String = "demo") {
        OpenPush.initialize(context, appId = appId, sdkKey = "sdk", credential = { "opdev" }) {
            if (!it.url.endsWith("/sessions")) {
                bodies += it.body!!
                gate.countDown()
            }
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    private fun coldStart(appId: String = "demo") {
        OpenPush.resetForTests()
        attach(appId)
    }

    @Test fun aBurstOfTagWritesPersistsOnce() {
        val writes = AtomicInteger()
        val countingContext = CountingPreferencesContext(context, writes)
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk")
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS)
        writes.set(0)

        repeat(10) { OpenPush.User.addTag("key-$it", "value-$it") }

        assertEquals(0, writes.get())
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS - 1)
        assertEquals(0, writes.get())
        clock.advance(1)
        assertEquals(1, writes.get())
    }

    @Test fun registrationFlushesPendingSessionStateBeforeTakingItsBodySnapshot() {
        val writes = AtomicInteger()
        val countingContext = CountingPreferencesContext(context, writes)
        var registrationBody: String? = null
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk") { request ->
            registrationBody = request.body
            OpenPush.Response(200, "{}")
        }
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS)
        writes.set(0)

        OpenPush.User.addTag("plan", "pro")
        OpenPush.registerBlocking("provider-token")

        assertEquals("pro", JSONObject(registrationBody!!).getJSONObject("tags").getString("plan"))
        val storedTags = countingContext.getSharedPreferences("openpush", Context.MODE_PRIVATE)
            .getString("openpush.deviceToken.tags", null)
        assertEquals("pro", JSONObject(storedTags!!).getString("plan"))
    }

    @Test fun unchangedPermissionRefreshDoesNotRewriteTheSession() {
        val writes = AtomicInteger()
        val countingContext = CountingPreferencesContext(context, writes)
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk")
        writes.set(0)

        repeat(3) { OpenPush.onAppForegrounded() }
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS)
        assertEquals(0, writes.get())

        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.onAppForegrounded()
        assertEquals(0, writes.get())
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS)
        assertEquals(1, writes.get())
    }

    @Test fun backgroundingFlushesPendingSessionStateImmediately() {
        val writes = AtomicInteger()
        val countingContext = CountingPreferencesContext(context, writes)
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk")
        writes.set(0)
        OpenPush.User.addTag("plan", "pro")

        OpenPush.onAppBackgrounded()

        val storedTags = countingContext.getSharedPreferences("openpush", Context.MODE_PRIVATE)
            .getString("openpush.deviceToken.tags", null)
        assertEquals("pro", JSONObject(storedTags!!).getString("plan"))
        assertEquals(1, writes.get())
    }

    @Test fun resetCancelsAQueuedPersistenceCallback() {
        val writes = AtomicInteger()
        val countingContext = CountingPreferencesContext(context, writes)
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk")
        writes.set(0)
        OpenPush.User.addTag("stale", "value")

        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.initialize(countingContext, appId = "demo", sdkKey = "sdk")
        val writesAfterFreshAttach = writes.get()
        clock.advance(OpenPush.SESSION_PERSIST_WINDOW_MS)

        assertEquals(writesAfterFreshAttach, writes.get())
        val storedTags = countingContext.getSharedPreferences("openpush", Context.MODE_PRIVATE)
            .getString("openpush.deviceToken.tags", null)
        assertFalse(JSONObject(storedTags!!).has("stale"))
    }

    @Test fun optOutSurvivesAColdStart() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.PushSubscription.optOut()
        done.awaitRequests()

        coldStart()
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
        assertFalse(OpenPush.PushSubscription.optedIn)
    }

    @Test fun aLegacyOptOutMigratesWithoutResubscribingTheDevice() {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        prefs.edit().putInt("openpush.deviceToken.status", -2).commit()
        attach()
        val done = expect(1)

        OpenPush.register("provider-token")
        done.awaitRequests()

        assertEquals(-2, JSONObject(bodies.last()).getInt("status"))
        assertTrue(prefs.getBoolean("openpush.deviceToken.optedOut", false))
        assertFalse(prefs.contains("openpush.deviceToken.status"))
    }

    @Test fun aLegacyStatusIsMigratedOnceAndTheVersionIsStamped() {
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        prefs.edit().putInt("openpush.deviceToken.status", -2).commit()

        attach()

        assertEquals(2, prefs.getInt("openpush.schemaVersion", 0))
        assertTrue(prefs.getBoolean("openpush.deviceToken.optedOut", false))
        assertFalse(prefs.contains("openpush.deviceToken.status"))

        // A stale legacy field must not be interpreted after schema 2 has been stamped. Removing the
        // two authoritative fields makes the distinction visible: a repeated migration would send
        // -2, while a schema-2 restore keeps the default permission-derived status 0.
        OpenPush.resetForTests()
        prefs.edit()
            .remove("openpush.deviceToken.notificationTypes")
            .remove("openpush.deviceToken.optedOut")
            .putInt("openpush.deviceToken.status", -2)
            .commit()
        bodies.clear()
        attach()
        val done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        assertEquals(0, JSONObject(bodies.last()).getInt("status"))
        assertFalse(prefs.getBoolean("openpush.deviceToken.optedOut", false))
        assertFalse(prefs.contains("openpush.deviceToken.status"))
    }

    @Test fun tagsAndAliasesSurviveAColdStart() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.User.addTags(mapOf("plan" to "pro", "seats" to "3"))
        clock.advance(300)
        done.awaitRequests()
        done = expect(1)
        OpenPush.User.addAliases(mapOf("crm_id" to "c-42"))
        clock.advance(300)
        done.awaitRequests()

        coldStart()
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        val body = JSONObject(bodies.last())
        assertEquals("pro", body.getJSONObject("tags").getString("plan"))
        assertEquals("3", body.getJSONObject("tags").getString("seats"))
        assertEquals("c-42", body.getJSONObject("aliases").getString("crm_id"))
    }

    @Test fun externalIdentitySurvivesButItsAuthHashStaysMemoryOnly() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.login("user-7", "hash-9")
        done.awaitRequests()

        coldStart()
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        val body = JSONObject(bodies.last())
        // The hash did not survive, so the identity is not asserted at all (spec A5).
        assertFalse(body.has("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("openpush.deviceToken.authHash"))
    }

    @Test fun aLogoutSurvivesAColdStartAsAnExplicitDetach() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.login("user-7")
        done.awaitRequests()
        done = expect(1)
        OpenPush.logout()
        done.awaitRequests()

        coldStart()
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        val body = JSONObject(bodies.last())
        assertTrue(body.has("external_id"))
        assertEquals("", body.getString("external_id"))
    }

    @Test fun aWorkspaceSwitchWipesThePersistedSession() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        clock.advance(300)
        done.awaitRequests()
        done = expect(1)
        OpenPush.PushSubscription.optOut()
        done.awaitRequests()

        attach(appId = "other")
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        assertEquals(0, JSONObject(prefs.getString("openpush.deviceToken.tags", "{}")!!).length())
        assertFalse(prefs.contains("openpush.deviceToken.status"))
        assertFalse(prefs.getBoolean("openpush.deviceToken.optedOut", false))

        coldStart(appId = "other")
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        val body = JSONObject(bodies.last())
        assertFalse(body.has("tags"))
        assertEquals(0, body.getInt("status"))
    }

    @Test fun aValueStagedBeforeInitializeBeatsTheRestoredOne() {
        attach()
        var done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()
        done = expect(1)
        OpenPush.User.addTag("plan", "pro")
        clock.advance(300)
        done.awaitRequests()

        OpenPush.resetForTests()
        OpenPush.User.addTag("plan", "free")
        attach()
        bodies.clear()
        done = expect(1)
        OpenPush.register("provider-token")
        done.awaitRequests()

        assertEquals("free", JSONObject(bodies.last()).getJSONObject("tags").getString("plan"))
    }

    /**
     * The accept path's session guard and its write-back are one step. A registration whose
     * response is still in flight when the process resets must write nothing at all: not the
     * subscription id it was told, and above all not the status adoption, which would clear an
     * opt-out the new session has just recorded.
     */
    @Test fun aRegistrationHeldAcrossAResetCannotWriteOverTheNewSession() {
        val held = CountDownLatch(1)
        val reached = CountDownLatch(1)
        val holdNext = AtomicBoolean(true)
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) { request ->
            if (!request.url.endsWith("/sessions") && holdNext.compareAndSet(true, false)) {
                reached.countDown()
                held.await(5, TimeUnit.SECONDS)
                // Another subscription id and a positive status: everything this response would
                // adopt if the guard were a check-then-act.
                OpenPush.Response(200, """{"id":"sub_held","status":1}""")
            } else {
                OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
        val worker = Thread { OpenPush.registerBlocking("provider-token") }
        worker.start()
        assertTrue("the first registration never reached the transport", reached.await(5, TimeUnit.SECONDS))

        // A whole new session while that response is held.
        OpenPush.resetForTests()
        attach()
        OpenPush.registerBlocking("provider-token")
        OpenPush.optOutBlocking()

        held.countDown()
        worker.join(5_000)
        assertFalse("the held registration never finished", worker.isAlive)

        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        assertTrue(
            "the superseded registration cleared the new session's opt-out",
            prefs.getBoolean("openpush.deviceToken.optedOut", false),
        )
        assertFalse(OpenPush.PushSubscription.optedIn)
        assertEquals("sub_1", OpenPush.User.pushSubscription.id)
        assertEquals("sub_1", prefs.getString("openpush.deviceToken.subscriptionId", null))
    }

    /**
     * Same guard, the rejection branch. A superseded registration's `identity_rejected` speaks for
     * a session that no longer exists, so it neither clears the identity the new session holds nor
     * tells the host that identity was refused.
     */
    @Test fun aSupersededRejectionLeavesTheNewSessionsIdentityAlone() {
        val rejections = Collections.synchronizedList(mutableListOf<String>())
        OpenPush.onIdentityRejected = { rejections += it }
        val held = CountDownLatch(1)
        val reached = CountDownLatch(1)
        val holdNext = AtomicBoolean(true)
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) { request ->
            if (!request.url.endsWith("/sessions") && holdNext.compareAndSet(true, false)) {
                reached.countDown()
                held.await(5, TimeUnit.SECONDS)
                OpenPush.Response(200, """{"id":"sub_held","identity_rejected":"auth hash invalid"}""")
            } else {
                OpenPush.Response(200, """{"id":"sub_1"}""")
            }
        }
        val worker = Thread { OpenPush.registerBlocking("provider-token", "user-7") }
        worker.start()
        assertTrue("the first registration never reached the transport", reached.await(5, TimeUnit.SECONDS))

        OpenPush.resetForTests()
        attach()
        // The new session claims the very same identity, so the stale rejection would match it.
        OpenPush.loginBlocking("user-7")

        held.countDown()
        worker.join(5_000)
        assertFalse("the held registration never finished", worker.isAlive)

        assertEquals("user-7", OpenPush.User.externalId)
        val prefs = context.getSharedPreferences("openpush", Context.MODE_PRIVATE)
        assertEquals("user-7", prefs.getString("openpush.deviceToken.externalId", null))
        assertTrue("a superseded rejection reached the host", rejections.isEmpty())
    }
}

private class CountingPreferencesContext(base: Context, private val writes: AtomicInteger) : ContextWrapper(base) {
    private val openPushPreferences = CountingSharedPreferences(
        base.getSharedPreferences("openpush", Context.MODE_PRIVATE),
        writes,
    )

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        if (name == "openpush") openPushPreferences else super.getSharedPreferences(name, mode)
}

private class CountingSharedPreferences(private val delegate: SharedPreferences, private val writes: AtomicInteger) :
    SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = CountingEditor(delegate.edit(), writes)
}

private class CountingEditor(private val delegate: SharedPreferences.Editor, private val writes: AtomicInteger) :
    SharedPreferences.Editor {
    override fun putString(key: String?, value: String?) = apply { delegate.putString(key, value) }
    override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { delegate.putStringSet(key, values) }
    override fun putInt(key: String?, value: Int) = apply { delegate.putInt(key, value) }
    override fun putLong(key: String?, value: Long) = apply { delegate.putLong(key, value) }
    override fun putFloat(key: String?, value: Float) = apply { delegate.putFloat(key, value) }
    override fun putBoolean(key: String?, value: Boolean) = apply { delegate.putBoolean(key, value) }
    override fun remove(key: String?) = apply { delegate.remove(key) }
    override fun clear() = apply { delegate.clear() }
    override fun commit(): Boolean {
        writes.incrementAndGet()
        return delegate.commit()
    }
    override fun apply() {
        writes.incrementAndGet()
        delegate.apply()
    }
}
