package ai.openpush.android.inappmessages

/**
 * Whether a message's triggers are satisfied by what the host has told the SDK.
 *
 * The shape is the server's: the outer list is `OR`, each inner group is `AND`, and a message with
 * no triggers at all is always satisfied. Two condition kinds read the clock rather than the host's
 * trigger values — `session_time` and `min_time_since` — and are passed in as seconds.
 */
internal object InAppTriggerController {
    fun matches(
        groups: List<List<IamCondition>>,
        values: Map<String, Any>,
        sessionSeconds: Double,
        sinceLastSeconds: Double,
    ): Boolean = groups.isEmpty() ||
        groups.any { group ->
            group.isNotEmpty() &&
                group.all { condition ->
                    val actual: Any? = when (condition.kind) {
                        "session_time" -> sessionSeconds
                        "min_time_since" -> sinceLastSeconds
                        else -> condition.property?.let(values::get)
                    }
                    when (condition.op) {
                        "exists" -> actual != null
                        "not_exists" -> actual == null
                        else -> compare(actual, condition.value, condition.op)
                    }
                }
        }

    private fun compare(actual: Any?, expected: Any?, op: String): Boolean {
        if (actual == null || expected == null) return false
        if (op == "in") return isMember(actual, expected)
        val left = actual.toString().toDoubleOrNull()
        val right = expected.toString().toDoubleOrNull()
        if (left != null && right != null) {
            return when (op) {
                "equal" -> left == right
                "not_equal" -> left != right
                "greater" -> left > right
                "greater_or_equal" -> left >= right
                "less" -> left < right
                "less_or_equal" -> left <= right
                else -> false
            }
        }
        return when (op) {
            "equal" -> actual.toString() == expected.toString()
            "not_equal" -> actual.toString() != expected.toString()
            else -> false
        }
    }

    /**
     * Whether [expected] is one of [actual]'s members.
     *
     * `in` is membership, not substring: a device value of `"gold, silver"` contains the text
     * `"sil"` but its members are `gold` and `silver`, and a trigger asking for `"sil"` must not
     * match. A comma-separated string is how the wire writes a list, so it is split and each part
     * trimmed; a [Collection] is already a list; anything else is a scalar and is only "in" itself.
     */
    private fun isMember(actual: Any, expected: Any): Boolean {
        val members: List<Any?> = when (actual) {
            is Collection<*> -> actual.toList()
            is String -> actual.split(',').map(String::trim)
            else -> listOf(actual)
        }
        return members.any { member -> sameValue(member, expected) }
    }

    /** Equality as the rest of this file reads it: numeric when both sides parse, text otherwise. */
    private fun sameValue(member: Any?, expected: Any): Boolean {
        if (member == null) return false
        val left = member.toString().toDoubleOrNull()
        val right = expected.toString().toDoubleOrNull()
        if (left != null && right != null) return left == right
        return member.toString() == expected.toString()
    }
}
