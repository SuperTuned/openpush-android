package ai.openpush.gradle.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gradle TestKit runs of `openpushSetup`, then real builds that apply the recorded choices. */
class OpenPushSetupFixtureTests {
    @get:Rule val scratch = TemporaryFolder()

    private fun fixture(name: String) = FixtureProject(name, scratch.root)

    @Suppress("UNCHECKED_CAST")
    private fun FixtureProject.choices() = setup()["choices"] as Map<String, Map<String, Any?>>

    @Test fun withNoOneToAskAMissingChoiceFailsAndNamesIt() {
        val project = fixture("own-service")

        val result = project.fail(":app:openpushSetup")

        assertTrue(result.output.contains("android_delivery"))
        assertTrue(
            result.output.contains(
                "-Popenpush.android_delivery=<forward|forward:com.example.fixture.GameMessagingService|automatic>",
            ),
        )
        assertTrue(result.output.contains("-Popenpush.yes=true for forward"))
        assertTrue(result.output.contains("-Popenpush.icons=<reuse|new>"))
        // Findings are still recorded; no answer is invented.
        assertEquals(emptyMap<String, Any>(), project.choices())
        assertFalse(project.found().isEmpty())
    }

    @Test fun reuseDefaultsForwardAndReuseTheIconAndChannelWithoutTouchingSources() {
        val project = fixture("own-service")
        val source = project.app.resolve("src/main/java/com/example/fixture/GameMessagingService.kt")
        val before = source.readText()

        project.build(":app:openpushSetup", "-Popenpush.yes=true")

        val choices = project.choices()
        assertEquals(
            mapOf(
                "mode" to "forward",
                "service" to "com.example.fixture.GameMessagingService",
                "remove" to emptyList<String>(),
            ),
            choices["android_delivery"],
        )
        assertEquals("reuse", choices["icons"]?.get("mode"))
        assertEquals("@drawable/ic_stat_game", choices["icons"]?.get("small"))
        assertEquals("game_news", choices["icons"]?.get("channel"))
        assertEquals(before, source.readText())
        val patch = project.app.resolve("build/openpush/forward.patch").readText()
        assertTrue(patch.contains("+        if (OpenPush.messageId(message.data) != null) {"))
        assertTrue(patch.contains("+import ai.openpush.android.fcm.OpenPushMessagingService"))

        project.build(":app:assembleDebug")

        val merged = project.mergedManifest()
        assertTrue(merged.contains("android:name=\"ai.openpush.fcm.delivery\""))
        assertTrue(merged.contains("android:value=\"forward\""))
        assertTrue(merged.contains("android:name=\"ai.openpush.notification_icon\""))
        assertTrue(merged.contains("android:resource=\"@drawable/ic_stat_game\""))
        assertTrue(merged.contains("android:value=\"game_news\""))
        // The developer's own manifest is never edited.
        assertFalse(project.app.resolve("src/main/AndroidManifest.xml").readText().contains("ai.openpush"))
    }

    @Test fun forwardWithApplyPatchEditsTheJavaServiceAndTheAppBuildsAgainstTheSdk() {
        val project = fixture("forward-java")

        project.build(":app:openpushSetup", "-Popenpush.yes=true", "-Popenpush.applyPatch=true")

        val source = project.app.resolve("src/main/java/com/example/fixture/GameMessagingService.java").readText()
        assertTrue(source.contains("OpenPushMessagingService.handle(this, message);"))
        assertTrue(source.contains("OpenPush.register(token);"))
        assertTrue(source.contains("import ai.openpush.android.OpenPush;"))

        project.build(":app:assembleDebug")

        val merged = project.mergedManifest()
        assertTrue(merged.contains("android:value=\"fixture-app\""))
        assertTrue(merged.contains("android:value=\"fixture-sdk-key\""))
        assertTrue(merged.contains("android:value=\"forward\""))
        assertTrue(merged.contains("com.example.fixture.GameMessagingService"))
        assertTrue(merged.contains(PushPlumbingScanner.OPENPUSH_SERVICE))

        // A second run finds the service already forwarding and changes nothing.
        project.build(":app:openpushSetup", "-Popenpush.applyPatch=true")
        val again = project.app.resolve("src/main/java/com/example/fixture/GameMessagingService.java").readText()
        assertEquals(source, again)
    }

