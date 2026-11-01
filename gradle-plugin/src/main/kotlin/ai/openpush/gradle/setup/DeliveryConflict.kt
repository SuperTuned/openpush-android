package ai.openpush.gradle.setup

/**
 * The services that receive `com.google.firebase.MESSAGING_EVENT` do not match the delivery choice.
 * Android hands each push to only one enabled service, so any mismatch splits pushes between them and
 * some silently never render.
 *
 * [mode] and [service] are the recorded choice (null when there is none); [services] are the enabled
 * receivers in the merged manifest.
 */
internal data class DeliveryConflict(
    val variant: String,
    val services: List<String>,
    val mode: String? = null,
    val service: String? = null,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf("services" to services, "mode" to mode, "service" to service)

    fun message(setupFile: String): String = buildString {
        append("OpenPush: ${services.size} services receive ${PushPlumbingScanner.MESSAGING_EVENT} ")
        append("in the merged manifest of variant '$variant':\n")
        services.forEach { append("  - $it\n") }
        append("Android hands each push to only one of them, so pushes would be split between them. ")
        when (mode) {
            "forward" -> {
                append("$setupFile records Forward to ${service ?: "your service"}, so only that service and ")
                append("${PushPlumbingScanner.OPENPUSH_SERVICE} (disabled at runtime) may stay. Add the others to ")
                append("\"remove\" in the android_delivery choice, or rerun ./gradlew openpushSetup ")
                append("-Popenpush.android_delivery=forward:<your service>.\n")
            }
            "automatic" -> {
                append("$setupFile records Automatic, so only ${PushPlumbingScanner.OPENPUSH_SERVICE} may stay. ")
                append("Add the others to \"remove\" in the android_delivery choice, or rerun ")
                append("./gradlew openpushSetup -Popenpush.android_delivery=automatic.\n")
            }
            else -> {
                append("Choose who receives pushes in $setupFile, either way:\n")
                append("  1. Forward: keep your service, call OpenPushMessagingService.handle(this, message) at the ")
                append("top of onMessageReceived and OpenPush.register(token) in onNewToken, and record\n")
                append(
                    "     \"choices\": {\"android_delivery\": {\"mode\": \"forward\", \"service\": \"<your service>\"}}\n",
                )
                append("  2. Automatic: let ${PushPlumbingScanner.OPENPUSH_SERVICE} receive pushes and record\n")
                append(
                    "     \"choices\": {\"android_delivery\": {\"mode\": \"automatic\", \"remove\": [<the others>]}}\n",
                )
                append("./gradlew openpushSetup records either and applies it to the merged manifest.\n")
            }
        }
        append("Run ./gradlew openpushDoctor to list everything found.")
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(variant: String, json: Map<String, Any?>) = DeliveryConflict(
            variant,
            json["services"] as List<String>,
            json["mode"] as String?,
            json["service"] as String?,
        )

        /**
         * Checks the merged manifest against the recorded [choice] (`android_delivery`), or, without one,
         * against `ai.openpush.fcm.delivery=forward` set in the manifest by hand:
         * - no choice: at most one enabled service;
         * - forward: besides OpenPush's own service, which the SDK disables at runtime, exactly the chosen
         *   service (any one service when the manifest value was set by hand);
         * - automatic: only OpenPush's service.
         */
        fun find(variant: String, manifest: MergedManifest, choice: Map<String, Any?>?): DeliveryConflict? {
            val services = manifest.receiving(PushPlumbingScanner.MESSAGING_EVENT, "service")
                .filter { it.enabled && it.name != PushPlumbingScanner.FIREBASE_FALLBACK_SERVICE }
                .map { it.name }
            val others = services.filter { it != PushPlumbingScanner.OPENPUSH_SERVICE }
            val manifestForward = manifest.metaValue(PushPlumbingScanner.OPENPUSH_DELIVERY_META)
                .equals("forward", ignoreCase = true)
            val mode = (choice?.get("mode") as? String)?.takeIf { it.isNotBlank() }
                ?: if (manifestForward) "forward" else null
            val chosen = choice?.get("service") as? String
            val matches = when (mode) {
                "forward" -> if (chosen != null) others == listOf(chosen) else others.size == 1
                "automatic" -> others.isEmpty()
                else -> services.size < 2
            }
            return if (matches) null else DeliveryConflict(variant, services, mode, chosen)
        }
    }
}
