package ai.openpush.android

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A transparent Activity that shows the POST_NOTIFICATIONS prompt through the Activity Result API and
 * hands the answer to [OpenPush], so a host never forwards `onRequestPermissionsResult`. It is declared
 * in this module's manifest; a pending prompt survives a configuration change.
 */
internal class OpenPushPermissionActivity : ComponentActivity() {
    private var answered = false
    private var restored = false
    private val generation get() = intent.getLongExtra(EXTRA_GENERATION, -1L)

    private val prompt = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PermissionPromptWatch.shown(generation)
        // Recreated after a configuration change or process death: the registry delivers a pending
        // answer to `prompt` before onResume.
        if (savedInstanceState != null) {
            restored = true
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            answer(notificationsEnabled())
            return
        }
        prompt.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // Restored, and no answer arrived before resuming: the system dialog is gone (it covers this
        // Activity while it is up), so the permission is already decided. Report it and leave.
        if (restored && !answered) answer(currentPermission())
    }

    override fun onDestroy() {
        // Finished without an answer (the system dismissed it): report what the OS says now, so the
        // request does not stay in flight.
        if (isFinishing && !answered) report(currentPermission())
        super.onDestroy()
    }

    private fun answer(granted: Boolean) {
        if (answered) return
        report(granted)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun report(granted: Boolean) {
        answered = true
        OpenPush.onPermissionActivityResult(generation, granted)
    }

    private fun currentPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        notificationsEnabled()
    }

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

    internal companion object {
        const val EXTRA_GENERATION = "ai.openpush.android.permission_generation"

        /**
         * Starts the prompt on top of [activity]. False when the merged manifest does not declare this
         * Activity (a host removed it) or the start failed, so the caller can fall back to the legacy prompt.
         * A start Android silently drops is caught by [PermissionPromptWatch].
         */
        fun launch(activity: Activity, generation: Long): Boolean {
            val component = ComponentName(activity, OpenPushPermissionActivity::class.java)
            return runCatching {
                @Suppress("DEPRECATION")
                activity.packageManager.getActivityInfo(component, 0)
                PermissionPromptWatch.launched(activity, generation)
                activity.startActivity(Intent().setComponent(component).putExtra(EXTRA_GENERATION, generation))
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }.isSuccess
        }
    }
}
