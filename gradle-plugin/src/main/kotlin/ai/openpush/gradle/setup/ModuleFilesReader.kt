package ai.openpush.gradle.setup

import java.io.File

/** Reads [ModuleFiles] from disk, with every path relative to the app module directory. */
internal class ModuleFilesReader(private val moduleDirectory: File) {
    fun read(
        sourceDirectories: Iterable<File>,
        resDirectories: Iterable<File>,
        sourceManifests: Iterable<File>,
        googleServicesCandidates: Iterable<File>,
    ) = ModuleFiles(
        classSources = classSources(sourceDirectories),
        sourceManifests = sourceManifests.filter(File::isFile).associate { relative(it) to it.readText() },
        statIcons = statIcons(resDirectories),
        googleServices = googleServicesCandidates.firstOrNull(File::isFile)?.let(::googleServices),
    )

    private fun relative(file: File): String =
        file.relativeToOrNull(moduleDirectory)?.invariantSeparatorsPath ?: file.invariantSeparatorsPath

    private fun classSources(directories: Iterable<File>): Map<String, String> {
        val classes = linkedMapOf<String, String>()
        directories.filter(File::isDirectory).forEach { directory ->
            directory.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    val text = file.readText()
                    val packageName = PACKAGE.find(text)?.groupValues?.get(1)?.let { "$it." } ?: ""
                    DECLARATION.findAll(text).forEach { match ->
                        classes.putIfAbsent(packageName + match.groupValues[1], relative(file))
                    }
                }
        }
        return classes
    }

    private fun statIcons(directories: Iterable<File>): Map<String, String> {
        val icons = linkedMapOf<String, String>()
        directories.filter(File::isDirectory).forEach { directory ->
            directory.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("drawable") }
                .flatMap { it.listFiles().orEmpty().toList() }
                .filter { it.isFile && it.name.startsWith("ic_stat_") }
                .forEach { icons[relative(it)] = it.name.substringBefore('.') }
        }
        return icons
    }

    private fun googleServices(file: File): GoogleServicesFile {
        val document = runCatching { Json.parse(file.readText()) as? Map<*, *> }.getOrNull()
        val projectInfo = document?.get("project_info") as? Map<*, *>
        return GoogleServicesFile(relative(file), projectInfo?.get("project_id") as? String)
    }

    private companion object {
        val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val DECLARATION = Regex("""\b(?:class|object|interface)\s+([A-Za-z_]\w*)""")
    }
}
