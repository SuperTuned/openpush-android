package ai.openpush.android.fcm

import ai.openpush.android.OpenPush
import android.content.Context
import androidx.startup.Initializer

/**
 * Runs at process start. Installs the module's notification-shade manager, so
 * `OpenPush.Notifications.remove`/`removeGroup` reach notifications an earlier process posted before
 * this one renders anything. Creates the default Firebase app from manifest values when there is no
 * `google-services.json`, then installs [OpenPushFirebaseTokenProvider] so every `initialize`
 * registers the device without the host forwarding a token.
 *
 * Set `ai.openpush.fcm.automatic_token` to `false` in the manifest to forward tokens yourself. Set
 * `ai.openpush.fcm.delivery` to `forward` when your own `FirebaseMessagingService` receives pushes and
 * calls [OpenPushMessagingService.handle]; see [OpenPushDeliveryMode].
 */
class OpenPushFcmInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val appContext = context.applicationContext ?: context
        installOpenPushShadeManager()
        OpenPushFirebaseApp.ensure(appContext)
        OpenPushDeliveryMode.apply(appContext)
        if (!automaticToken(appContext)) return
        if (OpenPush.tokenProvider == null) OpenPush.tokenProvider = OpenPushFirebaseTokenProvider()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    internal companion object {
        const val META_AUTOMATIC_TOKEN = "ai.openpush.fcm.automatic_token"

        fun automaticToken(context: Context): Boolean =
            !OpenPushFirebaseApp.manifestValue(context, META_AUTOMATIC_TOKEN).equals("false", ignoreCase = true)
    }
}
