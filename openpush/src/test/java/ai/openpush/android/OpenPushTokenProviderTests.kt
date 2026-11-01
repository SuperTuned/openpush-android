package ai.openpush.android

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushTokenProviderTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var gate = CountDownLatch(0)

    /** Answers with [answer] (a token, or `null` for unavailable) and counts every request. */
    private class FakeProvider(var answer: String?) : OpenPushTokenProvider {
        var requests = 0
        var pending: OpenPushTokenCallback? = null
        var holdAnswers = false

        override fun requestToken(context: Context, callback: OpenPushTokenCallback) {
            requests++
            if (holdAnswers) {
                pending = callback
                return
            }
            answer?.let(callback::onToken) ?: callback.onUnavailable("no provider")
        }
    }

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.tokenProvider = null
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        logs.clear()
        OpenPush.onLog = { logs += it }
        bodies.clear()
    }

    @After fun clearProvider() {
        OpenPush.tokenProvider = null
    }

    private fun initialize() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            bodies += it.body!!
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1"}""")
        }
    }

    private fun expect(n: Int) = CountDownLatch(n).also { gate = it }
    private fun CountDownLatch.awaitRequests() = assertTrue(await(2, TimeUnit.SECONDS))

    private fun registrations() = synchronized(bodies) { bodies.filter { JSONObject(it).has("platform") } }

    private fun tokensSent() = registrations().map { JSONObject(it).getString("token") }

    private fun awaitTokenSent(token: String) {
        val deadline = System.currentTimeMillis() + 2_000
        while (token !in tokensSent() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$token was never registered", token in tokensSent())
    }

    @Test fun attachRegistersTheTokenTheProviderFetched() {
        OpenPush.tokenProvider = FakeProvider("provider-token")
        val done = expect(1)

        initialize()

        done.awaitRequests()
        assertEquals("provider-token", JSONObject(registrations().single()).getString("token"))
    }

    @Test fun withoutAProviderAttachRegistersNothing() {
        initialize()

        Thread.sleep(200)
        assertTrue(bodies.isEmpty())
    }

    @Test fun aTokenAlreadyRegisteredIsNotSentAgain() {
        val provider = FakeProvider("provider-token")
        OpenPush.tokenProvider = provider
        val first = expect(1)
        initialize()
        first.awaitRequests()
        assertTrue(runBlocking { OpenPush.awaitRegistration() })

        initialize()

        Thread.sleep(200)
        assertEquals(1, registrations().size)
        assertEquals("a repeated attach asks again for a rotated token", 2, provider.requests)
    }

    @Test fun aRotatedTokenIsRegisteredOnTheNextAttach() {
        val provider = FakeProvider("old-token")
        OpenPush.tokenProvider = provider
        val first = expect(1)
        initialize()
        first.awaitRequests()
        assertTrue(runBlocking { OpenPush.awaitRegistration() })

        provider.answer = "new-token"
        initialize()

        awaitTokenSent("new-token")
    }

    @Test fun anUnavailableTokenIsAskedForAgainOnTheNextForeground() {
        val provider = FakeProvider(null)
        OpenPush.tokenProvider = provider
        initialize()
        assertTrue(logs.any { it.contains("provider token unavailable") })

        provider.answer = "late-token"
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            awaitTokenSent("late-token")
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun onlyOneRequestIsInFlightAtATime() {
        val provider = FakeProvider("provider-token").apply { holdAnswers = true }
        OpenPush.tokenProvider = provider
        initialize()
        OpenPush.onAppForegrounded()
        OpenPush.onAppForegrounded()
        assertEquals(1, provider.requests)

        val done = expect(1)
        provider.pending!!.onToken("provider-token")
        provider.pending!!.onToken("provider-token")

        done.awaitRequests()
        Thread.sleep(200)
        assertEquals(1, registrations().size)
    }

    @Test fun aThrowingProviderIsTreatedAsUnavailable() {
        OpenPush.tokenProvider = OpenPushTokenProvider { _, _ -> error("provider exploded") }

        initialize()

        assertTrue(OpenPush.isInitialized)
        assertTrue(logs.any { it.contains("provider exploded") })
    }
}
