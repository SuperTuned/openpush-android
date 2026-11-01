package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * Fetches the big picture and large icon a composer attached to a push.
 *
 * A URL in a push is content the sending workspace chose, so the rules here are deliberately
 * narrow: https only, at most [MAX_IMAGE_BYTES], and one monotonic [TOTAL_IMAGE_BUDGET_MS] budget
 * shared by every image in a render. Every failure is a `null` and a log line, never an exception:
 * a picture that cannot be fetched means a plainer notification, not a dropped one.
 *
 * Substitute this in a host renderer by passing your own instance to
 * [OpenPushNotificationRenderer] and overriding [load] — for instance to reuse an image cache
 * the app already has.
 */
open class OpenPushImageLoader internal constructor(
    private val fetch: (String, Long, () -> Long) -> ByteArray?,
    private val nanoTime: () -> Long,
) {
    internal constructor(fetch: (String) -> ByteArray?) : this(
        { url, _, _ -> fetch(url) },
        System::nanoTime,
    )

    constructor() : this(
        { url, deadline, clock -> download(url, deadline, clock) },
        System::nanoTime,
    )

    private val renderDeadline = ThreadLocal<Long?>()

    /** Runs both renderer image lookups against one deadline, without extending a nested budget. */
    internal fun <T> withTotalBudget(block: () -> T): T {
        if (renderDeadline.get() != null) return block()
        renderDeadline.set(nanoTime() + TOTAL_IMAGE_BUDGET_MS * NANOS_PER_MILLI)
        return try {
            block()
        } finally {
            renderDeadline.remove()
        }
    }

    /** The bitmap at [url], or null when it cannot be fetched or decoded under the rules above. */
    open fun load(url: String): Bitmap? {
        val inheritedDeadline = renderDeadline.get()
        val ownsDeadline = inheritedDeadline == null
        val deadline = inheritedDeadline ?: (nanoTime() + TOTAL_IMAGE_BUDGET_MS * NANOS_PER_MILLI)
        if (ownsDeadline) renderDeadline.set(deadline)
        return try {
            runCatching {
                if (!url.trim().startsWith("https://", ignoreCase = true)) {
                    OpenPush.onLog("image skipped — only https urls are fetched")
                    return null
                }
                if (remainingNanos(deadline, nanoTime) <= 0L) {
                    OpenPush.onLog("image skipped — the 10 second image budget expired")
                    return null
                }
                val bytes = fetch(url, deadline, nanoTime) ?: return null
                if (remainingNanos(deadline, nanoTime) <= 0L) {
                    OpenPush.onLog("image skipped — the 10 second image budget expired")
                    return null
                }
                if (bytes.isEmpty()) {
                    OpenPush.onLog("image skipped — the response was empty")
                    return null
                }
                if (bytes.size > MAX_IMAGE_BYTES) {
                    OpenPush.onLog("image skipped — larger than 5 MB")
                    return null
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (remainingNanos(deadline, nanoTime) <= 0L) {
                    OpenPush.onLog("image skipped — the 10 second image budget expired")
                    return null
                }
                if (bitmap == null) OpenPush.onLog("image skipped — the bytes are not a decodable image")
                bitmap
            }.onFailure { OpenPush.onLog("image failed — ${it.message}") }.getOrNull()
        } finally {
            if (ownsDeadline) renderDeadline.remove()
        }
    }

    companion object {
        /** 5 MB, counted twice: once from `Content-Length` and once while the body streams in. */
        internal const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        /** One budget for the big picture and large icon together. */
        internal const val TOTAL_IMAGE_BUDGET_MS = 10_000L

        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 7_000
        private const val NANOS_PER_MILLI = 1_000_000L

        private fun download(url: String, deadline: Long, clock: () -> Long): ByteArray? =
            download(URL(url).openConnection() as HttpURLConnection, deadline, clock)

        /** Internal seam for a deterministic connect-then-header deadline regression. */
        internal fun download(connection: HttpURLConnection, deadline: Long, clock: () -> Long): ByteArray? {
            return try {
                val beforeConnect = remainingTimeoutMillis(deadline, clock) ?: return null
                connection.connectTimeout = min(CONNECT_TIMEOUT_MS, beforeConnect)
                connection.readTimeout = min(READ_TIMEOUT_MS, beforeConnect)
                connection.requestMethod = "GET"
                connection.connect()
                // TLS/connect time belongs to the same absolute budget. responseCode can block on
                // response headers, so replace the pre-connect timeout with only what remains now.
                val beforeHeaders = remainingTimeoutMillis(deadline, clock) ?: return null
                connection.readTimeout = min(READ_TIMEOUT_MS, beforeHeaders)
                val status = connection.responseCode
                if (status !in 200..299) {
                    OpenPush.onLog("image skipped — the host answered $status")
                    return null
                }
                if (remainingNanos(deadline, clock) <= 0L) return expired()
                if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                    OpenPush.onLog("image skipped — Content-Length is larger than 5 MB")
                    return null
                }
                connection.inputStream.use { stream ->
                    readCapped(stream, deadline, clock) { remaining ->
                        connection.readTimeout = min(READ_TIMEOUT_MS, remaining)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        /** A dishonest `Content-Length` must not become an unbounded read, so the body is capped too. */
        internal fun readCapped(
            stream: InputStream,
            deadline: Long,
            clock: () -> Long,
            beforeRead: (Int) -> Unit = {},
        ): ByteArray? {
            val collected = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val remaining = remainingTimeoutMillis(deadline, clock) ?: return null
                beforeRead(remaining)
                val read = stream.read(chunk)
                if (remainingNanos(deadline, clock) <= 0L) return expired()
                if (read == -1) break
                collected.write(chunk, 0, read)
                if (collected.size() > MAX_IMAGE_BYTES) {
                    OpenPush.onLog("image skipped — the body ran past 5 MB")
                    return null
                }
            }
            return collected.toByteArray()
        }

        private fun remainingTimeoutMillis(deadline: Long, clock: () -> Long): Int? {
            val remaining = remainingNanos(deadline, clock)
            if (remaining <= 0L) {
                expired()
                return null
            }
            return ((remaining + NANOS_PER_MILLI - 1L) / NANOS_PER_MILLI)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
                .coerceAtLeast(1)
        }

        private fun remainingNanos(deadline: Long, clock: () -> Long): Long = deadline - clock()

        private fun expired(): ByteArray? {
            OpenPush.onLog("image skipped — the 10 second image budget expired")
            return null
        }
    }
}
