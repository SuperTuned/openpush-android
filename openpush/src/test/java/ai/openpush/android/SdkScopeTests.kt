package ai.openpush.android

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** A failure that escapes one of the SDK's own jobs is logged, never thrown into the host's process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdkScopeTests {
    @After fun restoreLog() {
        OpenPush.onLog = {}
    }

    @Test fun anEscapedFailureIsLoggedAndTheScopeKeepsWorking() {
        val logs = CopyOnWriteArrayList<String>()
        OpenPush.onLog = { logs += it }
        val scope = sdkScope("OpenPush test")

        runBlocking { scope.launch { throw IllegalStateException("boom") }.join() }

        assertTrue(
            "the failure is logged: $logs",
            logs.any {
                it ==
                    "✗ OpenPush test: unexpected IllegalStateException — boom"
            },
        )
        var ran = false
        runBlocking { scope.launch { ran = true }.join() }
        assertTrue("a failed job must not cancel its siblings", ran)
    }

    @Test fun theScopeCarriesTheLoggingHandler() {
        assertNotNull(sdkScope("OpenPush").coroutineContext[CoroutineExceptionHandler])
    }
}
