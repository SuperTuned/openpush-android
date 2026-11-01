package ai.openpush.gradle.setup

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * `openpushDoctor`: runs every variant's doctor, writes the union of their findings to
 * `openpush-setup.json` `found`, prints them, and only then fails if a variant would split delivery.
 */
@DisableCachingByDefault(because = "Writes a project file the developer edits")
abstract class OpenPushDoctorReportTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val variantReports: ConfigurableFileCollection

    @get:Internal
    abstract val setupFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun run() {
        val (found, conflicts) = DoctorReports.read(variantReports.files)
        val file = setupFile.get().asFile
        SetupFile(file).writeFound(found)
        logger.lifecycle("OpenPush doctor: ${found.size} item(s) found, written to ${file.name}")
        found.forEach { item ->
            val vendor = item.vendor?.let { " [$it]" } ?: ""
            val owner = if (item.editable) "editable" else "from a dependency"
            logger.lifecycle("  ${item.kind}: ${item.id ?: "-"}$vendor — ${item.source} ($owner)")
        }
        if (conflicts.isNotEmpty()) {
            throw GradleException(conflicts.joinToString("\n\n") { it.message(SetupFile.NAME) })
        }
    }
}

/** The per-variant doctor reports, merged: each finding once, with the variants it was found in. */
internal data class DoctorReports(val found: List<Found>, val conflicts: List<DeliveryConflict>) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun read(files: Iterable<File>): DoctorReports {
            val merged = linkedMapOf<String, Pair<Found, MutableList<String>>>()
            val conflicts = mutableListOf<DeliveryConflict>()
            files.sortedBy { it.path }.forEach { file ->
                val report = Json.parse(file.readText()) as Map<String, Any?>
                val variant = report["variant"] as String
                (report["found"] as List<Map<String, Any?>>).map(Found::fromJson).forEach { found ->
                    val key = listOf(found.kind, found.id, found.source, found.vendor).joinToString("|")
                    merged.getOrPut(key) { found to mutableListOf() }.second += variant
                }
                (report["conflict"] as Map<String, Any?>?)?.let { conflicts += DeliveryConflict.fromJson(variant, it) }
            }
            val found = merged.values.map { (item, variants) ->
                item.copy(details = item.details + ("variants" to variants))
            }
            return DoctorReports(found, conflicts)
        }
    }
}
