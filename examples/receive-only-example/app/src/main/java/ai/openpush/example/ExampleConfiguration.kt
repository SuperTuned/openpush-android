package ai.openpush.example

import ai.openpush.android.OpenPush
import ai.openpush.android.OpenPushConfiguration
import android.content.Context

enum class RuntimeMode(val label: String) {
    DEMO("Demo"),
    LIVE("Live"),
}

enum class AppAppearance(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class FeatureDestination(val label: String) {
    STATUS("Status"),
    USER("User"),
    IN_APP("In-app"),
    EVENTS("Events"),
    ACTIVITY("Activity"),
}

enum class NotificationExample(val title: String, val detail: String) {
    SIMPLE("Simple", "Baseline title and body payload"),
    IMAGE("Image", "Expandable feature-announcement image"),
    BUTTON("Button", "Open and Save notification actions"),
    CUSTOM("Custom", "Additional app-defined routing data"),
    SOUND("Sound", "Default Android notification sound"),
    GROUPED("Grouped", "Two messages and an SDK summary"),
}

data class CustomNotificationDraft(
    val title: String,
    val description: String,
    val dataKey: String,
    val dataValue: String,
) {
    fun normalized() = copy(
        title = title.trim(),
        description = description.trim(),
        dataKey = dataKey.trim(),
        dataValue = dataValue.trim(),
    )

    val isValid: Boolean
        get() = normalized().run {
            title.isNotEmpty() &&
                description.isNotEmpty() &&
                dataKey.isNotEmpty() &&
                !isReservedDataKey(dataKey) &&
                dataValue.isNotEmpty()
        }

    companion object {
        const val TITLE_LIMIT = 80
        const val DESCRIPTION_LIMIT = 240
        const val DATA_KEY_LIMIT = 50
        const val DATA_VALUE_LIMIT = 120

        private val reservedDataKeys = setOf(
            "example",
            "openpush_custom",
            "op_action_id",
            "op_message_id",
        )

        fun isReservedDataKey(value: String): Boolean = value.trim() in reservedDataKeys

        val Example = CustomNotificationDraft(
            title = "OpenPush · Custom",
            description = "Open this notification to inspect app-defined payload data.",
            dataKey = "screen",
            dataValue = "order_detail",
        )
    }
}

enum class LabEventKind {
    SDK,
    PERMISSION,
    SUBSCRIPTION,
    FOREGROUND,
    IN_APP,
    CLICK,
    IDENTITY,
    DATA,
    ACTIVITY,
    ERROR,
}

data class LabEvent(val id: Long, val timestamp: Long, val kind: LabEventKind, val title: String, val detail: String)

object ExampleConfiguration {
    val SERVER_URL: String = BuildConfig.OPENPUSH_SERVER_URL.takeUnless(::isPlaceholder)
        ?: OpenPush.DEFAULT_SERVER_URL
    const val APP_ID = BuildConfig.OPENPUSH_APP_ID
    const val SDK_KEY = BuildConfig.OPENPUSH_SDK_KEY
    const val CREDENTIAL_SERVICE = "ai.openpush.example.device"

    /** The value object used by the lower-level attach API, shown here without taking over its seams. */
    val SDK_CONFIGURATION: OpenPushConfiguration
        get() = OpenPushConfiguration(APP_ID, SDK_KEY, SERVER_URL)

    val hasLiveCredentials: Boolean
        get() = !isPlaceholder(APP_ID) && !isPlaceholder(SDK_KEY)

    private fun isPlaceholder(value: String): Boolean = value.isBlank() || value.startsWith("replace-with-")
}

object ExamplePreferences {
    private const val FILE = "openpush_feature_lab"
    private const val APPEARANCE = "appearance"

    fun appearance(context: Context): AppAppearance {
        val value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(APPEARANCE, AppAppearance.SYSTEM.name)
        return runCatching { AppAppearance.valueOf(value.orEmpty()) }
            .getOrDefault(AppAppearance.SYSTEM)
    }

    fun setAppearance(context: Context, appearance: AppAppearance) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(APPEARANCE, appearance.name)
            .apply()
    }
}

/** The last push [ExampleMessagingService] received, so the Status workspace can show it on a device test. */
object ExampleLastPush {
    private const val FILE = "openpush_feature_lab"
    private const val MESSAGE_ID = "last_push_message_id"
    private const val TITLE = "last_push_title"

    fun record(context: Context, messageId: String?, title: String?) {
        preferences(context).edit().putString(MESSAGE_ID, messageId).putString(TITLE, title).apply()
    }

    fun messageId(context: Context): String? = preferences(context).getString(MESSAGE_ID, null)

    fun title(context: Context): String? = preferences(context).getString(TITLE, null)

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

object ExampleConsentGate {
    private const val FILE = "openpush_feature_lab"
    private const val REQUIRED = "consent_required"
    private const val GRANTED = "consent_granted"

    fun isRequired(context: Context): Boolean = preferences(context).getBoolean(REQUIRED, false)

    fun isGranted(context: Context): Boolean = preferences(context).getBoolean(GRANTED, false)

    fun permitsSdkStart(context: Context): Boolean = !isRequired(context) || isGranted(context)

    fun setRequired(context: Context, required: Boolean) {
        preferences(context).edit().apply {
            putBoolean(REQUIRED, required)
            if (required) putBoolean(GRANTED, false)
        }.apply()
    }

    fun setGranted(context: Context, granted: Boolean) {
        preferences(context).edit().putBoolean(GRANTED, granted).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

object ExampleEventRelay {
    private const val MAX_BACKLOG = 40
    private val backlog = ArrayDeque<LabEvent>()
    private var sink: ((LabEvent) -> Unit)? = null
    private var nextId = 1L

    @Synchronized
    fun emit(kind: LabEventKind, title: String, detail: String) {
        val event = LabEvent(nextId++, System.currentTimeMillis(), kind, title, detail)
        val target = sink
        if (target == null) {
            backlog.addLast(event)
            while (backlog.size > MAX_BACKLOG) backlog.removeFirst()
        } else {
            target(event)
        }
    }

    fun attach(target: (LabEvent) -> Unit) {
        val pending = synchronized(this) {
            sink = target
            backlog.toList().also { backlog.clear() }
        }
        pending.forEach(target)
    }

    @Synchronized
    fun detach() {
        sink = null
    }
}
