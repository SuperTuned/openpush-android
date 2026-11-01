package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
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
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushEmailSubscriptionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = TestScheduler()
    private val requests = Collections.synchronizedList(mutableListOf<OpenPush.Request>())
    private val results = Collections.synchronizedList(mutableListOf<OpenPushEmailResult>())

    /** Answers for email writes, consumed in order; once empty, every write is subscribed. */
    private val answers = Collections.synchronizedList(mutableListOf<OpenPush.Response>())

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.jitter = { 0 }
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        requests.clear()
        answers.clear()
        results.clear()
    }

    @After fun restore() {
        OpenPush.scheduler = OpenPush.RealScheduler
        OpenPush.resetForTests()
    }

    private fun initialize(appId: String = "demo") {
        OpenPush.initialize(context, appId = appId, sdkKey = "sdk-public", credential = { "opdev" }) { request ->
            val body = request.body?.let(::JSONObject)
            if (body?.optString("platform") == "email") {
                requests += request
                synchronized(answers) { answers.removeFirstOrNull() }
                    ?: OpenPush.Response(
                        200,
                        """{"id":"sub_email","status":1,"status_name":"Subscribed","created":true}""",
                    )
            } else {
                OpenPush.Response(200, """{"id":"sub_push"}""")
            }
        }
        OpenPush.settleForTests()
    }

    private fun bodies() = requests.map { JSONObject(it.body!!) }

    @Test fun addEmailPostsTheCleanedAddressAsAnEmailTokenWithNoStatus() {
        initialize()
        assertTrue(OpenPush.User.addEmail("  Ana.Lopez@Example.COM ") { results += it })
        OpenPush.settleForTests()

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://app.openpush.ai/v1/apps/demo/subscriptions", request.url)
        assertEquals("sdk-public", request.headers["X-OP-SDK-Key"])
        val body = JSONObject(request.body!!)
        assertEquals("ana.lopez@example.com", body.getString("token"))
        assertEquals("email", body.getString("platform"))
        assertFalse(body.has("status"))
        assertFalse("no external_id while anonymous", body.has("external_id"))
        idleMain()
        assertEquals(listOf(OpenPushEmailResult.Success("sub_email", removed = false)), results)
        assertEquals("sub_email", PhoneSubscriptions.subscriptionId(PhonePlatform.EMAIL, "ana.lopez@example.com"))
    }

    @Test fun aLoggedInUserIsNamedWithItsAuthHash() {
        initialize()
        OpenPush.login("player-9", "hash-9")
        OpenPush.User.addEmail("ana@example.com")
        OpenPush.settleForTests()

        val body = bodies().single()
        assertEquals("player-9", body.getString("external_id"))
        assertEquals("hash-9", body.getString("external_id_auth_hash"))
    }

    @Test fun withoutIdentityVerificationNoHashIsSent() {
        initialize()
        OpenPush.login("player-9")
        OpenPush.User.addEmail("ana@example.com")
        OpenPush.settleForTests()

        val body = bodies().single()
        assertEquals("player-9", body.getString("external_id"))
        assertFalse(body.has("external_id_auth_hash"))
    }

    @Test fun removeEmailSendsStatusMinusTwoAndReportsARemoval() {
        initialize()
        answers +=
            OpenPush.Response(200, """{"id":"sub_email","status":-2,"status_name":"Unsubscribed","created":false}""")
        assertTrue(OpenPush.User.removeEmail("ana@example.com") { results += it })
        OpenPush.settleForTests()

        val body = bodies().single()
        assertEquals("email", body.getString("platform"))
        assertEquals(-2, body.getInt("status"))
        idleMain()
        assertEquals(listOf(OpenPushEmailResult.Success("sub_email", removed = true)), results)
    }

    @Test fun anInvalidAddressIsRefusedLocallyWithATypedErrorAndNeverSent() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        initialize()
        OpenPush.onLog = { logs += it }
        val tooLong = "a".repeat(250) + "@b.co"

        for (address in listOf("", "ana", "@example.com", "ana@@example.com", "ana@example", "a@b@c.com", tooLong)) {
            assertFalse(address, OpenPush.User.addEmail(address) { results += it })
        }
        assertFalse(OpenPush.User.removeEmail("nope"))
        OpenPush.settleForTests()

        assertTrue(requests.isEmpty())
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
        idleMain()
        assertEquals(7, results.size)
        results.forEach {
            assertEquals(OpenPushEmailError.Code.INVALID_EMAIL, (it as OpenPushEmailResult.Failure).error.code)
            assertNull(it.error.status)
        }
        assertTrue(logs.any { it.contains(EmailAddressFormat.INVALID_EMAIL_MESSAGE) })
    }

    @Test fun theLongestValidAddressIsAccepted() {
        initialize()
        assertTrue(OpenPush.User.addEmail("a".repeat(248) + "@b.com"))
    }

    @Test fun aSuppressedAddressIsReportedAndNotRetried() {
        initialize()
        answers += OpenPush.Response(200, """{"id":"sub_s","status":-2,"status_name":"Unsubscribed","created":true}""")
        OpenPush.User.addEmail("ana@example.com") { results += it }
        OpenPush.settleForTests()

        assertEquals(1, requests.size)
        assertTrue(clock.isIdle())
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
        idleMain()
        val error = (results.single() as OpenPushEmailResult.Failure).error
        assertEquals(OpenPushEmailError.Code.SUPPRESSED, error.code)
        assertEquals("sub_s", error.subscriptionId)
    }

    @Test fun a400IsATypedRefusalWithTheServersWordsAndIsNotRetried() {
        initialize()
        answers += OpenPush.Response(400, """{"detail":"Email is off for this app."}""")
        OpenPush.User.addEmail("ana@example.com") { results += it }
        OpenPush.User.addEmail("bo@example.com")
        OpenPush.settleForTests()

        assertEquals("the refusal does not wedge the next write", 2, requests.size)
        assertTrue(clock.isIdle())
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
        idleMain()
        val error = (results.single() as OpenPushEmailResult.Failure).error
        assertEquals(OpenPushEmailError.Code.REFUSED, error.code)
        assertEquals(400, error.status)
        assertEquals("Email is off for this app.", error.message)
    }

    @Test fun aServerErrorIsRetriedOnTheBackoffAndReportedOnce() {
        initialize()
        answers += OpenPush.Response(503, "")
        OpenPush.User.addEmail("ana@example.com") { results += it }
        OpenPush.settleForTests()

        assertEquals(1, requests.size)
        assertEquals(listOf(2_000L), clock.pendingDelays())
        assertTrue("a retry reports nothing", results.isEmpty())

        clock.advance(2_000L)
        OpenPush.settleForTests()

        assertEquals(2, requests.size)
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
        idleMain()
        assertEquals(listOf(OpenPushEmailResult.Success("sub_email", removed = false)), results)
    }

    @Test fun aWriteIsStoredUnderTheEmailKeyAndSentOnceAttached() {
        assertTrue(OpenPush.User.addEmail("Ana@Example.com"))
        OpenPush.User.removeEmail("ana@example.com")
        assertEquals(listOf("email:ana@example.com"), PhoneSubscriptions.pendingWrites().map { it.key })

        initialize()
        OpenPush.settleForTests()

        val body = bodies().single()
        assertEquals("only the newest write per address is sent", -2, body.getInt("status"))
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun anUnsentRemovalSurvivesProcessDeath() {
        initialize()
        answers += OpenPush.Response(503, "")
        OpenPush.User.removeEmail("ana@example.com")
        OpenPush.settleForTests()
        assertEquals(1, requests.size)

        OpenPush.resetForTests()
        OpenPush.scheduler = clock
        OpenPush.jitter = { 0 }
        OpenPush.onLog = {}
        initialize()
        OpenPush.settleForTests()

        assertEquals(2, requests.size)
        assertEquals(-2, bodies().last().getInt("status"))
        assertTrue(PhoneSubscriptions.pendingWrites().isEmpty())
    }

    @Test fun logLinesMaskTheAddress() {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        initialize()
        OpenPush.onLog = { logs += it }
        OpenPush.User.addEmail("ana.lopez@example.com")
        OpenPush.settleForTests()

        assertTrue(logs.any { it.contains("a•••z@example.com") })
        assertFalse(logs.any { it.contains("ana.lopez") })
    }
}