    @Test fun automaticRemovesTheOtherServiceThroughTheGeneratedManifest() {
        val project = fixture("two-services")

        project.build(":app:openpushSetup", "-Popenpush.android_delivery=automatic")

        assertEquals(
            mapOf("mode" to "automatic", "remove" to listOf("com.example.fixture.GameMessagingService")),
            project.choices()["android_delivery"],
        )
        project.build(":app:assembleDebug")

        val merged = project.mergedManifest()
        assertFalse(merged.contains("com.example.fixture.GameMessagingService"))
        assertTrue(merged.contains(PushPlumbingScanner.OPENPUSH_SERVICE))
        assertTrue(merged.contains("android:value=\"automatic\""))
    }

    @Test fun firebaseNewWritesTheThreeValuesAndReuseWritesNone() {
        val fresh = fixture("plain")
        fresh.app.resolve(OpenPushProperties.NAME).writeText(
            """
            ai.openpush.fcm.project_id=fixture-new
            ai.openpush.fcm.application_id=1:123456789012:android:00ff
            ai.openpush.fcm.api_key=AIzaNewKey
            """.trimIndent(),
        )
        fresh.build(":app:openpushSetup", "-Popenpush.yes=true")
        assertEquals(mapOf("mode" to "new"), fresh.choices()["firebase"])
        fresh.build(":app:assembleDebug")
        val merged = fresh.mergedManifest()
        assertTrue(merged.contains("android:value=\"fixture-new\""))
        assertTrue(merged.contains("android:value=\"AIzaNewKey\""))

        val existing = fixture("variant-google-services")
        existing.build(":app:openpushSetup", "-Popenpush.yes=true")
        assertEquals(mapOf("mode" to "reuse"), existing.choices()["firebase"])
        existing.build(":app:processDebugManifest")
        assertFalse(existing.mergedManifest().contains("ai.openpush.fcm.project_id"))
    }

    @Test fun forwardRemovesAServiceMergedFromADependencyAndAMismatchedChoiceFailsTheBuild() {
        val project = fixture("service-from-dependency")
        val vendor = "com.example.vendor.VendorMessagingService"

        val doctor = project.fail(":app:openpushDoctor")
        assertTrue(doctor.output.contains(vendor))
        assertEquals(false, project.found().single { it["id"] == vendor }["editable"])

        // Two candidates, one from a dependency: no default to take.
        val yes = project.fail(":app:openpushSetup", "-Popenpush.yes=true")
        assertTrue(
            yes.output.contains(
                "-Popenpush.android_delivery=<forward|forward:com.example.fixture.GameMessagingService|automatic>",
            ),
        )

        // A recorded Forward that leaves the dependency's service in place still splits delivery.
        project.setupFile.writeText(
            """{"choices": {"android_delivery": {"mode": "forward", "service": "com.example.fixture.GameMessagingService"}}}""",
        )
        val mismatch = project.fail(":app:processDebugManifestForPackage")
        assertTrue(mismatch.output.contains("records Forward to com.example.fixture.GameMessagingService"))

        project.build(":app:openpushSetup", "-Popenpush.android_delivery=forward")
        assertEquals(listOf(vendor), project.choices()["android_delivery"]?.get("remove"))
        project.build(":app:assembleDebug")

        val merged = project.mergedManifest()
        assertFalse(merged.contains(vendor))
        assertTrue(merged.contains("com.example.fixture.GameMessagingService"))
    }

    @Test fun anAutomaticChoiceThatLeavesAnotherServiceFailsTheBuild() {
        val project = fixture("two-services")
        project.setupFile.writeText("""{"choices": {"android_delivery": {"mode": "automatic", "remove": []}}}""")

        val result = project.fail(":app:processDebugManifestForPackage")

        assertTrue(result.output.contains("records Automatic"))
    }

    @Test fun setupInTheSameBuildAsAnAssembleStillFailsAConflictingAssemble() {
        val project = fixture("two-services")

        val result = project.fail(":app:openpushSetup", "-Popenpush.yes=true", ":app:assembleDebug", "--continue")

        assertTrue(result.output.contains("Execution failed for task ':app:openpushCheckDebug'"))
    }

    @Test fun aMipmapIconIsReusedAsAMipmap() {
        val project = fixture("mipmap-icon")

        project.build(":app:openpushSetup", "-Popenpush.yes=true")
        assertEquals("@mipmap/ic_launcher", project.choices()["icons"]?.get("small"))
        project.build(":app:assembleDebug")

        val merged = project.mergedManifest()
        val meta = merged.substringAfter("android:name=\"ai.openpush.notification_icon\"").substringBefore("/>")
        assertTrue(meta.contains("android:resource=\"@mipmap/ic_launcher\""))
    }

    @Test fun generatedSourcesThatReadTheManifestDoNotMakeACycle() {
        val project = fixture("generated-source")

        project.build(":app:openpushDoctor", ":app:assembleDebug")
    }
}
