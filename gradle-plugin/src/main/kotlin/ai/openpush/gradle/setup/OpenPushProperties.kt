package ai.openpush.gradle.setup

import java.io.StringReader
import java.util.Properties

/**
 * `openpush.properties` beside the app module's build file: the console's keys, and the Firebase
 * values a Create new Firebase choice uses. Keep it out of version control when the keys are private.
 */
internal class OpenPushProperties(private val values: Map<String, String>) {
    val appId: String? get() = values[APP_ID]
    val sdkKey: String? get() = values[SDK_KEY]

    /** The three `ai.openpush.fcm.*` values, when all of them are set. */
    val firebase: Map<String, String>?
        get() = FIREBASE_KEYS.associateWith { values[it] }
            .takeIf { map -> map.values.all { !it.isNullOrBlank() } }
            ?.mapValues { it.value!! }

    companion object {
        const val NAME = "openpush.properties"
        const val APP_ID = "ai.openpush.app_id"
        const val SDK_KEY = "ai.openpush.sdk_key"
        val FIREBASE_KEYS = listOf(
            "ai.openpush.fcm.project_id",
            "ai.openpush.fcm.application_id",
            "ai.openpush.fcm.api_key",
        )

        fun parse(text: String?): OpenPushProperties {
            val properties = Properties()
            text?.let { properties.load(StringReader(it)) }
            return OpenPushProperties(
                properties.stringPropertyNames().associateWith { properties.getProperty(it).trim() }
                    .filterValues { it.isNotEmpty() },
            )
        }
    }
}
