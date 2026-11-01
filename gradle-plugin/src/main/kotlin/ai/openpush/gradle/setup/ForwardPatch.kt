package ai.openpush.gradle.setup

/**
 * The Forward edit to a host's own `FirebaseMessagingService`: OpenPush pushes go to
 * `OpenPushMessagingService.handle` before the host's code runs, and new tokens reach
 * `OpenPush.register`. Lines are only inserted, at anchors that must be found; nothing is rewritten.
 */
internal object ForwardPatch {
    sealed interface Result {
        data class Patched(val text: String) : Result

        data object AlreadyForwards : Result

        data class NoAnchor(val reason: String) : Result
    }

    private const val HANDLE = "OpenPushMessagingService.handle"
    private val IMPORTS = listOf("ai.openpush.android.OpenPush", "ai.openpush.android.fcm.OpenPushMessagingService")

    fun apply(source: String, kotlin: Boolean): Result {
        if (HANDLE in source) return Result.AlreadyForwards
        // Edit with \n, then write back the file's own line separator.
        val separator = if ("\r\n" in source) "\r\n" else "\n"
        val lines = source.replace("\r\n", "\n").lines().toMutableList()
        val receive = if (kotlin) KOTLIN_RECEIVE else JAVA_RECEIVE
        val token = if (kotlin) KOTLIN_TOKEN else JAVA_TOKEN

        val receiveAt = lines.indexOfFirst { receive.containsMatchIn(it) && it.trimEnd().endsWith("{") }
            .takeIf { it >= 0 }
            ?: return Result.NoAnchor("no block-bodied onMessageReceived(…) { in the service")
        val message = receive.find(lines[receiveAt])!!.groupValues[1]
        val receiveIndent = indentOf(lines[receiveAt]) + "    "
        val forward = if (kotlin) {
            "${receiveIndent}if (OpenPush.messageId($message.data) != null) {\n" +
                "$receiveIndent    OpenPushMessagingService.handle(this, $message)\n" +
                "$receiveIndent    return\n" +
                "$receiveIndent}"
        } else {
            "${receiveIndent}if (OpenPush.messageId($message.getData()) != null) {\n" +
                "$receiveIndent    OpenPushMessagingService.handle(this, $message);\n" +
                "$receiveIndent    return;\n" +
                "$receiveIndent}"
        }
        lines.add(receiveAt + 1, forward)

        val tokenAt = lines.indexOfFirst { token.containsMatchIn(it) && it.trimEnd().endsWith("{") }
        if (tokenAt >= 0) {
            val name = token.find(lines[tokenAt])!!.groupValues[1]
            val indent = indentOf(lines[tokenAt]) + "    "
            lines.add(tokenAt + 1, "${indent}OpenPush.register($name)" + if (kotlin) "" else ";")
        } else {
            // No onNewToken yet: add one beside onMessageReceived, at the same indent.
            val indent = indentOf(lines[receiveAt])
            val method = if (kotlin) {
                "${indent}override fun onNewToken(token: String) {\n" +
                    "$indent    super.onNewToken(token)\n" +
                    "$indent    OpenPush.register(token)\n" +
                    "$indent}\n"
            } else {
                "$indent@Override\n" +
                    "${indent}public void onNewToken(String token) {\n" +
                    "$indent    super.onNewToken(token);\n" +
                    "$indent    OpenPush.register(token);\n" +
                    "$indent}\n"
            }
            lines.add(declarationStart(lines, receiveAt), method)
        }

        addImports(lines, kotlin)
        return Result.Patched(lines.joinToString("\n").replace("\n", separator))
    }

    /** The first line of the declaration at [at]: above its annotations and its KDoc or comments. */
    private fun declarationStart(lines: List<String>, at: Int): Int {
        var start = at
        var inComment = false
        while (start > 0) {
            val line = lines[start - 1].trim()
            when {
                inComment -> inComment = !(line.startsWith("/*"))
                line.endsWith("*/") -> inComment = !line.startsWith("/*")
                line.startsWith("@") || line.startsWith("//") -> Unit
                else -> return start
            }
            start--
        }
        return start
    }

    private fun addImports(lines: MutableList<String>, kotlin: Boolean) {
        val missing = IMPORTS.filter { name -> lines.none { it.trim().removeSuffix(";") == "import $name" } }
        if (missing.isEmpty()) return
        val end = if (kotlin) "" else ";"
        val lastImport = lines.indexOfLast { it.startsWith("import ") }
        val at = if (lastImport >= 0) lastImport + 1 else lines.indexOfFirst { it.startsWith("package ") } + 1
        val block = missing.map { "import $it$end" }
        lines.addAll(at, if (lastImport >= 0) block else listOf("") + block)
    }

    private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

    private val KOTLIN_RECEIVE = Regex("""override\s+fun\s+onMessageReceived\s*\(\s*(\w+)\s*:\s*RemoteMessage\s*\)""")
    private val JAVA_RECEIVE =
        Regex("""void\s+onMessageReceived\s*\(\s*(?:final\s+)?(?:@\w+\s+)*RemoteMessage\s+(\w+)\s*\)""")
    private val KOTLIN_TOKEN = Regex("""override\s+fun\s+onNewToken\s*\(\s*(\w+)\s*:\s*String\s*\)""")
    private val JAVA_TOKEN = Regex("""void\s+onNewToken\s*\(\s*(?:final\s+)?(?:@\w+\s+)*String\s+(\w+)\s*\)""")
}

/** A unified diff of two texts, by lines, with three lines of context. */
internal object UnifiedDiff {
    fun of(path: String, before: String, after: String): String {
        val a = before.lines()
        val b = after.lines()
        // Longest common subsequence table; service sources are small.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        data class Line(val tag: Char, val text: String, val aLine: Int, val bLine: Int)
        val script = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> script += Line(' ', a[i], i++, j++)
                j < b.size && (i == a.size || lcs[i][j + 1] >= lcs[i + 1][j]) -> script += Line('+', b[j], i, j++)
                else -> script += Line('-', a[i], i++, j)
            }
        }
        val out = StringBuilder("--- a/$path\n+++ b/$path\n")
        val changes = script.indices.filter { script[it].tag != ' ' }
        // Changes closer than twice the context share a hunk.
        val groups = mutableListOf<MutableList<Int>>()
        changes.forEach { index ->
            if (groups.isNotEmpty() && index - groups.last().last() <= 2 * CONTEXT) {
                groups.last() += index
            } else {
                groups += mutableListOf(index)
            }
        }
        groups.forEach { group ->
            val hunk = script.subList(maxOf(0, group.first() - CONTEXT), minOf(script.size, group.last() + 1 + CONTEXT))
            val aCount = hunk.count { it.tag != '+' }
            val bCount = hunk.count { it.tag != '-' }
            out.append("@@ -${hunk.first().aLine + 1},$aCount +${hunk.first().bLine + 1},$bCount @@\n")
            hunk.forEach { out.append(it.tag).append(it.text).append('\n') }
        }
        return out.toString()
    }

    private const val CONTEXT = 3
}
