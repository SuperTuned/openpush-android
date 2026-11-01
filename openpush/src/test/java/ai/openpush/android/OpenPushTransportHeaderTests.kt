package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What every request says about who is making it, and what the SDK reads back off a response.
 *
 * The SDK header is the only thing a server operator has to tell an out-of-date device from a current
 * one, or a wrapper SDK's traffic from a native integration's, so it is asserted on every endpoint
 * this module can reach rather than on a representative one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(OpenPushInternalApi::class)
class OpenPushTransportHeaderTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())

    @Volatile private var gate = CountDownLatch(0)

    /** The app-session ping fires at most once every 30 minutes, so a test that wants two of them has
     *  to let half an hour pass between them. */
    @Volatile private var fakeNow = 1_700_000_000_000L
    private var sweep = 0

    @Before fun reset() {
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.nowMs = { fakeNow }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk-public", credential = { "opdev" }) {
            requests += it
            gate.countDown()
            OpenPush.Response(200, """{"id":"sub_1","status":1}""")
        }
        OpenPush.registerBlocking("provider-token")
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    /** `requests` is appended from the SDK's IO threads; iterate a copy taken under its own monitor. */
    private fun snapshot(): List<OpenPush.Request> = synchronized(requests) { requests.toList() }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.yield()
        assertTrue(condition())
    }

    /** Drives one request per endpoint this module owns and returns them keyed by a readable name. */
    private fun everyEndpoint(): Map<String, OpenPush.Request> {
        requests.clear()
        fakeNow += TimeUnit.MINUTES.toMillis(31)
        OpenPush.registerBlocking("provider-token")
        // A fresh message id each sweep: a receipt is sent once per message and then deduped.
        OpenPush.receiptBlocking("received", "m${++sweep}")
        OpenPush.trackEvent("checkout_started")
        OpenPush.flushCustomEventsBlocking()
        gate = CountDownLatch(1)
        OpenPush.onAppForegrounded() // the session ping is the one call here that goes out async
        awaitCondition { snapshot().any { it.url.endsWith("/sessions") } }
        runBlocking { OpenPushBridge.request("GET", "/v1/apps/demo/subscriptions/sub_1/iams") }

        val byName = buildMap {
            snapshot().forEach { request ->
                val name = when {
                    request.url.endsWith("/subscriptions") -> "registration"
                    request.url.endsWith("/v1/ingest") -> "ingest"
                    request.url.endsWith("/events") -> "events"
                    request.url.endsWith("/sessions") -> "sessions"
                    request.url.endsWith("/iams") -> "in-app fetch"
                    else -> "other (${request.url})"
                }
                put(name, request)
            }
        }
        assertEquals(
            setOf("registration", "ingest", "events", "sessions", "in-app fetch"),
            byName.keys,
        )
        return byName
    }

    @Test fun everyRequestCarriesTheSdkHeader() {
        val expected = "android/${OpenPush.sdkVersion}"
        everyEndpoint().forEach { (name, request) ->
            assertEquals("$name is missing the SDK header", expected, request.headers[OpenPush.X_OP_SDK])
        }
        // The WebView loads the in-app document itself, with headers it asks for by hand.
        assertEquals(expected, OpenPushBridge.requestHeaders()[OpenPush.X_OP_SDK])
    }

    @Test fun theWrapperHeaderIsSentOnlyWhenSet() {
        everyEndpoint().forEach { (name, request) ->
            assertFalse(
                "$name announced a wrapper nobody set",
                OpenPush.X_OP_SDK_WRAPPER in request.headers,
            )
        }
        assertFalse(OpenPush.X_OP_SDK_WRAPPER in OpenPushBridge.requestHeaders())

        OpenPush.sdkWrapper = "unity/1.1.4"
        everyEndpoint().forEach { (name, request) ->
            assertEquals("$name lost the wrapper header", "unity/1.1.4", request.headers[OpenPush.X_OP_SDK_WRAPPER])
            // The wrapper names the surface the host wrote against; it never replaces the native one.
            assertEquals("android/${OpenPush.sdkVersion}", request.headers[OpenPush.X_OP_SDK])
        }
        assertEquals("unity/1.1.4", OpenPushBridge.requestHeaders()[OpenPush.X_OP_SDK_WRAPPER])

        // A wrapper that clears itself is back to a plain native integration.
        OpenPush.sdkWrapper = "   "
        assertFalse(OpenPush.X_OP_SDK_WRAPPER in OpenPushBridge.requestHeaders())
    }

    /**
     * Answers one request with a fixed, hand-written HTTP response and closes. A real socket, because
     * the lower-casing has to hold for what an HTTP stack hands back, not for a map a test wrote.
     */
    private fun serveOnce(response: String): ServerSocket {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) { // drain the request head, up to its blank line
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test fun responseHeadersAreLowerCased() {
        val payload = """{"detail":"too many"}"""
        val server = serveOnce(
            "HTTP/1.1 429 Too Many Requests\r\n" +
                "Retry-After: 5\r\n" +
                "X-OP-Trace: abc\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                payload,
        )
        try {
            val url = "http://127.0.0.1:${server.localPort}/slow-down"
            val response = runBlocking {
                OpenPush.defaultTransport(OpenPush.Request("GET", url, emptyMap(), null))
            }
            assertEquals(429, response.status)
            assertEquals(payload, response.body)
            assertTrue(
                "header names arrived as ${response.headers.keys}",
                response.headers.keys.all { it == it.lowercase() },
            )
            assertEquals("5", response.headers["retry-after"])
            assertEquals("abc", response.headers["x-op-trace"])
            // The status line rides under a null key in headerFields and is not a header.
            assertFalse(response.headers.keys.any { it.isEmpty() })
            assertEquals(5_000L, OpenPush.retryAfterMs(response))
        } finally {
            runCatching { server.close() }
        }
    }

    @Test fun cancellingTheDefaultTransportDisconnectsItsHttpConnection() = runBlocking {
        val responseStarted = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val fake = object : HttpURLConnection(URL("https://example.test/blocked")) {
            override fun connect() = Unit
            override fun usingProxy(): Boolean = false
            override fun disconnect() {
                disconnected.countDown()
                releaseResponse.countDown()
            }

            override fun getResponseCode(): Int {
                responseStarted.countDown()
                releaseResponse.await()
                return 200
            }

            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
            override fun getHeaderFields(): Map<String?, List<String>> = emptyMap()
        }
        OpenPush.httpConnectionFactory = { fake }

        val request = launch(Dispatchers.Default) {
            OpenPush.defaultTransport(
                OpenPush.Request("GET", "https://example.test/blocked", emptyMap(), null),
            )
        }
        assertTrue(responseStarted.await(2, TimeUnit.SECONDS))

        request.cancel()

        assertTrue("cancellation did not disconnect HttpURLConnection", disconnected.await(2, TimeUnit.SECONDS))
        withTimeout(2_000L) { request.join() }
        assertTrue(request.isCancelled)
    }

    @Test fun retryAfterIsReadAsDeltaSeconds() {
        assertEquals(5_000L, OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("retry-after" to "5"))))
        assertEquals(0L, OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("retry-after" to "0"))))
        assertEquals(120_000L, OpenPush.retryAfterMs(OpenPush.Response(503, "", mapOf("retry-after" to " 120 "))))
        // A host transport under no obligation to lower-case is still understood.
        assertEquals(5_000L, OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("Retry-After" to "5"))))
    }

    @Test fun retryAfterIsReadAsAnHttpDate() {
        OpenPush.nowMs = { 1_792_567_680_000L } // Wed, 21 Oct 2026 07:28:00 GMT
        val inTwoMinutes = OpenPush.Response(503, "", mapOf("retry-after" to "Wed, 21 Oct 2026 07:30:00 GMT"))
        assertEquals(120_000L, OpenPush.retryAfterMs(inTwoMinutes))

        // A date that has already gone by means "now", not a negative delay that would undo the floor.
        val inThePast = OpenPush.Response(503, "", mapOf("retry-after" to "Wed, 21 Oct 2026 07:20:00 GMT"))
        assertEquals(0L, OpenPush.retryAfterMs(inThePast))
    }

    @Test fun anAbsentOrUnreadableRetryAfterIsNull() {
        assertNull(OpenPush.retryAfterMs(OpenPush.Response(429, "")))
        assertNull(OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("retry-after" to ""))))
        assertNull(OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("retry-after" to "soon"))))
        assertNull(OpenPush.retryAfterMs(OpenPush.Response(429, "", mapOf("retry-after" to "-5"))))
    }

    /** A host transport that builds two-argument responses keeps compiling and keeps working: the
     *  headers parameter has a default, so the two-argument call is still the same call. */
    @Test fun theTwoArgResponseConstructorStillCompiles() {
        val legacy = OpenPush.Response(200, "{}")
        assertTrue(legacy.headers.isEmpty())
        assertNull(OpenPush.retryAfterMs(legacy))

        val transport: suspend (OpenPush.Request) -> OpenPush.Response = { OpenPush.Response(202, "{}") }
        assertEquals(202, runBlocking { transport(OpenPush.Request("POST", "http://x/y", emptyMap(), null)) }.status)
    }
}
