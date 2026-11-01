package ai.openpush.gradle.setup

/**
 * Names a push SDK from what it leaves in a build. Detection never depends on these: every signal is
 * found generically first, and a fingerprint only adds `vendor` to it.
 *
 * This is the only file in the plugin that names another push SDK.
 */
internal interface VendorFingerprint {
    val vendor: String

    fun matchesClass(className: String): Boolean

    fun matchesDependency(group: String, module: String): Boolean

    fun matchesResource(name: String): Boolean

    fun matchesMetaData(name: String): Boolean
}

/**
 * OneSignal 5.x apps: an FCM broadcast receiver on
 * `c2dm RECEIVE` rather than a `FirebaseMessagingService`, an HMS service, the
 * `com.onesignal.NotificationServiceExtension` meta-data and the `ic_stat_onesignal_default` icon.
 */
internal object OneSignalFingerprint : VendorFingerprint {
    override val vendor = "onesignal"

    override fun matchesClass(className: String) = className.startsWith("com.onesignal.")

    override fun matchesDependency(group: String, module: String) = group == "com.onesignal"

    override fun matchesResource(name: String) = name == "ic_stat_onesignal_default"

    override fun matchesMetaData(name: String) = name.startsWith("com.onesignal.")
}

internal object VendorFingerprints {
    val all: List<VendorFingerprint> = listOf(OneSignalFingerprint)

    fun forClass(className: String) = all.firstOrNull { it.matchesClass(className) }?.vendor

    fun forDependency(group: String, module: String) = all.firstOrNull { it.matchesDependency(group, module) }?.vendor

    fun forResource(name: String) = all.firstOrNull { it.matchesResource(name) }?.vendor

    fun forMetaData(name: String) = all.firstOrNull { it.matchesMetaData(name) }?.vendor
}
