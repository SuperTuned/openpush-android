package ai.openpush.android

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs everything posted to the main thread so far: public callbacks are delivered there. */
internal fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

/** True when the calling thread is the main thread. */
internal fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

/**
 * [CountDownLatch.await] for a latch that a public callback counts down. Robolectric runs the test
 * on the main thread, which is where the callback is posted, so a plain await would block the very
 * thread it needs. This lets the main looper run between checks, within the same overall [timeout].
 */
internal fun CountDownLatch.awaitOnMain(timeout: Long, unit: TimeUnit): Boolean {
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    while (true) {
        idleMain()
        if (count == 0L) return true
        val left = deadline - System.nanoTime()
        if (left <= 0L) return false
        await(minOf(left, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)
    }
}
