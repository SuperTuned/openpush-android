package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** `requestPermission()` without an Activity, called before any Activity has resumed. */
@OptIn(OpenPushInternalApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushDeferredPermissionTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val bridgeResults = mutableListOf<Boolean>()
    private val logs = mutableListOf<String>()

    @Before fun reset() {
        OpenPush.resetForTests()
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.onLog = { logs += it }
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") { OpenPush.Response(200, "{}") }
        OpenPushBridge.addPermissionResultListener { bridgeResults += it }
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun clear() {
        OpenPush.onLog = {}
    }

    /** A host in its onCreate: created, not yet started or resumed. */
    private fun creating(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create()

    private fun startedPrompts(host: Activity): Int = generateSequence { shadowOf(host).nextStartedActivity }
        .count { it.component?.className == OpenPushPermissionActivity::class.java.name }

    @Test fun aRequestBeforeTheFirstResumeIsDeferredInsteadOfAnsweredFromTheCache() {
        val controller = creating()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(callback = results::add)

        assertTrue(results.isEmpty())
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertTrue(logs.any { "deferred" in it })
        assertTrue(logs.none { "cached permission" in it })
    }

    @Test fun theDeferredPromptStartsOnResumeAndItsAnswerReachesEveryCoalescedCallback() {
        val controller = creating()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        val third = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = first::add)
        OpenPush.Notifications.requestPermission(fallbackToSettings = true, callback = second::add)
        OpenPush.Notifications.requestPermission(callback = third::add)

        controller.start().resume()
        val host = controller.get()
        val prompt = PermissionPrompt.started(host).get()
        // One prompt for all three callers.
        assertEquals(0, startedPrompts(host))
        assertTrue(first.isEmpty() && second.isEmpty() && third.isEmpty())

        PermissionPrompt.answer(prompt, granted = true)

        idleMain()
        assertEquals(listOf(true), first)
        assertEquals(listOf(true), second)
        assertEquals(listOf(true), third)
        assertEquals(listOf(true), bridgeResults)
        assertTrue(OpenPush.Notifications.permission)

        // The deferral is spent: a later resume starts nothing.
        controller.pause().stop().start().resume()
        assertEquals(0, startedPrompts(host))
        assertEquals(listOf(true), first)
    }

    @Test fun aDenialOfTheDeferredPromptReachesTheCallback() {
        val controller = creating()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = results::add)

        controller.start().resume()
        PermissionPrompt.answer(controller.get(), granted = false)

        idleMain()
        assertEquals(listOf(false), results)
    }

    @Test fun aRequestMadeWhileTheDeferredPromptIsUpJoinsIt() {
        val controller = creating()
        val first = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = first::add)
        controller.start().resume()
        val prompt = PermissionPrompt.started(controller.get())

        val second = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = second::add)
        assertEquals(0, startedPrompts(controller.get()))
        PermissionPrompt.answer(prompt.get(), granted = true)

        idleMain()
        assertEquals(listOf(true), first)
        assertEquals(listOf(true), second)
    }

    @Test fun anAlreadyGrantedPermissionAnswersAtOnceWithoutDeferring() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = creating()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(callback = results::add)

        idleMain()
        assertEquals(listOf(true), results)
        controller.start().resume()
        assertEquals(0, startedPrompts(controller.get()))
        assertEquals(listOf(true), results)
    }

    @Test
    @Config(sdk = [31])
    fun belowApi33ItAnswersAtOnce() {
        val controller = creating()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(callback = results::add)

        idleMain()
        assertEquals(listOf(true), results) // Robolectric enables notifications by default
        controller.start().resume()
        assertEquals(0, startedPrompts(controller.get()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theSuspendFormResumesWhenTheDeferredPromptAnswers() {
        val controller = creating()
        val answer = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
            OpenPush.Notifications.requestPermission(fallbackToSettings = false)
        }
        assertFalse(answer.isCompleted)

        controller.start().resume()
        PermissionPrompt.answer(controller.get(), granted = true)

        assertTrue(answer.isCompleted)
        assertTrue(answer.getCompleted())
    }

    /** 1.2.1's call shape: `val granted = requestPermission()` in a coroutine waits for the answer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theNoArgumentCallInACoroutineSuspendsUntilTheDeferredPromptAnswers() {
        val controller = creating()
        val answer = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
            val granted: Boolean = OpenPush.Notifications.requestPermission()
            granted
        }
        assertFalse(answer.isCompleted)

        controller.start().resume()
        assertFalse(answer.isCompleted)
        PermissionPrompt.answer(controller.get(), granted = false)

        assertTrue(answer.isCompleted)
        assertFalse(answer.getCompleted())
    }

    /** Outside a coroutine, the callback form: a trailing lambda gets the answer on the main thread. */
    @Test fun aTrailingLambdaIsTheCallbackFormAndGetsTheDeferredAnswer() {
        val controller = creating()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission { granted -> results += granted }
        assertTrue(results.isEmpty())

        controller.start().resume()
        PermissionPrompt.answer(controller.get(), granted = true)

        idleMain()
        assertEquals(listOf(true), results)
    }

    @Test fun theActivityOverloadStillPromptsAtOnceFromAnActivityThatHasNotResumed() {
        val controller = creating()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(controller.get(), callback = results::add)

        val prompt = PermissionPrompt.started(controller.get()).get()
        PermissionPrompt.answer(prompt, granted = true)
        idleMain()
        assertEquals(listOf(true), results)
        controller.start().resume()
        assertEquals(0, startedPrompts(controller.get()))
        assertTrue(logs.none { "deferred" in it })
    }

    @Test fun aDeferredRequestJoinsAnActivityRequestAlreadyInFlight() {
        val controller = creating()
        val deferred = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = deferred::add)
        val explicit = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(controller.get(), callback = explicit::add)
        val prompt = PermissionPrompt.started(controller.get())

        controller.start().resume()
        assertEquals(0, startedPrompts(controller.get()))
        PermissionPrompt.answer(prompt.get(), granted = true)

        idleMain()
        assertEquals(listOf(true), explicit)
        assertEquals(listOf(true), deferred)
    }

    @Test fun aForwardedResultWhileDeferredIsStillDeliveredAndTheResumeThenAnswersWithoutAPrompt() {
        val controller = creating()
        val results = mutableListOf<Boolean>()
        OpenPush.Notifications.requestPermission(callback = results::add)

        // A wrapper SDK's own prompt (Unity's AndroidPermissionRequester) reports a grant.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OpenPush.Notifications.onPermissionResult(
            OpenPush.Notifications.PERMISSION_REQUEST_CODE,
            intArrayOf(PackageManager.PERMISSION_GRANTED),
        )
        assertEquals(listOf(true), bridgeResults)

        controller.start().resume()
        assertEquals(0, startedPrompts(controller.get()))
        idleMain()
        assertEquals(listOf(true), results)
    }

    @Test fun withNoActivityWatcherItReportsTheCachedPermission() {
        OpenPush.resetForTests()
        val results = mutableListOf<Boolean>()

        OpenPush.Notifications.requestPermission(callback = results::add)

        idleMain()
        assertEquals(listOf(false), results)
    }
}
