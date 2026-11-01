package ai.openpush.gradle.setup

/**
 * The small JSON subset the setup file needs: objects keep their key order, so a rewrite leaves
 * fields this plugin does not know where the developer put them. Numbers stay as written.
 */
internal object Json {
    fun parse(text: String): Any? = Reader(text).run {
        val value = value()
        skipSpace()
        require(at == text.length) { "unexpected text at offset $at" }
        value
    }

    fun write(value: Any?): String = StringBuilder().also { write(it, value, "") }.append('\n').toString()

    private fun write(out: StringBuilder, value: Any?, indent: String) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Number -> out.append(value)
            is RawNumber -> out.append(value.text)
            is String -> quote(out, value)
            is Map<*, *> -> block(out, value.entries.toList(), indent, '{', '}') { entry, inner ->
                quote(out, entry.key.toString())
                out.append(": ")
                write(out, entry.value, inner)
            }
            is Iterable<*> -> block(out, value.toList(), indent, '[', ']') { item, inner -> write(out, item, inner) }
            else -> quote(out, value.toString())
        }
    }

    private fun <T> block(
        out: StringBuilder,
        items: List<T>,
        indent: String,
        open: Char,
        close: Char,
        each: (T, String) -> Unit,
    ) {
        if (items.isEmpty()) {
            out.append(open).append(close)
            return
        }
        val inner = "$indent  "
        out.append(open).append('\n')
        items.forEachIndexed { index, item ->
            out.append(inner)
            each(item, inner)
            if (index < items.lastIndex) out.append(',')
            out.append('\n')
        }
        out.append(indent).append(close)
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        text.forEach { c ->
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    /** A number kept exactly as the file spelled it. */
    data class RawNumber(val text: String)

    private class Reader(val text: String) {
        var at = 0

        fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        fun value(): Any? {
            skipSpace()
            require(at < text.length) { "unexpected end of JSON" }
            return when (val c = text[at]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at offset $at")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "unexpected text at offset $at" }
            at += word.length
            return value
        }

        private fun number(): RawNumber {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return RawNumber(text.substring(start, at))
        }

        private fun obj(): LinkedHashMap<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            at++
            skipSpace()
            if (text.getOrNull(at) == '}') return map.also { at++ }
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                require(text.getOrNull(at) == ':') { "expected ':' at offset $at" }
                at++
                map[key] = value()
                skipSpace()
                when (text.getOrNull(at)) {
                    ',' -> at++
                    '}' -> return map.also { at++ }
                    else -> error("expected ',' or '}' at offset $at")
                }
            }
        }

        private fun array(): MutableList<Any?> {
            val list = mutableListOf<Any?>()
            at++
            skipSpace()
            if (text.getOrNull(at) == ']') return list.also { at++ }
            while (true) {
                list += value()
                skipSpace()
                when (text.getOrNull(at)) {
                    ',' -> at++
                    ']' -> return list.also { at++ }
                    else -> error("expected ',' or ']' at offset $at")
                }
            }
        }

        private fun string(): String {
            require(text.getOrNull(at) == '"') { "expected a string at offset $at" }
            at++
            val out = StringBuilder()
            while (true) {
                require(at < text.length) { "unterminated string" }
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        when (val escaped = text[at++]) {
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                out.append(text.substring(at, at + 4).toInt(16).toChar())
                                at += 4
                            }
                            else -> out.append(escaped)
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}
