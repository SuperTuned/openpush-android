package ai.openpush.gradle.setup

/** What the app module's own files say, as paths relative to the module directory. */
internal class ModuleFiles(
    /** Classes declared in the variant's Java and Kotlin source sets: fully qualified name to file. */
    val classSources: Map<String, String>,
    /** Source-set `AndroidManifest.xml` files: path to text. */
    val sourceManifests: Map<String, String>,
    /** `res/drawable*` files named `ic_stat_*`: path to resource name. */
    val statIcons: Map<String, String>,
    /** The `google-services.json` the Google Services plugin would use for this variant, if any. */
    val googleServices: GoogleServicesFile?,
) {
    fun declaresInManifest(name: String): String? = sourceManifests.entries.firstOrNull { name in it.value }?.key
}

internal data class GoogleServicesFile(val path: String, val projectId: String?)

internal data class ResolvedModule(val group: String, val module: String, val version: String) {
    override fun toString() = "$group:$module:$version"
}

/**
 * The generic push-plumbing scan: any FCM service or receiver, HMS, the Firebase app and its config,
 * and existing notification defaults, whichever SDK or framework put them there. Vendor fingerprints
 * only label what this finds.
 */
internal object PushPlumbingScanner {
    const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"
    const val C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
    const val HMS_MESSAGING_EVENT = "com.huawei.push.action.MESSAGING_EVENT"
    const val OPENPUSH_SERVICE = "ai.openpush.android.fcm.OpenPushMessagingService"

    /**
     * firebase-messaging declares its own base class on MESSAGING_EVENT at priority -500, as the
     * fallback when an app has no service. It is in every merged manifest with Firebase, so it is never
     * existing plumbing and never a second receiver.
     */
    const val FIREBASE_FALLBACK_SERVICE = "com.google.firebase.messaging.FirebaseMessagingService"

    /**
     * Firebase's own components, present whenever firebase-messaging is: the fallback service above and
     * the receiver that hands every push to the services. Neither is a second integration.
     */
    private val FIREBASE_OWN = setOf(FIREBASE_FALLBACK_SERVICE, "com.google.firebase.iid.FirebaseInstanceIdReceiver")
    const val OPENPUSH_DELIVERY_META = "ai.openpush.fcm.delivery"
    const val OPENPUSH_STAT_ICON = "ic_stat_openpush_default"
    const val GOOGLE_SERVICES_PLUGIN = "com.google.gms.google-services"

    private const val MERGED = "merged AndroidManifest.xml"
    private const val FCM_META = "com.google.firebase.messaging."
    private const val DEFAULT_ICON_META = FCM_META + "default_notification_icon"
    private const val DEFAULT_COLOR_META = FCM_META + "default_notification_color"
    private const val DEFAULT_CHANNEL_META = FCM_META + "default_notification_channel_id"
    private const val AUTO_INIT_META = "firebase_messaging_auto_init_enabled"
    private const val OPENPUSH_PROJECT_META = "ai.openpush.fcm.project_id"

    fun scan(
        manifest: MergedManifest,
        files: ModuleFiles,
        dependencies: List<ResolvedModule>,
        googleServicesPluginApplied: Boolean,
    ): List<Found> = buildList {
        addAll(components(manifest, files, MESSAGING_EVENT, "service", "fcm_service"))
        addAll(components(manifest, files, C2DM_RECEIVE, "receiver", "fcm_receiver"))
        addAll(
            components(manifest, files, HMS_MESSAGING_EVENT, "service", "push_sdk", mapOf("provider" to "hms")),
        )
        addAll(pushSdks(dependencies))
        firebaseApp(manifest, files, dependencies, googleServicesPluginApplied)?.let(::add)
        addAll(notificationDefaults(manifest, files))
        addAll(statIcons(files))
    }

    private fun components(
        manifest: MergedManifest,
        files: ModuleFiles,
        action: String,
        tag: String,
        kind: String,
        extra: Map<String, Any?> = emptyMap(),
    ): List<Found> = manifest.receiving(action, tag)
        .filter { it.name != OPENPUSH_SERVICE && it.name !in FIREBASE_OWN }
        .map { component ->
            val sourceFile = files.classSources[component.name]
            Found(
                kind = kind,
                id = component.name,
                source = sourceFile ?: MERGED,
                vendor = VendorFingerprints.forClass(component.name),
                // Editable means its class is in this module's sources; otherwise a dependency owns it.
                editable = sourceFile != null,
                details = extra + linkedMapOf("action" to action, "enabled" to component.enabled),
            )
        }

