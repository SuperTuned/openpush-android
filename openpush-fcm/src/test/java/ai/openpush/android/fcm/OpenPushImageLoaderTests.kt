package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/**
 * The loader is the only place a push can make the SDK reach out to a URL the composer chose, so
 * every refusal is a deliberate one: plaintext transport, an oversized body, a slow host, or any
 * failure at all. Nothing here throws — a picture that cannot be fetched only means a plainer
 * notification, never a dropped one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenPushImageLoaderTests {
    private val logs = Collections.synchronizedList(mutableListOf<String>())

    @Before fun captureLogs() {
        logs.clear()
        OpenPush.onLog = { logs += it }
    }

    @Test fun httpsBytesDecodeToABitmap() {
        val loader = OpenPushImageLoader { ByteArray(64) { 1 } }
        assertNotNull(loader.load("https://cdn.example.com/a.png"))
    }

    @Test fun aPlaintextUrlIsRefusedWithoutFetching() {
        var fetched = false
        val loader = OpenPushImageLoader {
            fetched = true
            ByteArray(64)
        }

        assertNull(loader.load("http://cdn.example.com/a.png"))

        assertTrue("the fetch must not run for a plaintext url", !fetched)
        assertTrue(logs.any { it.contains("https") })
    }

    @Test fun aUrlThatIsNotAUrlIsRefused() {
        assertNull(OpenPushImageLoader { ByteArray(64) }.load("not a url"))
    }

    @Test fun bytesOverTheCapAreRefused() {
        val loader = OpenPushImageLoader { ByteArray(OpenPushImageLoader.MAX_IMAGE_BYTES + 1) }

        assertNull(loader.load("https://cdn.example.com/huge.png"))

        assertTrue(logs.any { it.contains("5 MB") })
    }

    @Test fun aFetchReturningNothingIsNull() {
        assertNull(OpenPushImageLoader { null }.load("https://cdn.example.com/missing.png"))
    }

    @Test fun aThrowingFetchIsNullRatherThanAnEscapingException() {
        val loader = OpenPushImageLoader { error("connection reset") }

        assertNull(loader.load("https://cdn.example.com/a.png"))

        assertTrue(logs.any { it.contains("connection reset") })
    }

    @Test fun emptyBytesAreNull() {
        assertNull(OpenPushImageLoader { ByteArray(0) }.load("https://cdn.example.com/empty.png"))
    }

    @Test fun aTricklingStreamCannotExtendTheMonotonicDeadline() {
        var now = 0L
        var reads = 0
        val timeouts = mutableListOf<Int>()
        val trickle = object : InputStream() {
            override fun read(): Int = error("bulk reads are expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                now += 1_000_000_000L
                reads++
                buffer[offset] = 1
                return 1
            }
        }

        val bytes = OpenPushImageLoader.readCapped(
            trickle,
            10_000_000_000L,
            { now },
            timeouts::add,
        )

        assertNull(bytes)
        assertEquals(10, reads)
        assertEquals(10_000, timeouts.first())
        assertEquals(1_000, timeouts.last())
        assertTrue(logs.any { it.contains("10 second image budget expired") })
    }

    @Test fun connectTimeIsSubtractedBeforeWaitingForResponseHeaders() {
        var now = 0L
        var timeoutAtHeaders = -1
        var disconnected = false
        val payload = byteArrayOf(1, 2, 3)
        val connection = object : HttpURLConnection(URL("https://cdn.example.com/slow-headers.png")) {
            override fun connect() {
                now += 3_000_000_000L
            }

            override fun usingProxy(): Boolean = false
            override fun disconnect() {
                disconnected = true
            }

            override fun getResponseCode(): Int {
                timeoutAtHeaders = readTimeout
                return 200
            }

            override fun getContentLengthLong(): Long = payload.size.toLong()
            override fun getInputStream() = ByteArrayInputStream(payload)
        }

        val bytes = OpenPushImageLoader.download(
            connection,
            deadline = 4_000_000_000L,
            clock = { now },
        )

        assertNotNull(bytes)
        assertTrue(payload.contentEquals(bytes!!))
        assertEquals(3_000, connection.connectTimeout)
        assertEquals(1_000, timeoutAtHeaders)
        assertTrue(disconnected)
    }

    @Test fun twoImagesShareOneTenSecondBudget() {
        var now = 0L
        val deadlines = mutableListOf<Long>()
        val loader = OpenPushImageLoader(
            fetch = { _, deadline, _ ->
                deadlines += deadline
                now += if (deadlines.size == 1) 6_000_000_000L else 4_000_000_000L
                ByteArray(64) { 1 }
            },
            nanoTime = { now },
        )

        val loaded = loader.withTotalBudget {
            loader.load("https://cdn.example.com/picture.png") to
                loader.load("https://cdn.example.com/icon.png")
        }

        assertNotNull(loaded.first)
        assertNull(loaded.second)
        assertEquals(listOf(10_000_000_000L, 10_000_000_000L), deadlines)
        assertEquals(10_000_000_000L, now)
    }
}
