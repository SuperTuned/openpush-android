package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Where host code runs. Robolectric runs each test on the main thread, so a callback that records
 * [onMainThread] proves its thread, and a list that is still empty until [idleMain] proves the
 * callback was posted rather than run inside the SDK call or on the SDK's own threads. Each list
 * below is written only from inside a callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushMainThreadDeliveryTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Volatile private var status = 200

    @Volatile private var registrationBody = """{"id":"sub_1"}"""

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun restore() {
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
        OpenPush.onIdentityRejected = null
        idleMain()
        OpenPush.resetForTests()
    }

    private fun initialize() {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk", credential = { "opdev" }) { request ->
            when {
                request.url.contains("/verify") -> OpenPush.Response(200, VERIFICATION)
                request.body?.contains("\"platform\":\"email\"") == true ->
                    OpenPush.Response(200, """{"id":"sub_email","status":1}""")
                request.url.endsWith("/subscriptions") ->
                    OpenPush.Response(status, if (status in 200..299) registrationBody else "")
                else -> OpenPush.Response(202, "{}")
            }
        }
    }

    // Registration hooks

    @Test fun onRegisteredIsPostedToTheMainThreadFromTheRegistrationQueue() {
        initialize()
        val calls = mutableListOf<Boolean>()
        OpenPush.onRegistered = { calls += onMainThread() }
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertTrue("it ran on the registration queue", calls.isEmpty())
        idleMain()
        assertEquals(listOf(true), calls)
    }

    @Test fun onRegistrationFailedIsPostedToTheMainThreadFromTheQueueAndFromInitialize() {
        status = 401
        initialize()
        val calls = mutableListOf<Boolean>()
        OpenPush.onRegistrationFailed = { calls += onMainThread() }
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertTrue(calls.isEmpty())
        idleMain()
        assertEquals(listOf(true), calls)

        // A refusal decided on the main thread is still posted, not run inside initialize.
        OpenPush.initialize(context, appId = "not an id", sdkKey = "sdk")
        assertEquals(1, calls.size)
        idleMain()
        assertEquals(listOf(true, true), calls)
    }

    @Test fun onIdentityRejectedIsPostedToTheMainThread() {
        registrationBody = """{"id":"sub_1","status":1,"identity_rejected":"bad hash"}"""
        initialize()
        val notes = mutableListOf<Pair<Boolean, String>>()
        OpenPush.onIdentityRejected = { notes += onMainThread() to it }
        OpenPush.login("user-a", "wrong")
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertTrue(notes.isEmpty())
        idleMain()
        assertTrue(notes.isNotEmpty())
        assertTrue(notes.all { (main, note) -> main && note == "bad hash" })
    }

    // Push subscription and permission observers

    @Test fun subscriptionObserversArePostedFromTheQueueAndFromAMainThreadCall() {
        initialize()
        val states = mutableListOf<Boolean>()
        val changes = mutableListOf<Boolean>()
        OpenPush.PushSubscription.addObserver { states += onMainThread() }
        OpenPush.PushSubscription.addChangeObserver { changes += onMainThread() }
        OpenPush.register("provider-token")
        OpenPush.settleForTests()
        assertTrue(states.isEmpty())
        idleMain()
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it })
        assertEquals(states.size, changes.size)
        assertTrue(changes.all { it })

        val before = states.size
        OpenPush.PushSubscription.optOut()
        assertEquals("it ran inside optOut", before, states.size)
        OpenPush.settleForTests()
        idleMain()
        assertEquals(before + 1, states.size)
        assertTrue(states.last())
    }

    @Test fun eventsFromOneThreadReachTheObserverInOrder() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        idleMain()
        val optedIn = mutableListOf<Boolean>()
        OpenPush.PushSubscription.addObserver { optedIn += it.optedIn }
        OpenPush.PushSubscription.optOut()
        OpenPush.PushSubscription.optIn()
        OpenPush.PushSubscription.optOut()
        OpenPush.settleForTests()
        idleMain()
        assertEquals(listOf(false, true, false), optedIn)
    }

    @Test fun anObserverRemovedBeforeThePostRunsIsNotCalled() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        idleMain()
        val calls = mutableListOf<Boolean>()
        val handle = OpenPush.PushSubscription.addObserver { calls += it.optedIn }
        OpenPush.PushSubscription.optOut()
        OpenPush.PushSubscription.removeObserver(handle)
        OpenPush.settleForTests()
        idleMain()
        assertTrue(calls.isEmpty())
    }

    @Test fun permissionObserversArePostedToTheMainThreadFromAWorkerThread() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        initialize()
        val seen = mutableListOf<Boolean>()
        OpenPush.Notifications.addPermissionObserver { granted -> if (onMainThread()) seen += granted }
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val worker = thread {
            OpenPush.Notifications.onPermissionResult(
                OpenPush.Notifications.PERMISSION_REQUEST_CODE,
                intArrayOf(PackageManager.PERMISSION_GRANTED),
            )
        }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue(seen.isEmpty())
        idleMain()
        assertEquals(listOf(true), seen)
    }

    // requestPermission

    @Test fun anAnswerGivenAtOnceIsStillPostedToTheMainThread() {
        initialize()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val answers = mutableListOf<Pair<Boolean, Boolean>>()
        OpenPush.Notifications.requestPermission(activity) { answers += onMainThread() to it }
        OpenPush.Notifications.requestPermission { answers += onMainThread() to it }
        assertTrue("it ran inside requestPermission", answers.isEmpty())
        idleMain()
        assertEquals(listOf(true to true, true to true), answers)
    }

    @Test fun theSuspendPermissionFormDoesNotWaitForTheMainThread() {
        initialize()
        // runBlocking holds the main thread, so a resume that needed a main-thread post never came.
        val granted = runBlocking { withTimeout(5_000L) { OpenPush.Notifications.requestPermission(false) } }
        assertTrue(granted)
    }

    // Identity

    @Test fun loginAndLogoutCallbacksArePostedToTheMainThread() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        idleMain()
        val results = mutableListOf<Pair<Boolean, IdentityResult>>()
        OpenPush.login("user-7") { results += onMainThread() to it }
        OpenPush.logout { results += onMainThread() to it }
        OpenPush.settleForTests()
        assertTrue("a callback ran on the registration queue", results.isEmpty())
        idleMain()
        assertEquals(listOf(true to IdentityResult.Success, true to IdentityResult.Success), results)

        // Answered locally, with nothing sent: still posted rather than run inside login.
        OpenPush.login("  ") { results += onMainThread() to it }
        assertEquals(2, results.size)
        idleMain()
        assertEquals(true to IdentityResult.Failed("empty external id"), results.last())
    }

    @Test fun theSuspendIdentityFormsDoNotWaitForTheMainThread() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        val results = runBlocking {
            withTimeout(5_000L) { listOf(OpenPush.loginSuspend("user-7"), OpenPush.logoutSuspend()) }
        }
        assertEquals(listOf(IdentityResult.Success, IdentityResult.Success), results)
    }

    // Email, phone codes and receipts

    @Test fun emailCallbacksArePostedToTheMainThread() {
        initialize()
        val results = mutableListOf<Pair<Boolean, OpenPushEmailResult>>()
        OpenPush.User.addEmail("not an address") { results += onMainThread() to it }
        assertTrue("an invalid address answered inside addEmail", results.isEmpty())
        OpenPush.User.addEmail("ana@example.com") { results += onMainThread() to it }
        OpenPush.settleForTests()
        assertTrue(results.isEmpty())
        idleMain()
        assertEquals(2, results.size)
        assertTrue(results.all { it.first })
        assertTrue(results[0].second is OpenPushEmailResult.Failure)
        assertEquals(OpenPushEmailResult.Success("sub_email", removed = false), results[1].second)
    }

    @Test fun verifyCallbacksArePostedToTheMainThread() {
        initialize()
        val onMain = Collections.synchronizedList(mutableListOf<Boolean>())
        val done = java.util.concurrent.CountDownLatch(1)
        OpenPush.Verify.start("+14155550123") {
            onMain += onMainThread()
            done.countDown()
        }
        assertTrue(done.awaitOnMain(5, TimeUnit.SECONDS))
        assertEquals(listOf(true), onMain)
    }

    @Test fun theReceiptContinuationIsPostedToTheMainThread() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        val ran = mutableListOf<Boolean>()
        OpenPush.receipt("received", "m1") { ran += onMainThread() }
        OpenPush.settleForTests()
        assertTrue(ran.isEmpty())
        idleMain()
        assertEquals(listOf(true), ran)
    }

    // Clicks, foreground and background pushes

    @Test fun aClickReportedOnTheMainThreadIsDeliveredAtOnceAndOneFromAWorkerIsPosted() {
        initialize()
        val clicks = mutableListOf<Pair<Boolean, String>>()
        val handle = OpenPush.Notifications.addClickListener { clicks += onMainThread() to it.messageId!! }
        try {
            OpenPush.Notifications.processClick(mapOf("op_message_id" to "m1"))
            assertEquals("the trampoline's click runs before it opens the app", listOf(true to "m1"), clicks)

            thread { OpenPush.Notifications.processClick(mapOf("op_message_id" to "m2")) }
                .join(TimeUnit.SECONDS.toMillis(5))
            assertEquals(1, clicks.size)
            idleMain()
            assertEquals(listOf(true to "m1", true to "m2"), clicks)
        } finally {
            OpenPush.Notifications.removeClickListener(handle)
        }
    }

    @Test fun aHeldClickIsPostedToTheMainThreadWhenTheListenerRegistersFromAWorker() {
        initialize()
        OpenPush.Notifications.processClick(mapOf("op_message_id" to "cold"))
        val clicks = Collections.synchronizedList(mutableListOf<Pair<Boolean, String>>())
        var handle: ListenerHandle? = null
        thread { handle = OpenPush.Notifications.addClickListener { clicks += onMainThread() to it.messageId!! } }
            .join(TimeUnit.SECONDS.toMillis(5))
        try {
            assertTrue(clicks.isEmpty())
            idleMain()
            assertEquals(listOf(true to "cold"), clicks)
        } finally {
            handle?.let(OpenPush.Notifications::removeClickListener)
        }
    }

    @Test fun foregroundAndBackgroundListenersStayOnTheDeliveringThread() {
        initialize()
        OpenPush.registerBlocking("provider-token")
        Robolectric.buildActivity(Activity::class.java).setup()
        val foreground = mutableListOf<Thread>()
        val background = mutableListOf<Thread>()
        val foregroundHandle = OpenPush.Notifications.addForegroundLifecycleListener {
            foreground += Thread.currentThread()
            it.preventDefault()
        }
        val backgroundHandle = OpenPush.Notifications.addBackgroundMessageListener {
            background += Thread.currentThread()
        }
        var prevented = false
        var backgroundHandled = false
        val worker = thread {
            prevented = OpenPush.Notifications.processForegroundMessage(mapOf("op_message_id" to "f1", "title" to "hi"))
            backgroundHandled = OpenPush.Notifications.processForegroundMessage(
                mapOf("op_message_id" to "b1", "op_background" to "1"),
            )
        }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        OpenPush.Notifications.removeForegroundLifecycleListener(foregroundHandle)
        OpenPush.Notifications.removeBackgroundMessageListener(backgroundHandle)
        assertFalse(worker.isAlive)
        // preventDefault was read before processForegroundMessage returned, on the worker itself.
        assertTrue(prevented)
        assertTrue(backgroundHandled)
        assertEquals(listOf(worker), foreground)
        assertEquals(listOf(worker), background)
    }

    private companion object {
        const val VERIFICATION = """{"id":"vf_123","to":"+1415•••0123","channel":"sms","status":"pending",""" +
            """"attempts":0,"expires_at":"2026-10-23T10:25:30.000000+00:00"}"""
    }
}