    private fun pushSdks(dependencies: List<ResolvedModule>): List<Found> = dependencies.mapNotNull { module ->
        val vendor = VendorFingerprints.forDependency(module.group, module.module) ?: return@mapNotNull null
        Found(
            kind = "push_sdk",
            id = "${module.group}:${module.module}",
            source = "runtime classpath",
            vendor = vendor,
            editable = false,
            details = linkedMapOf("version" to module.version),
        )
    }.distinctBy { it.id }

    private fun firebaseApp(
        manifest: MergedManifest,
        files: ModuleFiles,
        dependencies: List<ResolvedModule>,
        googleServicesPluginApplied: Boolean,
    ): Found? {
        val firebase = dependencies.filter { it.group == "com.google.firebase" }
        val messaging = firebase.firstOrNull { it.module == "firebase-messaging" }
        val bom = firebase.firstOrNull { it.module == "firebase-bom" }
        val openPushProject = manifest.metaValue(OPENPUSH_PROJECT_META)
        val config = files.googleServices
        if (config == null && openPushProject == null && messaging == null && !googleServicesPluginApplied) return null
        val details = linkedMapOf<String, Any?>(
            "config" to when {
                config != null -> "google-services.json"
                openPushProject != null -> "openpush meta-data"
                else -> null
            },
            "google_services_plugin" to googleServicesPluginApplied,
            "firebase_bom" to bom?.version,
            "firebase_messaging" to messaging?.version,
        )
        manifest.metaValue(AUTO_INIT_META)?.let { details["auto_init_enabled"] = it }
        return Found(
            kind = "firebase_app",
            id = config?.projectId ?: openPushProject,
            source = config?.path ?: if (openPushProject != null) MERGED else "runtime classpath",
            editable = config != null || files.declaresInManifest(OPENPUSH_PROJECT_META) != null,
            details = details,
        )
    }

    private fun notificationDefaults(manifest: MergedManifest, files: ModuleFiles): List<Found> = buildList {
        manifest.metaData.firstOrNull { it.name == DEFAULT_ICON_META }?.let { meta ->
            val name = meta.value?.substringAfterLast('/')
            add(
                Found(
                    kind = "notification_icon",
                    id = name,
                    source = files.declaresInManifest(meta.name) ?: MERGED,
                    vendor = name?.let(VendorFingerprints::forResource),
                    editable = files.declaresInManifest(meta.name) != null,
                    details = linkedMapOf(
                        "meta_data" to meta.name,
                        // The reference exactly as the manifest has it, type included (@drawable, @mipmap).
                        "resource" to meta.value,
                        "color" to manifest.metaValue(DEFAULT_COLOR_META),
                    ),
                ),
            )
        }
        manifest.metaData.firstOrNull { it.name == DEFAULT_CHANNEL_META }?.let { meta ->
            add(
                Found(
                    kind = "notification_channel",
                    id = meta.value,
                    source = files.declaresInManifest(meta.name) ?: MERGED,
                    editable = files.declaresInManifest(meta.name) != null,
                    details = linkedMapOf("meta_data" to meta.name),
                ),
            )
        }
        // Vendor settings that are neither icons nor channels still name the SDK that put them there.
        manifest.metaData.forEach { meta ->
            val vendor = VendorFingerprints.forMetaData(meta.name) ?: return@forEach
            add(
                Found(
                    kind = "push_sdk",
                    id = meta.name,
                    source = files.declaresInManifest(meta.name) ?: MERGED,
                    vendor = vendor,
                    editable = files.declaresInManifest(meta.name) != null,
                    details = linkedMapOf("meta_data" to meta.value),
                ),
            )
        }
    }

    private fun statIcons(files: ModuleFiles): List<Found> = files.statIcons.entries
        .filter { it.value != OPENPUSH_STAT_ICON }
        .groupBy({ it.value }, { it.key })
        .map { (name, paths) ->
            Found(
                kind = "notification_icon",
                id = name,
                source = paths.sorted().first(),
                vendor = VendorFingerprints.forResource(name),
                editable = true,
                details = linkedMapOf("resource" to "@drawable/$name", "files" to paths.sorted()),
            )
        }
}
