package ai.openpush.android

import android.os.Handler
import android.os.Looper

/**
 * The one path public callbacks take to the main thread, shared by every OpenPush module.
 *
 * [post] always queues, even when the caller is already on the main thread, so a callback never
 * runs inside the SDK call that caused it. Everything goes through one [Handler] on the main
 * [Looper], which is one first-in, first-out queue: callbacks run in the order they were posted.
 * Nothing here waits for the main thread.
 */
@OpenPushInternalApi
object OpenPushMainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** True when the calling thread is the main thread. */
    val isCurrent: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    /** Queues [action] on the main thread, also when called from it. False when the looper is gone. */
    fun post(action: () -> Unit): Boolean = handler.post(action)

    /** Runs [action] now when called on the main thread; queues it from any other thread. */
    fun run(action: () -> Unit) {
        if (isCurrent) action() else post(action)
    }
}
