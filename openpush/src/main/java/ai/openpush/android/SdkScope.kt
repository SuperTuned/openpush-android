package ai.openpush.android

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A scope for the SDK's own background work. A failure that escapes one job is logged through
 * [OpenPush.onLog] instead of reaching the host's default handler, which on Android ends the
 * process; [SupervisorJob] keeps it from cancelling the scope's other jobs.
 */
internal fun sdkScope(area: String): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO + loggingExceptionHandler(area))

internal fun loggingExceptionHandler(area: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    OpenPush.onLog("✗ $area: unexpected ${error.javaClass.simpleName} — ${error.message}")
}
