package ai.openpush.gradle.setup

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `openpushDoctor<Variant>`: reads the variant's merged manifest as it leaves `process<Variant>Manifest`,
 * passes it on unchanged, and records what push plumbing the variant has and whether delivery would be
 * split. It never fails; `openpushCheck<Variant>`, next in the manifest pipeline, fails the build.
 */
@DisableCachingByDefault(because = "Small file reads; rerunning is cheaper than caching")
abstract class OpenPushDoctorTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val updatedManifest: RegularFileProperty

    /** This variant's findings, read by `openpushDoctor`. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val setupFile: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceManifests: ConfigurableFileCollection

    /** Where the Google Services plugin looks for this variant, most specific first. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val googleServicesCandidates: ConfigurableFileCollection

    /** The variant's resolved runtime modules, as `group:module:version`. */
    @get:Input
    abstract val dependencies: ListProperty<String>

    @get:Input
    abstract val googleServicesPluginApplied: Property<Boolean>

    @get:Internal
    abstract val moduleDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val manifestFile = mergedManifest.get().asFile
        manifestFile.copyTo(updatedManifest.get().asFile, overwrite = true)

        val manifest = MergedManifest.read(manifestFile)
        val moduleDir = moduleDirectory.get().asFile
        val files = ModuleFilesReader(moduleDir).read(
            sourceDirectories.files,
            resDirectories.files,
            sourceManifests.files,
            googleServicesCandidates.toList(),
        )
        val modules = dependencies.get().mapNotNull { coordinate ->
            coordinate.split(':').takeIf { it.size == 3 }?.let { ResolvedModule(it[0], it[1], it[2]) }
        }
        val found = PushPlumbingScanner.scan(manifest, files, modules, googleServicesPluginApplied.get())
        val stored = setupFile.files.firstOrNull {
            it.isFile
        }?.let { SetupFile(it).choices[SetupFile.ANDROID_DELIVERY] }
        val conflict = DeliveryConflict.find(variantName.get(), manifest, stored)

        report.get().asFile.writeText(
            Json.write(
                linkedMapOf(
                    "variant" to variantName.get(),
                    "found" to found.map(Found::toJson),
                    "conflict" to conflict?.toJson(),
                ),
            ),
        )
    }
}
