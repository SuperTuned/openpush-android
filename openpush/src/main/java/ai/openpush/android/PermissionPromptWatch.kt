package ai.openpush.android

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Notices a permission prompt that was started but never appeared — Android drops an Activity start
 * from an app in the background — so its request does not stay in flight forever. When an app Activity
 * resumes after the host left the screen and the prompt still has not shown, the request completes
 * with the permission the OS reports. Fed by OpenPush's own Activity lifecycle callbacks.
 */
internal object PermissionPromptWatch {
    private var generation = -1L
    private var host: WeakReference<Activity>? = null
    private var shown = false

    // The host left the screen after the launch, or had already stopped when it launched. A host that
    // starts the prompt from onCreate resumes once before the prompt appears; that resume is not a miss.
    private var hostLeft = false
    private val stopped: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    @Synchronized
    fun launched(host: Activity, generation: Long) {
        this.generation = generation
        this.host = WeakReference(host)
        shown = false
        hostLeft = host in stopped
    }

    @Synchronized
    fun shown(generation: Long) {
        if (generation == this.generation) shown = true
    }

    @Synchronized
    fun onStarted(activity: Activity) {
        stopped.remove(activity)
    }

    @Synchronized
    fun onStopped(activity: Activity) {
        stopped.add(activity)
    }

    @Synchronized
    fun onPaused(activity: Activity) {
        if (host?.get() === activity) hostLeft = true
    }

    fun onResumed(activity: Activity) {
        if (activity is OpenPushPermissionActivity) return
        val missed = synchronized(this) {
            if (generation < 0 || shown || !hostLeft) return
            generation.also { generation = -1L }
        }
        OpenPush.completePermissionRequestFromSystem(missed)
    }

    @Synchronized
    fun reset() {
        generation = -1L
        host = null
        shown = false
        hostLeft = false
        stopped.clear()
    }
}
