package ai.openpush.android

import android.app.Activity
import android.content.Context

/**
 * Every Kotlin call shape the docs, the examples and the compatibility module use for the overloaded
 * entry points. Never run: compiling this file is the check. [OpenPushInitializeOverloadTests] proves
 * what the ambiguous-looking ones bind to.
 */
@Suppress("unused")
internal object KotlinCallShapes {
    fun initializeForms(context: Context, transport: suspend (OpenPush.Request) -> OpenPush.Response) {
        OpenPush.initialize(context)
        OpenPush.initialize(context, "app-id")
        OpenPush.initWithContext(context, "app-id")
        // A third positional string is the SDK key.
        OpenPush.initialize(context, "app-id", "pk_live_key")
        OpenPush.initialize(context, "app-id", "pk_live_key") { transport(it) }
        OpenPush.initialize(context, "app-id", "pk_live_key", credential = { "credential" })
        OpenPush.initialize(context, "app-id", "pk_live_key", OpenPush.DEFAULT_SERVER_URL)
        OpenPush.initialize(context, "app-id", "pk_live_key", OpenPush.DEFAULT_SERVER_URL, { null }, transport)
        OpenPush.initialize(context, appId = "app-id", sdkKey = "pk_live_key")
        OpenPush.initialize(context, appId = "app-id", sdkKey = "pk_live_key") { transport(it) }
        // The manifest-key form names its extras; a trailing lambda is the transport.
        OpenPush.initialize(context, "app-id", serverURL = OpenPush.DEFAULT_SERVER_URL)
        OpenPush.initialize(context, appId = "app-id", serverURL = "https://push.example.com", credential = { null })
        OpenPush.initialize(context, "app-id", credential = { "credential" })
        OpenPush.initialize(context, appId = "app-id") { transport(it) }
    }

    /** What `onCreate` can call: the callback forms, none of which needs a coroutine. */
    fun permissionFromOnCreate(activity: Activity) {
        OpenPush.Notifications.requestPermission { granted -> check(granted || !granted) }
        OpenPush.Notifications.requestPermission(fallbackToSettings = true) { }
        OpenPush.Notifications.requestPermission(true) { }
        OpenPush.Notifications.requestPermission(activity)
        OpenPush.Notifications.requestPermission(activity, fallbackToSettings = true) { }
    }

    /** The 1.2.1 suspend shapes: with no callback, `requestPermission` suspends and returns the answer. */
    suspend fun permissionInACoroutine(): Boolean {
        val granted: Boolean = OpenPush.Notifications.requestPermission()
        val withFallback: Boolean = OpenPush.Notifications.requestPermission(fallbackToSettings = true)
        val positional: Boolean = OpenPush.Notifications.requestPermission(false)
        return granted && withFallback && positional
    }

    @Suppress("DEPRECATION")
    fun registerForms(token: String, externalId: String?) {
        OpenPush.register(token)
        OpenPush.register(token, externalId)
        OpenPush.register(token, externalId = "player-1")
        OpenPush.login("player-1")
        OpenPush.login("player-1", "auth-hash") { }
    }
}
