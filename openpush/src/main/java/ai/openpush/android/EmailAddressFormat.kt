package ai.openpush.android

/**
 * The local check every email call in this SDK applies before anything goes on the wire. The
 * address is trimmed and lower-cased, as the server stores it, and must then have exactly one `@`,
 * a non-empty part before it, a dot in the part after it, and at most 254 characters. Anything the
 * server's stricter check still refuses comes back as a 400 in words.
 */
internal object EmailAddressFormat {
    private const val MAX_LENGTH = 254

    /** Says what a refused address must look like. It never echoes the address itself. */
    const val INVALID_EMAIL_MESSAGE =
        "not an email address — one @, a name before it and a domain with a dot after it, " +
            "at most 254 characters, e.g. ana@example.com"

    /** The cleaned form of [raw], or null when it is not an address this SDK sends. */
    fun normalized(raw: String): String? {
        val address = raw.trim().lowercase()
        if (address.length > MAX_LENGTH || address.count { it == '@' } != 1) return null
        val local = address.substringBefore('@')
        val domain = address.substringAfter('@')
        return address.takeIf { local.isNotEmpty() && '.' in domain }
    }

    /** [address] with its name hidden, for log lines: `a•••z@example.com`. */
    fun masked(address: String): String {
        val local = address.substringBefore('@')
        val domain = address.substringAfter('@', "")
        val tail = if (local.length > 2) local.takeLast(1) else ""
        return local.take(1) + "•••" + tail + "@" + domain
    }
}
