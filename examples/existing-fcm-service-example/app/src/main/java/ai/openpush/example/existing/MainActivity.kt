package ai.openpush.example.existing

import ai.openpush.android.ListenerHandle
import ai.openpush.android.OpenPush
import ai.openpush.android.fcm.OpenPushMessagingService
import android.app.Activity
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

/** One TextView with what a device test checks: subscription, permission, Forward mode, and the last push. */
class MainActivity :
    Activity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var status: TextView
    private var subscription: ListenerHandle? = null
    private var permission: ListenerHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setPadding(64, 64, 64, 64)
            setTextIsSelectable(true)
        }
        setContentView(status)
        LastPush.preferences(this).registerOnSharedPreferenceChangeListener(this)

        if (!OpenPush.isInitialized) return

        // The prompt runs in OpenPush's own Activity, so this Activity forwards nothing.
        OpenPush.Notifications.requestPermission(this, fallbackToSettings = true)
        subscription = OpenPush.User.pushSubscription.addObserver { runOnUiThread(::refresh) }
        permission = OpenPush.Notifications.addPermissionObserver { runOnUiThread(::refresh) }
    }

    override fun onDestroy() {
        LastPush.preferences(this).unregisterOnSharedPreferenceChangeListener(this)
        subscription?.let(OpenPush.User.pushSubscription::removeObserver)
        permission?.let(OpenPush.Notifications::removePermissionObserver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        runOnUiThread(::refresh)
    }

    private fun refresh() {
        if (!OpenPush.isInitialized) {
            status.text = getString(R.string.example_not_configured)
            return
        }
        val snapshot = OpenPush.User.pushSubscription.snapshot()
        status.text = getString(
            R.string.example_status,
            snapshot.id ?: "—",
            snapshot.token ?: "—",
            getString(
                if (OpenPush.Notifications.permission) R.string.example_allowed else R.string.example_not_allowed,
            ),
            deliveryMode(),
            openPushServiceState(),
            LastPush.title(this) ?: "—",
            LastPush.messageId(this) ?: "—",
            LastPush.receiver(this) ?: "—",
        )
    }

    private fun deliveryMode(): String = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        .metaData?.getString("ai.openpush.fcm.delivery") ?: "automatic"

    private fun openPushServiceState(): String = runCatching {
        val component = ComponentName(this, OpenPushMessagingService::class.java)
        if (packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            "disabled"
        } else {
            "enabled"
        }
    }.getOrDefault("not in the manifest")
}
