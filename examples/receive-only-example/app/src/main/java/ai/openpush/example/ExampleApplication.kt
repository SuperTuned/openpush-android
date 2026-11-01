package ai.openpush.example

import ai.openpush.android.DeviceCredentialStore
import ai.openpush.android.OpenPush
import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Initializes OpenPush once per process, logs notification opens, and registers the provider token
 * only after this installation is linked.
 *
 * This example deliberately contains no account-linking or send implementation.
 * The host app owns sign-in; it saves the resulting device credential through
 * [credentialStore] and then calls [registerIfLinked].
 */
class ExampleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        credentialStore = DeviceCredentialStore(this, ExampleConfiguration.CREDENTIAL_SERVICE)
        ExampleNotificationFixtures.createChannels(this)

        OpenPush.onLog = { message ->
            Log.i(TAG, message)
            ExampleEventRelay.emit(LabEventKind.SDK, "SDK log", message)
        }
        OpenPush.onRegistered = {
            Log.i(TAG, "this device is registered for push")
            ExampleEventRelay.emit(
                LabEventKind.SUBSCRIPTION,
                "Device registered",
                "OpenPush accepted the latest Android subscription state.",
            )
        }
        OpenPush.onRegistrationFailed = { detail ->
            Log.w(TAG, detail)
            ExampleEventRelay.emit(LabEventKind.ERROR, "Registration failed", detail)
        }

        startIfPermitted()
    }

    companion object {
        private const val TAG = "OpenPushExample"

        /** Keystore-backed storage for the linked installation's device credential. */
        lateinit var credentialStore: DeviceCredentialStore
            private set

        /** True once the host app has saved a device credential for this installation. */
        fun isLinked() = credentialStore.load()?.isNotBlank() == true

        /**
         * Starts SDK-owned work only after the host app's saved consent gate allows it. OpenPush does
         * not persist or interpret consent; this is deliberately application-owned example code.
         */
        @Synchronized
        fun startIfPermitted(): Boolean {
            val application = currentApplication ?: return false
            if (!ExampleConfiguration.hasLiveCredentials) return false
            if (!ExampleConsentGate.permitsSdkStart(application)) {
                ExampleEventRelay.emit(
                    LabEventKind.SDK,
                    "SDK waiting for consent",
                    "The host app has not initialized OpenPush, so no SDK network work has started.",
                )
                return false
            }
            if (!OpenPush.isInitialized) {
                val configuration = ExampleConfiguration.SDK_CONFIGURATION
                Log.i(
                    TAG,
                    "configuration · ${configuration.platform} · ${configuration.serverURL} · " +
                        configuration.tokenKey,
                )
                OpenPush.initialize(
                    application,
                    appId = configuration.appID,
                    sdkKey = configuration.sdkKey,
                    serverURL = configuration.serverURL,
                    credential = { credentialStore.load() },
                )
            }
            registerIfLinked()
            return OpenPush.isInitialized
        }

        /**
         * Asks the provider for the current token and registers it. Call this at
         * launch and again as soon as your own sign-in flow has saved a credential.
         */
        fun registerIfLinked() {
            if (!OpenPush.isInitialized) return
            if (!isLinked()) {
                Log.i(TAG, "not registering yet — this device is not linked")
                return
            }
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> OpenPush.register(token) }
                .addOnFailureListener { error ->
                    Log.w(TAG, "no provider token available: ${error.message}")
                }
        }

        private var currentApplication: ExampleApplication? = null
    }

    init {
        currentApplication = this
    }
}
