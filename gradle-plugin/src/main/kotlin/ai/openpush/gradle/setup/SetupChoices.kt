package ai.openpush.gradle.setup

/** Asks the developer one question; null when there is no one to ask. */
internal interface ChoicePrompter {
    fun select(question: String, options: List<String>, default: String?): String?
}

/** A choice `openpushSetup` needs, with its Reuse default when a safe one exists. */
internal data class SetupQuestion(
    val name: String,
    val question: String,
    val options: List<String>,
    val default: String?,
    /** The `-P` property that answers it without a prompt. */
    val property: String,
    val answer: (String) -> Map<String, Any?>,
)

/**
 * Decides which choices `openpushSetup` needs from what the doctor found, and answers them: a stored
 * answer is kept, a `-Popenpush.<choice>` property wins, then a prompt, then — with `-Popenpush.yes=true` —
 * the Reuse default. What is still unanswered is returned so the task can fail and name it.
 */
internal class SetupChoices(
    private val found: List<Found>,
    private val stored: Map<String, Map<String, Any?>>,
    private val properties: OpenPushProperties,
) {
    data class Result(val answers: Map<String, Map<String, Any?>>, val unanswered: List<SetupQuestion>)

    fun resolve(overrides: Map<String, String>, prompter: ChoicePrompter?, acceptDefaults: Boolean): Result {
        val answers = linkedMapOf<String, Map<String, Any?>>()
        val unanswered = mutableListOf<SetupQuestion>()
        listOfNotNull(firebase(), delivery(), icons()).forEach { question ->
            val override = overrides[question.property]
            val choice = when {
                override != null -> override
                question.name in stored -> return@forEach
                else -> prompter?.select(question.question, question.options, question.default)
                    ?: question.default?.takeIf { acceptDefaults }
            }
            if (choice == null || choice !in question.options) {
                unanswered += question
            } else {
                answers[question.name] = question.answer(choice)
            }
        }
        return Result(answers, unanswered)
    }

    private fun firebase(): SetupQuestion? {
        val app = found.firstOrNull { it.kind == "firebase_app" && it.details["config"] != null }
        val options = if (app != null) listOf("reuse", "new") else listOf("new")
        if (app == null && properties.firebase == null) return null
        return SetupQuestion(
            name = SetupFile.FIREBASE,
            question = if (app != null) {
                "Firebase: reuse the app's Firebase project ${app.id ?: ""} (${app.source}), or a new one from " +
                    "the ai.openpush.fcm.* values in ${OpenPushProperties.NAME}?"
            } else {
                "Firebase: no Firebase configuration found; use the ai.openpush.fcm.* values in " +
                    "${OpenPushProperties.NAME}?"
            },
            options = options,
            default = options.first(),
            property = "openpush.firebase",
            answer = { mapOf("mode" to it) },
        )
    }

    private fun delivery(): SetupQuestion? {
        val services = found.filter { it.kind == "fcm_service" && it.details["enabled"] != false }
        if (services.isEmpty()) return null
        val editable = services.filter { it.editable }
        // Forward needs one editable service to forward from; anything else is the developer's call.
        val forwardService = editable.singleOrNull()?.id
        val safeDefault = if (forwardService != null && services.size == 1) "forward" else null
        // "forward" alone names the one editable service; "forward:<class>" names any editable one.
        val forwardOptions = listOfNotNull("forward".takeIf { forwardService != null }) +
            editable.mapNotNull { it.id }.map { "forward:$it" }
        return SetupQuestion(
            name = SetupFile.ANDROID_DELIVERY,
            question = "Push delivery: ${services.joinToString { it.id.orEmpty() }} already receive(s) " +
                "${PushPlumbingScanner.MESSAGING_EVENT}. Forward OpenPush pushes from your service, " +
                "or let OpenPush's " +
                "service receive them (automatic, removes the others)?",
            options = forwardOptions + "automatic",
            default = safeDefault,
            property = "openpush.android_delivery",
            answer = { choice ->
                if (choice == "automatic") {
                    linkedMapOf("mode" to "automatic", "remove" to services.mapNotNull { it.id })
                } else {
                    val service = choice.substringAfter("forward:", forwardService.orEmpty())
                    // Every other receiver goes, from the app's sources or a dependency alike.
                    val remove = services.mapNotNull { it.id }.filter { it != service }
                    linkedMapOf("mode" to "forward", "service" to service, "remove" to remove)
                }
            },
        )
    }

    private fun icons(): SetupQuestion? {
        val defaultIcon = found.firstOrNull { it.kind == "notification_icon" && it.details["meta_data"] != null }
        val icon = defaultIcon ?: found.firstOrNull { it.kind == "notification_icon" }
        val channel = found.firstOrNull { it.kind == "notification_channel" }
        if (icon == null && channel == null) return null
        val existing = listOfNotNull(icon?.id?.let { "icon $it" }, channel?.id?.let { "channel $it" })
            .joinToString(" and ")
        return SetupQuestion(
            name = SetupFile.ICONS,
            question = "Notifications: reuse the existing $existing, or OpenPush's defaults (new)?",
            options = listOf("reuse", "new"),
            default = "reuse",
            property = "openpush.icons",
            answer = { choice ->
                if (choice == "reuse") {
                    val small = icon?.let { it.details["resource"] as? String ?: it.id?.let { id -> "@drawable/$id" } }
                    linkedMapOf("mode" to "reuse", "small" to small, "large" to null, "channel" to channel?.id)
                } else {
                    linkedMapOf("mode" to "new", "small" to null, "large" to null)
                }
            },
        )
    }
}
