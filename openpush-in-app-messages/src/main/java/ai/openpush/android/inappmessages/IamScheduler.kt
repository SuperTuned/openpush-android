package ai.openpush.android.inappmessages

import android.os.Handler
import android.os.Looper

internal fun interface IamCancellable {
    fun cancel()
}

/** The engine's only clock, so tests can drive display-duration timers by hand. */
internal interface IamScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): IamCancellable
}

internal object MainThreadScheduler : IamScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): IamCancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return IamCancellable { handler.removeCallbacks(runnable) }
    }
}
