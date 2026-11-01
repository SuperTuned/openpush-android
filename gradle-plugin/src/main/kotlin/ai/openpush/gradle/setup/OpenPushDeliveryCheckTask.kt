package ai.openpush.gradle.setup

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `openpushCheck<Variant>`: the last step of the variant's manifest pipeline. It passes the merged
 * manifest on unchanged and fails the build when `openpushDoctor<Variant>` found delivery split between
 * services. Only tasks that need the final manifest (packaging, bundling, lint) run it, so
 * `openpushDoctor` and `openpushSetup` can still report a conflict they are there to resolve.
 */
@DisableCachingByDefault(because = "Copies one small file")
abstract class OpenPushDeliveryCheckTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: RegularFileProperty

    @Suppress("UNCHECKED_CAST")
    @TaskAction
    fun run() {
        mergedManifest.get().asFile.copyTo(updatedManifest.get().asFile, overwrite = true)
        val report = Json.parse(report.get().asFile.readText()) as Map<String, Any?>
        val conflict = (report["conflict"] as Map<String, Any?>?) ?: return
        throw GradleException(DeliveryConflict.fromJson(report["variant"] as String, conflict).message(SetupFile.NAME))
    }
}
