package ai.openpush.android

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files

/**
 * Compiles a Kotlin source against this module's test classpath, the way a host app's module would
 * see the SDK. For API checks whose point is that something does **not** compile.
 */
internal object KotlinFixtureCompiler {
    class Result(val exitCode: ExitCode, val output: String) {
        val compiled: Boolean get() = exitCode == ExitCode.OK
    }

    fun compile(source: String): Result {
        val dir = Files.createTempDirectory("openpush-fixture").toFile()
        try {
            val file = File(dir, "Fixture.kt").apply { writeText(source) }
            val out = File(dir, "out").apply { mkdirs() }
            val output = ByteArrayOutputStream()
            val exit = K2JVMCompiler().exec(
                PrintStream(output, true, "UTF-8"),
                file.path,
                "-d", out.path,
                "-classpath", System.getProperty("java.class.path"),
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target", "11",
            )
            return Result(exit, output.toString("UTF-8"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
