package ai.openpush.gradle.setup

import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Gradle TestKit runs of `openpushDoctor` against real AGP builds of the fixture projects. */
class OpenPushDoctorFixtureTests {
    @get:Rule val scratch = TemporaryFolder()

    private fun fixture(name: String) = FixtureProject(name, scratch.root)

    private fun List<Map<String, Any?>>.ofKind(kind: String) = filter { it["kind"] == kind }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.details() = this["details"] as Map<String, Any?>

    @Test fun aPlainAppHasNothingToReport() {
        val project = fixture("plain")

        val result = project.build(":app:openpushDoctor")

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:openpushDoctorDebug")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:openpushDoctorRelease")?.outcome)
        val setup = project.setup()
        assertEquals("openpush-setup/1", setup["schema"])
        assertEquals("android", setup["platform"])
        assertEquals(emptyList<Any>(), setup["found"])
        assertEquals(emptyMap<String, Any>(), setup["choices"])
    }

    @Test fun theAppsOwnServiceIsEditableAndFirebasesFallbackIsNotPlumbing() {
        val project = fixture("own-service")

        project.build(":app:openpushDoctor")

        val found = project.found()
        val service = found.ofKind("fcm_service").single()
        assertEquals("com.example.fixture.GameMessagingService", service["id"])
        assertEquals("src/main/java/com/example/fixture/GameMessagingService.kt", service["source"])
        assertEquals(true, service["editable"])
        assertNull(service["vendor"])
        // Firebase's own receiver and fallback service come with firebase-messaging; they are not plumbing.
        assertTrue(found.ofKind("fcm_receiver").isEmpty())

        val firebase = found.ofKind("firebase_app").single()
        assertEquals("33.7.0", firebase.details()["firebase_bom"])
        assertTrue((firebase.details()["firebase_messaging"] as String).isNotEmpty())
        assertNull(firebase.details()["config"])

        val icons = found.ofKind("notification_icon")
        assertTrue(icons.any { it["id"] == "ic_stat_game" && it.details()["meta_data"] != null })
        assertTrue(icons.any { it["id"] == "ic_stat_game" && it["source"] == "src/main/res/drawable/ic_stat_game.xml" })
        assertEquals("game_news", found.ofKind("notification_channel").single()["id"])
    }

    @Test fun aReceiverSdkIsFoundAndNamedByItsFingerprint() {
        val project = fixture("receiver-sdk")

        project.build(":app:openpushDoctor")

        val found = project.found()
        val vendor = OneSignalFingerprint.vendor
        val receiver = found.ofKind("fcm_receiver").single()
        assertEquals(vendor, receiver["vendor"])
        assertEquals(false, receiver["editable"])
        assertEquals(PushPlumbingScanner.C2DM_RECEIVE, receiver.details()["action"])
        assertTrue(found.ofKind("fcm_service").isEmpty())
        val hms = found.ofKind("push_sdk").single { it.details()["provider"] == "hms" }
        assertEquals(vendor, hms["vendor"])
        val icon = found.ofKind("notification_icon").single()
        assertEquals(vendor, icon["vendor"])
        assertEquals(true, icon["editable"])
        assertTrue(found.ofKind("push_sdk").any { it.details()["meta_data"] != null && it["vendor"] == vendor })
    }

    @Test fun twoServicesFailTheBuildUntilADeliveryChoiceIsRecorded() {
        val project = fixture("two-services")

        val doctor = project.fail(":app:openpushDoctor")
        assertTrue(doctor.output.contains("com.example.fixture.GameMessagingService"))
        assertTrue(doctor.output.contains(PushPlumbingScanner.OPENPUSH_SERVICE))
        assertTrue(doctor.output.contains("1. Forward"))
        assertTrue(doctor.output.contains("2. Automatic"))
        // The doctor still records what it found before failing.
        assertEquals(1, project.found().ofKind("fcm_service").size)

        // An ordinary build of the variant fails too: the check sits in the manifest pipeline.
        val build = project.fail(":app:processDebugManifestForPackage")
        assertTrue(build.output.contains("2 services receive com.google.firebase.MESSAGING_EVENT"))

        project.setupFile.writeText(
            """
            {
              "schema": "openpush-setup/1",
              "platform": "android",
              "note": "kept",
              "found": [],
              "choices": {
                "android_delivery": {"mode": "forward", "service": "com.example.fixture.GameMessagingService"},
                "firebase": {"mode": "reuse"}
              }
            }
            """.trimIndent(),
        )
        project.build(":app:processDebugManifestForPackage")
        project.build(":app:openpushDoctor")

        val setup = project.setup()
        assertEquals("kept", setup["note"])
        assertEquals(
            mapOf(
                "android_delivery" to
                    mapOf("mode" to "forward", "service" to "com.example.fixture.GameMessagingService"),
                "firebase" to mapOf("mode" to "reuse"),
            ),
            setup["choices"],
        )
        assertEquals(1, project.found().ofKind("fcm_service").size)
    }

    @Test fun googleServicesInAVariantFolderIsFoundForThatVariantOnly() {
        val project = fixture("variant-google-services")

        project.build(":app:openpushDoctor")

        val firebase = project.found().ofKind("firebase_app").single()
        assertEquals("fixture-debug", firebase["id"])
        assertEquals("src/debug/google-services.json", firebase["source"])
        assertEquals("google-services.json", firebase.details()["config"])
        assertEquals(listOf("debug"), firebase.details()["variants"])
        assertFalse(firebase.details()["google_services_plugin"] as Boolean)
    }
}
