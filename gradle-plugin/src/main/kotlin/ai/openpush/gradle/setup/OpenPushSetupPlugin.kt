package ai.openpush.gradle.setup

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.TaskProvider

/**
 * `id("ai.openpush.setup")`, applied to an Android application module. It registers
 * `openpushDoctor<Variant>` in each variant's manifest pipeline, the `openpushDoctor` task that writes
 * `openpush-setup.json` `found`, and `openpushSetup`, which records the choices. Every build then
 * applies the stored choices and `openpush.properties` through a generated manifest merged into each
 * variant, so the developer's own manifest is never edited.
 */
class OpenPushSetupPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        var applied = false
        project.plugins.withId("com.android.application") {
            applied = true
            configure(project)
        }
        project.afterEvaluate {
            if (!applied) {
                project.logger.warn(
                    "OpenPush: ai.openpush.setup does nothing in ${project.path} — apply it to the module " +
                        "that applies com.android.application",
                )
            }
        }
    }

    private fun configure(project: Project) {
        val setupFile = project.layout.projectDirectory.file(SetupFile.NAME)
        val setupText = project.providers.fileContents(setupFile).asText
        val propertiesFile = project.layout.projectDirectory.file(OpenPushProperties.NAME)
        val propertiesText = project.providers.fileContents(propertiesFile).asText
        val fragment = setupText.orElse("").zip(propertiesText.orElse("")) { setup, properties ->
            ManifestFragment.from(SetupFile.choicesOf(setup), OpenPushProperties.parse(properties))
        }
        val doctor = project.tasks.register(DOCTOR, OpenPushDoctorReportTask::class.java) { task ->
            task.group = "openpush"
            task.description = "Finds existing push plumbing in every variant and writes ${SetupFile.NAME}."
            task.setupFile.set(setupFile)
        }
        val setup = project.tasks.register(SETUP, OpenPushSetupTask::class.java) { task ->
            task.group = "openpush"
            task.description = "Records Reuse / Create new answers for the push plumbing found in ${SetupFile.NAME}."
            task.setupFile.set(setupFile)
            task.propertiesFile.set(project.layout.projectDirectory.file(OpenPushProperties.NAME))
            task.moduleDirectory.set(project.layout.projectDirectory)
            task.patchFile.set(project.layout.buildDirectory.file("openpush/forward.patch"))
            task.options.set(
                project.provider {
                    project.properties.filterKeys { it.startsWith("openpush.") }.mapValues { it.value.toString() }
                },
            )
            task.ci.set(project.providers.environmentVariable("CI").map { it.isNotBlank() }.orElse(false))
            task.notCompatibleWithConfigurationCache("asks on the console and edits project files")
        }
        val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants { variant ->
            val variantDoctor = registerVariantDoctor(project, variant)
            variant.artifacts.use(variantDoctor)
                .wiredWithFiles(OpenPushDoctorTask::mergedManifest, OpenPushDoctorTask::updatedManifest)
                .toTransform(SingleArtifact.MERGED_MANIFEST)
            // Transforms chain in registration order: the check reads the doctor's output.
            val check = project.tasks.register(
                "openpushCheck" + variant.name.replaceFirstChar(Char::uppercaseChar),
                OpenPushDeliveryCheckTask::class.java,
            ) { task ->
                task.group = "openpush"
                task.description = "Fails the ${variant.name} build when push delivery would be split."
                task.report.set(variantDoctor.flatMap(OpenPushDoctorTask::report))
            }
            variant.artifacts.use(check)
                .wiredWithFiles(OpenPushDeliveryCheckTask::mergedManifest, OpenPushDeliveryCheckTask::updatedManifest)
                .toTransform(SingleArtifact.MERGED_MANIFEST)
            doctor.configure { it.variantReports.from(variantDoctor.flatMap(OpenPushDoctorTask::report)) }
            setup.configure { it.variantReports.from(variantDoctor.flatMap(OpenPushDoctorTask::report)) }

            val manifestTask = project.tasks.register(
                "openpushManifest" + variant.name.replaceFirstChar(Char::uppercaseChar),
                OpenPushManifestTask::class.java,
            ) { task ->
                task.content.set(fragment.map(ManifestFragment::xml))
                task.manifest.set(project.layout.buildDirectory.file("openpush/${variant.name}/AndroidManifest.xml"))
            }
            variant.sources.manifests.addGeneratedManifestFile(manifestTask, OpenPushManifestTask::manifest)
            variant.manifestPlaceholders.putAll(fragment.map(ManifestFragment::placeholders))
        }
    }

    private fun registerVariantDoctor(
        project: Project,
        variant: ApplicationVariant,
    ): TaskProvider<OpenPushDoctorTask> {
        val name = DOCTOR + variant.name.replaceFirstChar(Char::uppercaseChar)
        return project.tasks.register(name, OpenPushDoctorTask::class.java) { task ->
            task.group = "openpush"
            task.description = "Checks the ${variant.name} merged manifest for push plumbing."
            task.variantName.set(variant.name)
            task.moduleDirectory.set(project.layout.projectDirectory)
            task.setupFile.from(project.layout.projectDirectory.file(SetupFile.NAME))
            // Static directories only: generated ones (KSP, kapt, codegen tasks) would wire their tasks
            // into the manifest pipeline, and those often depend on the manifest themselves — a cycle.
            variant.sources.java?.let { task.sourceDirectories.from(it.static) }
            variant.sources.kotlin?.let { task.sourceDirectories.from(it.static) }
            variant.sources.res?.let { task.resDirectories.from(it.static) }
            task.sourceManifests.from(
                project.fileTree(project.layout.projectDirectory.dir("src")) { it.include("*/AndroidManifest.xml") },
            )
            task.googleServicesCandidates.from(
                googleServicesLocations(variant).map { project.layout.projectDirectory.file("$it$GOOGLE_SERVICES") },
            )
            task.dependencies.set(
                variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(::moduleCoordinates),
            )
            task.googleServicesPluginApplied.set(
                project.provider { project.plugins.hasPlugin(PushPlumbingScanner.GOOGLE_SERVICES_PLUGIN) },
            )
            task.report.set(project.layout.buildDirectory.file("openpush/${variant.name}/doctor.json"))
        }
    }

    internal companion object {
        const val DOCTOR = "openpushDoctor"
        const val SETUP = "openpushSetup"
        private const val GOOGLE_SERVICES = "google-services.json"

        /** The Google Services plugin's search order for a variant, most specific first. */
        fun googleServicesLocations(variant: ApplicationVariant): List<String> {
            val buildType = variant.buildType.orEmpty()
            val flavorName = variant.flavorName.orEmpty()
            val flavors = variant.productFlavors.map { it.second }
            return buildList {
                add("src/${variant.name}/")
                if (flavorName.isNotEmpty() && buildType.isNotEmpty()) {
                    add("src/$flavorName/$buildType/")
                    add("src/$buildType/$flavorName/")
                }
                if (flavorName.isNotEmpty()) add("src/$flavorName/")
                if (buildType.isNotEmpty()) add("src/$buildType/")
                flavors.forEach { add("src/$it/") }
                add("")
            }.distinct()
        }

        fun moduleCoordinates(root: ResolvedComponentResult): List<String> {
            val seen = linkedSetOf<String>()
            val visited = mutableSetOf<ResolvedComponentResult>()
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!visited.add(component)) continue
                (component.id as? ModuleComponentIdentifier)?.let { seen += "${it.group}:${it.module}:${it.version}" }
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
            }
            return seen.sorted()
        }
    }
}
