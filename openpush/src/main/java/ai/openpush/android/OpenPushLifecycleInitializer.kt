package ai.openpush.android

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

/** Installs process lifecycle observation before the host's first Activity starts. */
class OpenPushLifecycleInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        OpenPush.observeLifecycle(context.applicationContext as? Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
