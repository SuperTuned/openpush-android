package ai.openpush.android.fcm

import android.content.Context
import android.content.pm.PackageManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Makes sure the default Firebase app exists. `google-services.json` creates it at process start;
 * without that file, the three `ai.openpush.fcm.*` manifest values below create it instead.
 */
internal object OpenPushFirebaseApp {
    const val META_PROJECT_ID = "ai.openpush.fcm.project_id"
    const val META_APPLICATION_ID = "ai.openpush.fcm.application_id"
    const val META_API_KEY = "ai.openpush.fcm.api_key"

    const val MISSING_CONFIGURATION =
        "no Firebase configuration — add google-services.json, or the $META_PROJECT_ID, " +
            "$META_APPLICATION_ID and $META_API_KEY manifest values"

    @Synchronized
    fun ensure(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (FirebaseApp.getApps(appContext).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) return true
        val options = manifestOptions(appContext) ?: return false
        return runCatching { FirebaseApp.initializeApp(appContext, options) }.isSuccess
    }

    // The sender id is left unset: Firebase derives it from the application id's project number.
    private fun manifestOptions(context: Context): FirebaseOptions? {
        val projectId = manifestValue(context, META_PROJECT_ID) ?: return null
        val applicationId = manifestValue(context, META_APPLICATION_ID) ?: return null
        val apiKey = manifestValue(context, META_API_KEY) ?: return null
        return FirebaseOptions.Builder()
            .setProjectId(projectId)
            .setApplicationId(applicationId)
            .setApiKey(apiKey)
            .build()
    }

    fun manifestValue(context: Context, name: String): String? = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData?.get(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
