package ai.openpush.example

import ai.openpush.android.OpenPush
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Hosts the Material 3 feature lab and forwards notification taps into OpenPush. The permission prompt
 * answers in OpenPush's own Activity, so nothing here forwards `onRequestPermissionsResult`.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: ExampleLabModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ExampleLabModel(this)
        setContent { OpenPushFeatureLab(model) }
        model.start()
        reportClick(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.onResume()
    }

    override fun onDestroy() {
        if (::model.isInitialized) model.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportClick(intent)
    }

    private fun reportClick(intent: Intent?) {
        val messageId = intent
            ?.getStringExtra(ExampleMessagingService.EXTRA_MESSAGE_ID)
            ?: return
        val actionId = intent.getStringExtra(ExampleMessagingService.EXTRA_ACTION_ID)
        val extras = intent.extras
        val customBundle = extras?.getBundle(ExampleNotificationFixtures.EXTRA_CUSTOM_DATA)
        val customData = customBundle
            ?.keySet()
            ?.mapNotNull { key ->
                customBundle.getString(key)?.let { key to it }
            }
            ?.toMap()
            .orEmpty()
        val data = extras
            ?.keySet()
            ?.filterNot {
                it == ExampleMessagingService.EXTRA_MESSAGE_ID ||
                    it == ExampleMessagingService.EXTRA_ACTION_ID ||
                    it == ExampleNotificationFixtures.EXTRA_CUSTOM_DATA
            }
            ?.mapNotNull { key -> extras.get(key)?.toString()?.let { key to it } }
            ?.toMap()
            .orEmpty() + customData
        if (::model.isInitialized) {
            model.handleNotificationIntent(messageId, actionId, data)
        } else {
            OpenPush.Notifications.processClick(
                buildMap {
                    put("op_message_id", messageId)
                    actionId?.let { put("op_action_id", it) }
                    putAll(data)
                },
            )
        }
        intent.removeExtra(ExampleMessagingService.EXTRA_MESSAGE_ID)
        intent.removeExtra(ExampleMessagingService.EXTRA_ACTION_ID)
    }
}
