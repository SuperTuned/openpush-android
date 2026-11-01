package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Who receives `com.google.firebase.MESSAGING_EVENT`, from the `ai.openpush.fcm.delivery` manifest value.
 *
 * - `automatic` (the default): [OpenPushMessagingService] receives pushes itself.
 * - `forward`: the host's own `FirebaseMessagingService` receives them and calls
 *   [OpenPushMessagingService.handle]. [OpenPushMessagingService] is disabled at runtime, so a merged
 *   copy the host forgot to remove with `tools:node="remove"` cannot split delivery between two services.
 *
 * The state is a persistent component setting, so switching back to `automatic` re-enables the service on
 * the next launch.
 */
internal object OpenPushDeliveryMode {
    const val META_DELIVERY = "ai.openpush.fcm.delivery"
    const val AUTOMATIC = "automatic"
    const val FORWARD = "forward"

    const val FORWARD_LOG =
        "forward mode: your FirebaseMessagingService must call OpenPushMessagingService.handle"

    fun isForward(context: Context): Boolean {
        val value = OpenPushFirebaseApp.manifestValue(context, META_DELIVERY) ?: return false
        return when {
            value.equals(FORWARD, ignoreCase = true) -> true
            value.equals(AUTOMATIC, ignoreCase = true) -> false
            else -> {
                OpenPush.onLog("$META_DELIVERY \"$value\" is not $AUTOMATIC or $FORWARD — using $AUTOMATIC")
                false
            }
        }
    }

    /**
     * Disables or re-enables [OpenPushMessagingService] to match the manifest value. Only a disable this
     * SDK made itself is undone: a host that disabled the service on its own keeps it disabled.
     */
    fun apply(context: Context) {
        val forward = isForward(context)
        val component = ComponentName(context, OpenPushMessagingService::class.java)
        val packageManager = context.packageManager
        val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val disabledBySdk = prefs.getBoolean(DISABLED_BY_FORWARD_KEY, false)
        // Automatic with nothing of ours to undo: leave the component alone, and say nothing, on every
        // launch of a host that removed the service with tools:node="remove".
        if (!forward && !disabledBySdk) return
        runCatching {
            val current = packageManager.getComponentEnabledSetting(component)
            val disabled = current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            when {
                forward && !disabled -> {
                    packageManager.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    prefs.edit().putBoolean(DISABLED_BY_FORWARD_KEY, true).apply()
                }
                !forward && disabledBySdk -> {
                    // Back to the manifest's own state, rather than forcing it on over a host's android:enabled.
                    if (disabled) {
                        packageManager.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.DONT_KILL_APP,
                        )
                    }
                    prefs.edit().remove(DISABLED_BY_FORWARD_KEY).apply()
                }
            }
        }.onFailure {
            // A host that removed the merged service with tools:node="remove" has no component to switch.
            OpenPush.onLog("delivery mode not applied to OpenPushMessagingService — ${it.message}")
        }
        if (forward) OpenPush.onLog(FORWARD_LOG)
    }

    private const val PREFERENCES_NAME = "openpush.fcm"

    // Set while forward mode has the service disabled. Component state outlives this flag when the user
    // clears the app's data: the service then stays disabled until the app is reinstalled or updated,
    // because automatic only re-enables a disable it has a record of. Forward mode sets it again.
    private const val DISABLED_BY_FORWARD_KEY = "openpush.fcm.serviceDisabledByForward"
}
