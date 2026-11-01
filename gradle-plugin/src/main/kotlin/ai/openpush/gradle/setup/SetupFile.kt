package ai.openpush.gradle.setup

import java.io.File

/** One piece of existing push plumbing, as a `found` entry of the `openpush-setup/1` schema. */
internal data class Found(
    val kind: String,
    val id: String?,
    val source: String,
    val vendor: String? = null,
    val editable: Boolean,
    val details: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "kind" to kind,
        "id" to id,
        "source" to source,
        "vendor" to vendor,
        "editable" to editable,
        "details" to details,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(json: Map<String, Any?>) = Found(
            kind = json["kind"] as String,
            id = json["id"] as String?,
            source = json["source"] as String,
            vendor = json["vendor"] as String?,
            editable = json["editable"] as Boolean,
            details = (json["details"] as Map<String, Any?>?) ?: emptyMap(),
        )
    }
}

/**
 * `openpush-setup.json` beside the app module's build file, shared with the iOS and Unity setup
 * tools. `found` is regenerated on every doctor run; `choices` and any field this plugin does not know
 * are kept exactly as they were.
 */
internal class SetupFile(private val file: File) {
    private val document: LinkedHashMap<String, Any?> =
        if (file.isFile) parse(file.readText(), file.name) else linkedMapOf()

    /** The stored answers, by choice name (`firebase`, `android_delivery`, `icons`, ...). */
    @Suppress("UNCHECKED_CAST")
    val choices: Map<String, Map<String, Any?>>
        get() = (document["choices"] as? Map<String, Any?>).orEmpty()
            .mapNotNull { (key, value) -> (value as? Map<String, Any?>)?.let { key to it } }
            .toMap()

    /** The stored `android_delivery` mode, or null when the developer has not chosen one. */
    val androidDeliveryMode: String? get() = choiceMode(choices, ANDROID_DELIVERY)

    fun writeFound(found: List<Found>) = write(found, emptyMap())

    /** Regenerates `found` and sets the given [answers] in `choices`, leaving every other choice as it was. */
    @Suppress("UNCHECKED_CAST")
    fun write(found: List<Found>, answers: Map<String, Map<String, Any?>>) {
        val out = linkedMapOf<String, Any?>("schema" to SCHEMA, "platform" to "android")
        out.putAll(document)
        out["schema"] = document["schema"] ?: SCHEMA
        out["platform"] = document["platform"] ?: "android"
        out["found"] = found.map(Found::toJson)
        val choices = LinkedHashMap(out["choices"] as? Map<String, Any?> ?: emptyMap())
        choices.putAll(answers)
        out["choices"] = choices
        file.writeText(Json.write(out))
    }

    companion object {
        const val NAME = "openpush-setup.json"
        const val SCHEMA = "openpush-setup/1"
        const val ANDROID_DELIVERY = "android_delivery"
        const val FIREBASE = "firebase"
        const val ICONS = "icons"

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String, name: String = NAME): LinkedHashMap<String, Any?> {
            // An editor's byte-order mark, or a file emptied by hand, is not an error: blank means empty.
            val json = text.removePrefix("\uFEFF")
            if (json.isBlank()) return linkedMapOf()
            val parsed = runCatching { Json.parse(json) }.getOrElse {
                throw IllegalStateException("$name is not valid JSON — ${it.message}")
            }
            return parsed as? LinkedHashMap<String, Any?>
                ?: throw IllegalStateException("$name must hold a JSON object")
        }

        /** The choices of a setup file's [text], or none for a missing file. */
        @Suppress("UNCHECKED_CAST")
        fun choicesOf(text: String?): Map<String, Map<String, Any?>> =
            (text?.let { parse(it)["choices"] } as? Map<String, Any?>).orEmpty()
                .mapNotNull { (key, value) -> (value as? Map<String, Any?>)?.let { key to it } }
                .toMap()

        fun choiceMode(choices: Map<String, Map<String, Any?>>, name: String): String? =
            (choices[name]?.get("mode") as? String)?.takeIf { it.isNotBlank() }
    }
}
