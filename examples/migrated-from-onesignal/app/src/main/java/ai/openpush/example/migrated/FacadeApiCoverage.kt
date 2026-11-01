package ai.openpush.example.migrated

import ai.openpush.android.ListenerHandle
import ai.openpush.android.onesignal.*
import android.app.Activity
import android.content.Context
import java.util.UUID

/**
 * Compile-checked recipes for facade members that are not sensible to execute together at startup.
 * The running sample uses the common migration path; these grouped functions make removal of any
 * supported facade forward fail this sample's build.
 */
@Suppress("unused")
internal object FacadeApiCoverage {
    fun initializeExplicitly(context: Context, appId: String, sdkKey: String) {
        OneSignal.initialize(context, appId, sdkKey, OneSignal.DEFAULT_SERVER_URL)
        OneSignal.initialize(
            context,
            appId,
            serverURL = OneSignal.DEFAULT_SERVER_URL,
        )
        OneSignal.isInitialized
        OneSignal.sdkVersion
    }

    // The deprecated two-argument register stays callable for migrated apps.
    @Suppress("DEPRECATION")
    suspend fun identity(externalId: String, authHash: String?) {
        OneSignal.login(externalId, authHash) { }
        OneSignal.loginSuspend(externalId, authHash)
        OneSignal.logout { }
        OneSignal.logoutSuspend()
        OneSignal.register("provider-token", externalId)
    }

    fun userData() {
        OneSignal.User.addTag("plan", "pro")
        OneSignal.User.addTags(mapOf("tier" to "gold"))
        OneSignal.User.removeTag("plan")
        OneSignal.User.removeTags(listOf("tier"))
        OneSignal.User.getTags()
        OneSignal.User.trackEvent("sample_event", mapOf("source" to "migration"))
        OneSignal.User.addAlias("account", "example")
        OneSignal.User.addAliases(mapOf("player" to "42"))
        OneSignal.User.removeAlias("account")
        OneSignal.User.removeAliases(listOf("player"))
        OneSignal.User.addSms("+14155550123")
        OneSignal.User.removeSms("+14155550123")
        OneSignal.User.addEmail("player@example.com")
        OneSignal.User.removeEmail("player@example.com")
        OneSignal.User.externalId
    }

    fun subscription(activity: Activity) {
        val subscription = OneSignal.User.pushSubscription
        subscription.id
        subscription.token
        subscription.optedIn
        subscription.snapshot()
        subscription.optIn()
        subscription.optIn(activity)
        subscription.optOut()
        val state = subscription.addObserver { }
        subscription.removeObserver(state)
        val change = subscription.addChangeObserver { }
        subscription.removeChangeObserver(change)
    }

    suspend fun notifications(activity: Activity) {
        OneSignal.Notifications.PERMISSION_REQUEST_CODE
        OneSignal.Notifications.permission
        OneSignal.Notifications.canRequestPermission
        OneSignal.Notifications.requestPermission(false) { }
        OneSignal.Notifications.requestPermission(activity, false) { }
        val granted: Boolean = OneSignal.Notifications.requestPermission(false)
        if (granted) OneSignal.Notifications.onPermissionResult(0, intArrayOf())

        val permission: ListenerHandle = OneSignal.Notifications.addPermissionObserver { }
        OneSignal.Notifications.removePermissionObserver(permission)
        val click: ListenerHandle = OneSignal.Notifications.addClickListener { }
        OneSignal.Notifications.removeClickListener(click)
        val foreground: ListenerHandle = OneSignal.Notifications.addForegroundLifecycleListener { }
        OneSignal.Notifications.removeForegroundLifecycleListener(foreground)
        if (OneSignal.Notifications.beginReceived(emptyMap())) OneSignal.Notifications.finishReceived(emptyMap())
        OneSignal.Notifications.processForegroundMessage(emptyMap())
        OneSignal.Notifications.processClick(emptyMap())
        OneSignal.Notifications.clearAllNotifications()
        OneSignal.Notifications.removeNotification(42)
        OneSignal.Notifications.removeGroupedNotifications("account-updates")
    }

    fun inAppMessages() {
        OneSignal.InAppMessages.paused = true
        OneSignal.InAppMessages.paused = false
        OneSignal.InAppMessages.addTrigger("plan", "pro")
        OneSignal.InAppMessages.addTriggers(mapOf("level" to 7, "ready" to true))
        OneSignal.InAppMessages.removeTrigger("plan")
        OneSignal.InAppMessages.removeTriggers(listOf("level", "ready"))
        OneSignal.InAppMessages.clearTriggers()
        val lifecycle: UUID = OneSignal.InAppMessages.addLifecycleListener { }
        OneSignal.InAppMessages.removeLifecycleListener(lifecycle)
        val click: UUID = OneSignal.InAppMessages.addClickListener { }
        OneSignal.InAppMessages.removeClickListener(click)
    }

    fun outcomes() {
        OneSignal.Session.addOutcome("tutorial")
        OneSignal.Session.addUniqueOutcome("first_open")
        OneSignal.Session.addOutcomeWithValue("tutorial_progress", 1f)
    }
}
