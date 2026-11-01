package ai.openpush.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushSuspendTests {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        context.getSharedPreferences("openpush", Context.MODE_PRIVATE).edit().clear().commit()
        OpenPush.resetForTests()
        OpenPush.onLog = {}
        OpenPush.onRegistered = null
        OpenPush.onRegistrationFailed = null
    }

    private fun initialize(status: Int = 200) {
        OpenPush.initialize(context, appId = "demo", sdkKey = "sdk") {
            OpenPush.Response(status, if (status in 200..299) """{"id":"sub_1"}""" else "nope")
        }
    }

    @Test fun loginSuspendReturnsTheIdentityResult() = runTest {
        initialize()
        OpenPush.registerBlocking("provider-token")

        val result = withContext(Dispatchers.IO) {
            withTimeout(5_000L) { OpenPush.loginSuspend("user-1", "hash-1") }
        }

        assertEquals(IdentityResult.Success, result)
    }

    @Test fun logoutSuspendReturnsTheIdentityResult() = runTest {
        initialize()
        OpenPush.registerBlocking("provider-token")
        OpenPush.loginBlocking("user-1")

        val result = withContext(Dispatchers.IO) {
            withTimeout(5_000L) { OpenPush.logoutSuspend() }
        }

        assertEquals(IdentityResult.Success, result)
    }

    @Test fun awaitRegistrationResumesOnAcceptance() = runTest {
        initialize()
        val callbackCalls = AtomicInteger()
        OpenPush.onRegistered = { callbackCalls.incrementAndGet() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { OpenPush.awaitRegistration() }

        OpenPush.register("provider-token")

        assertTrue(withContext(Dispatchers.IO) { withTimeout(5_000L) { waiting.await() } })
        OpenPush.settleForTests()
        idleMain()
        assertEquals(1, callbackCalls.get())
    }

    @Test fun awaitRegistrationReturnsImmediatelyWhenAlreadyRegistered() = runTest {
        initialize()
        OpenPush.registerBlocking("provider-token")

        assertTrue(OpenPush.awaitRegistration())
    }

    @Test fun awaitRegistrationResumesFalseOnFailureAndPreservesTheHostCallback() = runTest {
        initialize(status = 401)
        val callbackCalls = AtomicInteger()
        OpenPush.onRegistrationFailed = { callbackCalls.incrementAndGet() }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { OpenPush.awaitRegistration() }

        OpenPush.register("provider-token")

        assertFalse(withContext(Dispatchers.IO) { withTimeout(5_000L) { waiting.await() } })
        OpenPush.settleForTests()
        idleMain()
        assertEquals(1, callbackCalls.get())
    }

    @Test fun aCancelledRegistrationWaiterIsRemovedWithoutTouchingTheHostCallback() = runTest {
        initialize()
        val callbackCalls = AtomicInteger()
        OpenPush.onRegistered = { callbackCalls.incrementAndGet() }
        var cancelledResult: Boolean? = null
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            cancelledResult = OpenPush.awaitRegistration()
        }

        waiting.cancelAndJoin()
        OpenPush.registerBlocking("provider-token")

        assertNull(cancelledResult)
        idleMain()
        assertEquals(1, callbackCalls.get())
        assertTrue(OpenPush.awaitRegistration())
    }
}
