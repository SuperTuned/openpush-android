package ai.openpush.example.migrated

import ai.openpush.android.onesignal.*
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pushToggle by lazy { Button(this) }
    private val subscriptionStatus by lazy { TextView(this) }
    private val deviceStatus by lazy { TextView(this).apply { setTextIsSelectable(true) } }
    private val lastPushChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread(::refresh)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pushToggle.setOnClickListener { togglePush() }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 64, 64, 64)
                addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.example_status)
                    },
                )
                addView(pushToggle)
                addView(subscriptionStatus)
                addView(deviceStatus)
            },
        )
        LastPush.preferences(this).registerOnSharedPreferenceChangeListener(lastPushChanged)
        OneSignal.User.pushSubscription.addObserver { refresh() }
        OneSignal.Notifications.addPermissionObserver { refresh() }
        OneSignal.Notifications.addForegroundLifecycleListener { event ->
            Log.i(TAG, "foreground notification · ${event.messageId ?: "unknown"}")
            event.preventDefault()
        }
        askForNotificationPermission()
        reportClick(intent)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun askForNotificationPermission() {
        lifecycleScope.launch {
            val granted = OneSignal.Notifications.requestPermission(fallbackToSettings = true)
            if (!granted) Log.i(TAG, "notifications are not permitted")
        }
    }

    private fun togglePush() {
        if (OneSignal.User.pushSubscription.optedIn) {
            OneSignal.User.pushSubscription.optOut()
        } else {
            OneSignal.User.pushSubscription.optIn()
        }
        refresh()
    }

    private fun refresh() {
        val subscription = OneSignal.User.pushSubscription
        val snapshot = subscription.snapshot()
        pushToggle.text = getString(
            if (snapshot.optedIn) R.string.example_opt_out else R.string.example_opt_in,
        )
        subscriptionStatus.text = getString(
            R.string.example_subscription,
            snapshot.id ?: subscription.token ?: "—",
            OneSignal.User.externalId ?: "anonymous",
        )
        deviceStatus.text = getString(
            R.string.example_device_status,
            subscription.token.ifEmpty { "—" },
            OneSignal.Notifications.permission,
            LastPush.title(this) ?: "—",
            LastPush.messageId(this) ?: "—",
        )
    }

    override fun onDestroy() {
        LastPush.preferences(this).unregisterOnSharedPreferenceChangeListener(lastPushChanged)
        super.onDestroy()
    }

    // Optional: the prompt answers in OpenPush's own Activity; this legacy forward stays harmless.
    @Deprecated("ComponentActivity deprecates this in favour of ActivityResult; the SDK needs the raw result.")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        OneSignal.Notifications.onPermissionResult(requestCode, grantResults)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportClick(intent)
    }

    /**
     * A tapped notification carries its OpenPush message id. Clearing the extra
     * keeps a configuration change or a later relaunch from reporting it twice.
     */
    private fun reportClick(intent: Intent?) {
        val messageId = intent?.getStringExtra(ExampleMessagingService.EXTRA_MESSAGE_ID) ?: return
        Log.i(TAG, "opened by notification · $messageId")
        OneSignal.Notifications.processClick(mapOf("op_message_id" to messageId))
        intent.removeExtra(ExampleMessagingService.EXTRA_MESSAGE_ID)
    }

    private companion object {
        const val TAG = "MigratedExample"
    }
}
