package ai.openpush.android

/**
 * Whether a request that came back with this status is worth sending again. 429 joins the transport
 * failures (status <= 0) and the 5xx range: it is the one 4xx that means "later", not "never", and it
 * is the status the events endpoint uses to shed load.
 */
internal fun Int.isRetryableStatus() = this <= 0 || this == 429 || this in 500..599
