package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import android.os.Looper
import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Unity's player activity ships android:hardwareAccelerated="false"; without the flag a transparent
 * WebView paints an opaque white box over the game.
 */
@OptIn(OpenPushInternalApi::class)
internal class PopupHardwareAccelerationTests : IamTestCase() {
    @Test
    fun buildingAMessageTurnsOnHardwareAccelerationForTheHostWindow() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        val presenter = PopupPresenter()

        presenter.preview(activity, message(), "<html><body></body></html>") { }
        shadowOf(Looper.getMainLooper()).idle()

        val flags = activity.window.attributes.flags
        assertTrue((flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0)
        presenter.hide()
    }

    private fun message(): IamMessage = IamMessage(
        id = "iam_1",
        htmlPath = "/v1/apps/demo/iams/iam_1/html",
        layout = "center",
        triggers = emptyList(),
        displayDurationMs = null,
        redisplayLimit = 1,
        redisplayDelayMs = 0,
        endTimeMs = null,
        hasDynamicTriggers = false,
    )
}
