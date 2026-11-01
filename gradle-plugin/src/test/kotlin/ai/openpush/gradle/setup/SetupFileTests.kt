package ai.openpush.gradle.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SetupFileTests {
    @get:Rule val scratch = TemporaryFolder()

    @Test fun rewritingFoundKeepsChoicesAndUnknownFieldsInPlace() {
        val file = scratch.newFile(SetupFile.NAME)
        file.writeText(
            """
            {
              "schema": "openpush-setup/1",
              "platform": "android",
              "team": {"owner": "games", "count": 3, "ratio": 1.5e2},
              "found": [{"kind": "stale"}],
              "choices": {"android_delivery": {"mode": "automatic"}, "future": [true, null, "a\"b"]}
            }
            """.trimIndent(),
        )

        SetupFile(file).writeFound(listOf(Found("fcm_service", "com.example.S", "src/S.kt", editable = true)))

        val expected = """
            {
              "schema": "openpush-setup/1",
              "platform": "android",
              "team": {
                "owner": "games",
                "count": 3,
                "ratio": 1.5e2
              },
              "found": [
                {
                  "kind": "fcm_service",
                  "id": "com.example.S",
                  "source": "src/S.kt",
                  "vendor": null,
                  "editable": true,
                  "details": {}
                }
              ],
              "choices": {
                "android_delivery": {
                  "mode": "automatic"
                },
                "future": [
                  true,
                  null,
                  "a\"b"
                ]
              }
            }
        """.trimIndent() + "\n"
        assertEquals(expected, file.readText())
        assertEquals("automatic", SetupFile(file).androidDeliveryMode)
    }

    @Test fun aMissingFileStartsTheSchemaWithEmptyChoices() {
        val file = scratch.root.resolve(SetupFile.NAME)

        assertNull(SetupFile(file).androidDeliveryMode)
        SetupFile(file).writeFound(emptyList())

        assertEquals(
            mapOf(
                "schema" to "openpush-setup/1",
                "platform" to "android",
                "found" to emptyList<Any>(),
                "choices" to emptyMap<String, Any>(),
            ),
            Json.parse(file.readText()),
        )
    }

    @Test fun aBlankFileOrAByteOrderMarkIsNotAnError() {
        val blank = scratch.newFile("blank.json").apply { writeText("  \n") }
        assertNull(SetupFile(blank).androidDeliveryMode)

        val bom = scratch.newFile("bom.json").apply {
            writeText("\uFEFF{\"choices\": {\"android_delivery\": {\"mode\": \"forward\"}}}")
        }
        assertEquals("forward", SetupFile(bom).androidDeliveryMode)
        assertEquals("forward", SetupFile.choicesOf(bom.readText())["android_delivery"]?.get("mode"))
        assertEquals(emptyMap<String, Any>(), SetupFile.choicesOf(""))
    }
}
