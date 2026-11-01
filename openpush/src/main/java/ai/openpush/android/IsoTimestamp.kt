package ai.openpush.android

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the ISO 8601 timestamps the server writes (`2026-10-23T10:15:30.123456+00:00`, or with `Z`)
 * into epoch milliseconds. Written by hand because `java.time` needs API 26 and this SDK runs from 24.
 * Fractions beyond milliseconds are dropped; a timestamp without a zone is read as UTC.
 */
internal object IsoTimestamp {
    private val SHAPE = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})?$""")

    /** Epoch milliseconds for [value], or null when it is absent or not in that shape. */
    fun parseMillis(value: String?): Long? {
        val match = value?.trim()?.let(SHAPE::matchEntire) ?: return null
        val (whole, fraction, zone) = match.destructured
        val base = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
                .parse(whole)?.time
        }.getOrNull() ?: return null
        val millis = if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()
        return base + millis - offsetMillis(zone)
    }

    private fun offsetMillis(zone: String): Long {
        if (zone.isEmpty() || zone == "Z") return 0L
        val digits = zone.substring(1).replace(":", "")
        val minutes = digits.substring(0, 2).toLong() * 60L + digits.substring(2).toLong()
        val sign = if (zone[0] == '-') -1L else 1L
        return sign * minutes * 60_000L
    }
}
