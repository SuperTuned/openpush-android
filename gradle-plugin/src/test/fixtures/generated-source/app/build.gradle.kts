import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("ai.openpush.setup")
}

android {
    namespace = "com.example.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.fixture"
        minSdk = 24
        targetSdk = 36
    }
}

// A code generator that reads the merged manifest, as annotation processors and KSP can through the
// R class. Wiring generated sources into the doctor would make this a cycle.
abstract class ManifestCodegen : DefaultTask() {
    @get:InputFile
    abstract val manifest: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = output.get().asFile.resolve("com/example/fixture").apply { mkdirs() }
        dir.resolve("Generated.java").writeText(
            "package com.example.fixture;\npublic final class Generated { public static final int SIZE = " +
                manifest.get().asFile.length() + "; }\n",
        )
    }
}

androidComponents {
    onVariants { variant ->
        val codegen = tasks.register("codegen${variant.name.replaceFirstChar(Char::uppercaseChar)}", ManifestCodegen::class.java) {
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        variant.sources.java?.addGeneratedSourceDirectory(codegen, ManifestCodegen::output)
    }
}
