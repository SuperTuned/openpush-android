package ai.openpush.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [OpenPushBridge] hands out the SDK key, the device credential and authenticated requests, so it is
 * not public API: a module that uses it without opting in does not compile, and Java code, which no
 * opt-in can stop, cannot see the members that carry the credentials.
 */
class OpenPushInternalApiTests {
    @Test fun usingTheBridgeWithoutOptingInIsACompileError() {
        val result = KotlinFixtureCompiler.compile(
            """
            package fixture

            import ai.openpush.android.OpenPushBridge

            fun leak(): Map<String, String> = OpenPushBridge.requestHeaders()
            """.trimIndent(),
        )

        assertFalse("a module reached the bridge without opting in:\n${result.output}", result.compiled)
        assertTrue(
            result.output,
            result.output.contains("internal to the OpenPush modules, not public API", ignoreCase = true),
        )
    }

    @Test fun anOptedInModuleStillCompiles() {
        val result = KotlinFixtureCompiler.compile(
            """
            @file:OptIn(ai.openpush.android.OpenPushInternalApi::class)

            package fixture

            import ai.openpush.android.OpenPushBridge

            fun headers(): Map<String, String> = OpenPushBridge.requestHeaders()

            suspend fun fetch() = OpenPushBridge.request("GET", "/v1/ping")
            """.trimIndent(),
        )

        assertTrue(result.output, result.compiled)
    }

    @Test fun theCredentialMembersAreHiddenFromJava() {
        val bridge = Class.forName("ai.openpush.android.OpenPushBridge", false, javaClass.classLoader)
        val credentialed = bridge.declaredMethods.filter { it.name == "requestHeaders" || it.name == "request" }

        assertTrue(credentialed.isNotEmpty())
        credentialed.forEach { method ->
            assertTrue(
                "${method.name} is callable from Java",
                method.isSynthetic || !Modifier.isPublic(method.modifiers),
            )
        }
    }
}
