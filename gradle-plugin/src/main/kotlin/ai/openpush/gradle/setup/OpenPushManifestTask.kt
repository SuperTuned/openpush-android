package ai.openpush.gradle.setup

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** `openpushManifest<Variant>`: writes the [ManifestFragment] AGP merges into the variant. */
@CacheableTask
abstract class OpenPushManifestTask : DefaultTask() {
    /** The fragment's XML, computed from the setup file and `openpush.properties` at configuration. */
    @get:Input
    abstract val content: Property<String>

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun run() {
        manifest.get().asFile.writeText(content.get())
    }
}
