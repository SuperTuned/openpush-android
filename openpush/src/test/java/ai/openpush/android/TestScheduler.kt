package ai.openpush.android

/** Hand-driven clock. `advance` runs every action that has come due, on the calling thread. */
internal class TestScheduler : OpenPush.Scheduler {
    private class Entry(val dueAt: Long, val action: () -> Unit) {
        @Volatile var cancelled = false
    }

    private val entries = mutableListOf<Entry>()
    private var now = 0L

    // `now` is read and written under `entries` by both the test thread and the SDK's IO threads.
    // Unsynchronized, a worker scheduling a retry can see a stale `now` and file it in the past, where
    // the next `advance` fires it early — a backoff test then sees one request too many, sometimes.
    override fun schedule(delayMillis: Long, action: () -> Unit): OpenPush.Cancellable {
        val entry = synchronized(entries) { Entry(now + delayMillis, action).also { entries += it } }
        return OpenPush.Cancellable { entry.cancelled = true }
    }

    fun advance(millis: Long) {
        val due = synchronized(entries) {
            now += millis
            val ready = entries.filter { it.dueAt <= now && !it.cancelled }
            entries.removeAll { it.dueAt <= now }
            ready
        }
        due.forEach { it.action() }
    }

    fun isIdle() = synchronized(entries) { entries.none { !it.cancelled } }

    /** How long each live entry still has to wait. Lets a retry test name the delay a failure was
     *  filed for without advancing to it — and without knowing where the clock already stands. */
    fun pendingDelays(): List<Long> = synchronized(entries) { entries.filter { !it.cancelled }.map { it.dueAt - now } }
}
