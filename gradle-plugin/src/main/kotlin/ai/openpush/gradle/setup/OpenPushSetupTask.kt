package ai.openpush.gradle.setup

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.tasks.userinput.UserInputHandler
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * `openpushSetup`: runs the doctor, then records a Reuse / Create new answer for every choice the
 * findings raise. It asks on an interactive console; `-Popenpush.yes=true` takes the Reuse defaults;
 * `-Popenpush.<choice>=<answer>` answers one directly. A build with no one to ask and no answer fails
 * and names what is missing. The next build applies the answers through the generated manifest.
 */
@DisableCachingByDefault(because = "Edits project files the developer owns")
abstract class OpenPushSetupTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantReports: ConfigurableFileCollection

    @get:Internal
    abstract val setupFile: RegularFileProperty

    @get:Internal
    abstract val propertiesFile: RegularFileProperty

    @get:Internal
    abstract val moduleDirectory: DirectoryProperty

    @get:Internal
    abstract val patchFile: RegularFileProperty

    /** `openpush.*` project properties: `yes`, `applyPatch` and per-choice answers. */
    @get:Input
    abstract val options: MapProperty<String, String>

    @get:Input
    abstract val ci: Property<Boolean>

    @get:Inject
    abstract val userInput: UserInputHandler

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val found = DoctorReports.read(variantReports.files).found
        val file = SetupFile(setupFile.get().asFile)
        val properties = OpenPushProperties.parse(propertiesFile.get().asFile.takeIf { it.isFile }?.readText())
        val options = options.get()
        val prompter = GradleChoicePrompter.forBuild(
            runCatching { userInput }.getOrNull(),
            if (ci.get()) mapOf("CI" to "true") else emptyMap(),
        )
        val result = SetupChoices(found, file.choices, properties)
            .resolve(options, prompter, acceptDefaults = options["openpush.yes"] == "true")
        file.write(found, result.answers)

        result.answers.forEach { (name, answer) ->
            logger.lifecycle("OpenPush setup: $name = ${Json.write(answer).trim()}")
        }
        if (properties.appId == null || properties.sdkKey == null) {
            logger.warn(
                "OpenPush setup: ${OpenPushProperties.NAME} has no ${OpenPushProperties.APP_ID} / " +
                    "${OpenPushProperties.SDK_KEY}; paste the console's values there, or keep them in your manifest",
            )
        }
        if (result.unanswered.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("OpenPush setup needs an answer for ${result.unanswered.size} choice(s) ")
                    append("and has no one to ask:\n")
                    result.unanswered.forEach { question ->
                        append("  - ${question.name}: ${question.question}\n")
                        append("    answer with -P${question.property}=<${question.options.joinToString("|")}>")
                        question.default?.let { append(", or -Popenpush.yes=true for $it") }
                        append('\n')
                    }
                },
            )
        }
        forwardPatch(SetupFile(setupFile.get().asFile).choices, found, options["openpush.applyPatch"] == "true")
        logger.lifecycle("OpenPush setup: choices written to ${SetupFile.NAME}; the next build applies them.")
    }

    private fun forwardPatch(choices: Map<String, Map<String, Any?>>, found: List<Found>, apply: Boolean) {
        if (SetupFile.choiceMode(choices, SetupFile.ANDROID_DELIVERY) != "forward") return
        val service = choices[SetupFile.ANDROID_DELIVERY]?.get("service") as? String ?: return
        val source = found.firstOrNull { it.kind == "fcm_service" && it.id == service && it.editable }?.source
        if (source == null) {
            logger.warn("OpenPush setup: $service is not in this module's sources; add the forwarding lines by hand")
            return
        }
        val sourceFile = moduleDirectory.get().asFile.resolve(source)
        val before = sourceFile.readText()
        when (val result = ForwardPatch.apply(before, kotlin = sourceFile.extension == "kt")) {
            ForwardPatch.Result.AlreadyForwards ->
                logger.lifecycle("OpenPush setup: $service already forwards to OpenPush")
            is ForwardPatch.Result.NoAnchor -> logger.warn(
                "OpenPush setup: $source was not patched (${result.reason}). At the top of onMessageReceived add " +
                    "`if (OpenPush.messageId(message.data) != null) { " +
                    "OpenPushMessagingService.handle(this, message); " +
                    "return }`, and call OpenPush.register(token) in onNewToken.",
            )
            is ForwardPatch.Result.Patched -> {
                val patch = UnifiedDiff.of(source, before, result.text)
                patchFile.get().asFile.apply { parentFile.mkdirs() }.writeText(patch)
                if (apply) {
                    sourceFile.writeText(result.text)
                    logger.lifecycle("OpenPush setup: applied the forwarding patch to $source")
                } else {
                    logger.lifecycle(
                        "OpenPush setup: forwarding patch for $source written to " +
                            "${patchFile.get().asFile.relativeTo(moduleDirectory.get().asFile)}; " +
                            "apply it, or rerun with -Popenpush.applyPatch=true:\n$patch",
                    )
                }
            }
        }
    }
}
