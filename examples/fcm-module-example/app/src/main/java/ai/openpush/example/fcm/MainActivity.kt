package ai.openpush.example.fcm

import ai.openpush.android.ListenerHandle
import ai.openpush.android.OpenPush
import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.TextView

/** One TextView: the subscription this install holds, the permission, and the last push, as the SDK reports them. */
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

        if (!ExampleApplication.hasCredentials) return

        // The prompt runs in OpenPush's own Activity and answers there, so this Activity forwards nothing.
        OpenPush.Notifications.requestPermission(this, fallbackToSettings = true)
        // The module fetches and registers the provider token itself, at launch and on
        // every rotation, so the app never calls Firebase. Redraw when it lands.
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
            OpenPush.isInitialized,
            snapshot.id ?: "—",
            snapshot.token ?: "—",
            snapshot.optedIn,
            OpenPush.Notifications.permission,
            LastPush.title(this) ?: "—",
            LastPush.messageId(this) ?: "—",
            LastPush.seen(this) ?: "—",
        )
    }
}
