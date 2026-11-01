package ai.openpush.gradle.setup

import org.gradle.api.internal.tasks.userinput.UserInputHandler

/**
 * Prompts through Gradle's own console questions, the ones `gradle init` asks. Gradle answers them
 * with the default when the build is not interactive, so this returns null instead there and in CI:
 * the task then fails and names the missing choice rather than guessing.
 */
internal class GradleChoicePrompter private constructor(private val handler: UserInputHandler) : ChoicePrompter {
    override fun select(question: String, options: List<String>, default: String?): String? =
        handler.askUser { questions -> questions.selectOption(question, options, default ?: options.first()) }.orNull

    companion object {
        /** A prompter when this build has someone to ask, else null. */
        fun forBuild(handler: UserInputHandler?, environment: Map<String, String>): ChoicePrompter? {
            if (handler == null || !environment["CI"].isNullOrBlank()) return null
            // Gradle installs this handler when the client has no interactive console.
            if (handler.javaClass.simpleName == "NonInteractiveUserInputHandler") return null
            return GradleChoicePrompter(handler)
        }
    }
}
