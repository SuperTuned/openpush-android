package ai.openpush.android.onesignal

import ai.openpush.android.OpenPush
import android.app.Activity
import android.content.Context

/**
 * The facade's overloaded call shapes in Kotlin. Never run: compiling this file is the check, and
 * [FacadeInitializeOverloadTests] proves what the third positional string binds to.
 */
@Suppress("unused")
internal object FacadeCallShapes {
    fun initializeForms(context: Context, transport: suspend (OpenPush.Request) -> OpenPush.Response) {
        OneSignal.initWithContext(context, "app-id")
        OneSignal.initialize(context, "app-id")
        OneSignal.initialize(context, "app-id", "pk_live_key")
        OneSignal.initialize(context, "app-id", "pk_live_key", OneSignal.DEFAULT_SERVER_URL)
        OneSignal.initialize(context, "app-id", "pk_live_key") { transport(it) }
        OneSignal.initialize(context, "app-id", serverURL = OneSignal.DEFAULT_SERVER_URL)
        OneSignal.initialize(context, appId = "app-id", credential = { null }) { transport(it) }
    }

    fun permissionFromOnCreate(activity: Activity) {
        OneSignal.Notifications.requestPermission { }
        OneSignal.Notifications.requestPermission(true) { }
        OneSignal.Notifications.requestPermission(activity, true) { }
    }

    suspend fun permissionInACoroutine(): Boolean {
        val granted: Boolean = OneSignal.Notifications.requestPermission()
        return granted && OneSignal.Notifications.requestPermission(true)
    }

    @Suppress("DEPRECATION")
    fun registerForms(externalId: String?) {
        OneSignal.register("provider-token")
        OneSignal.register("provider-token", externalId)
    }
}
