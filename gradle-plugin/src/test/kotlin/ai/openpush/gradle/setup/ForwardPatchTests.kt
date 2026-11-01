package ai.openpush.gradle.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardPatchTests {
    @Test fun kotlinGainsTheBranchTheTokenForwardAndImportsAndNothingElseChanges() {
        val before = """
            package com.example

            import com.google.firebase.messaging.FirebaseMessagingService
            import com.google.firebase.messaging.RemoteMessage

            class Service : FirebaseMessagingService() {
                override fun onMessageReceived(msg: RemoteMessage) {
                    chat(msg)
                }
            }
        """.trimIndent()

        val after = (ForwardPatch.apply(before, kotlin = true) as ForwardPatch.Result.Patched).text

        assertEquals(
            """
            package com.example

            import com.google.firebase.messaging.FirebaseMessagingService
            import com.google.firebase.messaging.RemoteMessage
            import ai.openpush.android.OpenPush
            import ai.openpush.android.fcm.OpenPushMessagingService

            class Service : FirebaseMessagingService() {
                override fun onNewToken(token: String) {
                    super.onNewToken(token)
                    OpenPush.register(token)
                }

                override fun onMessageReceived(msg: RemoteMessage) {
                    if (OpenPush.messageId(msg.data) != null) {
                        OpenPushMessagingService.handle(this, msg)
                        return
                    }
                    chat(msg)
                }
            }
            """.trimIndent(),
            after,
        )
        assertEquals(ForwardPatch.Result.AlreadyForwards, ForwardPatch.apply(after, kotlin = true))
    }

    @Test fun javaUsesAnExistingOnNewToken() {
        val before = """
            package com.example;

            public class Service extends FirebaseMessagingService {
                @Override
                public void onMessageReceived(final RemoteMessage message) {
                    chat(message);
                }

                @Override
                public void onNewToken(String t) {
                    save(t);
                }
            }
        """.trimIndent()

        val after = (ForwardPatch.apply(before, kotlin = false) as ForwardPatch.Result.Patched).text

        assertTrue(after.contains("        if (OpenPush.messageId(message.getData()) != null) {\n"))
        assertTrue(after.contains("        OpenPush.register(t);\n        save(t);"))
        assertTrue(after.contains("package com.example;\n\nimport ai.openpush.android.OpenPush;"))
    }

    @Test fun anExpressionBodyHasNoAnchor() {
        val source = "class S { override fun onMessageReceived(m: RemoteMessage) = Unit }"
        val result = ForwardPatch.apply(source, kotlin = true)

        assertTrue(result is ForwardPatch.Result.NoAnchor)
    }

    @Test fun theDiffShowsOnlyTheInsertedLines() {
        val diff = UnifiedDiff.of("S.kt", "a\nb\nc\nd\ne\nf\ng\nh\ni", "a\nb\nc\nd\nX\ne\nf\ng\nh\ni")

        assertEquals("--- a/S.kt\n+++ b/S.kt\n@@ -2,6 +2,7 @@\n b\n c\n d\n+X\n e\n f\n g\n", diff)
    }

    @Test fun crlfFilesStayCrlf() {
        val before = "class S : FirebaseMessagingService() {\r\n" +
            "    override fun onMessageReceived(m: RemoteMessage) {\r\n" +
            "        chat(m)\r\n" +
            "    }\r\n" +
            "}\r\n"

        val after = (ForwardPatch.apply(before, kotlin = true) as ForwardPatch.Result.Patched).text

        assertEquals(after.count { it == '\n' }, after.split("\r\n").size - 1)
        assertTrue(after.contains("OpenPushMessagingService.handle(this, m)\r\n"))
    }

    @Test fun aNewOnNewTokenGoesAboveTheKdocAndAnnotationsOfTheMethodItPrecedes() {
        val before = """
            class S : FirebaseMessagingService() {
                /**
                 * Routes chat pushes.
                 */
                @WorkerThread
                override fun onMessageReceived(m: RemoteMessage) {
                    chat(m)
                }
            }
        """.trimIndent()

        val after = (ForwardPatch.apply(before, kotlin = true) as ForwardPatch.Result.Patched).text

        assertTrue(
            after.contains(
                "        OpenPush.register(token)\n    }\n\n    /**\n     * Routes chat pushes.\n     */\n    @WorkerThread\n",
            ),
        )
    }
}
