package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import kotlin.math.roundToInt

/**
 * What the four display locations do to the surface.
 *
 * The one that matters for the host app is the banner: a top or bottom banner is a window the size
 * of its own content, non-focusable and outside-touchable, so a user can go on using the app behind
 * it. A center or full-screen message is the opposite — it takes focus and darkens everything
 * behind it, because it is asking for an answer.
 *
 * [IamLayout.paramsFor] is the whole decision and is a pure function, so it is tested on its own
 * values; [IamLayout.apply] is tested against a real `PopupWindow` to prove those values reach the
 * window. The presenter's own `applyLayout` is the two lines that find this surface among its
 * fields.
 */
@OptIn(OpenPushInternalApi::class)
internal class PopupPresenterTests : IamTestCase() {

    @Test
    fun aFinishedDocumentMovesItsCloseControlOutOfTheScrollingCard() {
        val view = WebView(activity)
        val client = IamDocumentClient { }

        client.onPageFinished(view, "https://example.invalid/doc")

        val script = shadowOf(view).lastEvaluatedJavascript
        assertTrue(script.contains(".op-card > .op-close"))
        assertTrue(script.contains("root.appendChild(close)"))
        assertTrue(script.contains("data-op-close-pinned"))
    }

    @Test
    fun aTopBannerIsSizedToItsContentAndLetsTouchesThrough() {
        val params = IamLayout.paramsFor("top", heightDp = 120, density = 2f, screenHeightPx = 2000)

        assertEquals(240, params.popupHeightPx)
        assertEquals(0, params.popupYPx)
        assertEquals(Gravity.TOP, params.gravity)
        assertFalse(params.focusable)
        assertTrue(params.outsideTouchable)
        assertEquals(Color.TRANSPARENT, params.scrim)
        assertFalse(params.fullscreen)
    }

    @Test
    fun aBottomBannerSitsOnTheBottomEdge() {
        val params = IamLayout.paramsFor("bottom", heightDp = 120, density = 2f, screenHeightPx = 2000)

        assertEquals(240, params.popupHeightPx)
        assertEquals(1760, params.popupYPx)
        assertEquals(Gravity.BOTTOM, params.gravity)
        assertFalse(params.focusable)
        assertTrue(params.outsideTouchable)
    }

    @Test
    fun aBannerTallerThanTheScreenIsCappedAtTheScreen() {
        val params = IamLayout.paramsFor("bottom", heightDp = 4000, density = 2f, screenHeightPx = 500)

        assertEquals(500, params.popupHeightPx)
        assertEquals(0, params.popupYPx)
    }

    @Test
    fun aBannerWithNoReportedHeightWrapsItsContent() {
        val params = IamLayout.paramsFor("top", heightDp = 0, density = 2f, screenHeightPx = 2000)

        assertEquals(WRAP_CONTENT, params.popupHeightPx)
    }

    @Test
    fun aCenterMessageGetsAScrimAndKeepsFocus() {
        val params = IamLayout.paramsFor("center", heightDp = 320, density = 2f, screenHeightPx = 2000)

        assertEquals(MATCH_PARENT, params.popupHeightPx)
        assertEquals(640, params.contentHeightPx)
        assertEquals(Gravity.CENTER, params.gravity)
        assertTrue(params.focusable)
        assertFalse(params.outsideTouchable)
        assertEquals(IamLayout.SCRIM, params.scrim)
        assertFalse(params.fullscreen)
    }

    @Test
    fun aCenterMessageWithNoReportedHeightFillsTheScreen() {
        val params = IamLayout.paramsFor("center", heightDp = 0, density = 2f, screenHeightPx = 2000)

        assertEquals(MATCH_PARENT, params.contentHeightPx)
    }

    @Test
    fun fullCarouselAndAnythingUnknownTakeTheWholeScreen() {
        listOf("full", "carousel", "", "banner_left").forEach { location ->
            val params = IamLayout.paramsFor(location, heightDp = -1, density = 2f, screenHeightPx = 2000)

            assertEquals(location, MATCH_PARENT, params.popupHeightPx)
            assertEquals(location, MATCH_PARENT, params.contentHeightPx)
            assertEquals(location, IamLayout.SCRIM, params.scrim)
            assertTrue(location, params.focusable)
            assertTrue(location, params.fullscreen)
        }
    }

    @Test
    fun applyingABannerResizesTheWindowAndLeavesItTransparent() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "top", 120, 2000)

        assertEquals((120 * density()).roundToInt(), surface.window.height)
        assertEquals(MATCH_PARENT, surface.window.width)
        assertFalse(surface.window.isFocusable)
        assertTrue(surface.window.isOutsideTouchable)
        assertEquals(Color.TRANSPARENT, colorOf(surface.container))
        assertFalse(surface.fullscreenAsked())
    }

    @Test
    fun applyingACenterLayoutPaintsTheScrimAndKeepsFocus() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "center", 320, 2000)

        assertEquals(MATCH_PARENT, surface.window.height)
        assertTrue(surface.window.isFocusable)
        assertFalse(surface.window.isOutsideTouchable)
        assertEquals(IamLayout.SCRIM, colorOf(surface.container))
        assertEquals((320 * density()).roundToInt(), surface.content.layoutParams.height)
    }

    @Test
    fun applyingAFullLayoutAsksForTheImmersiveFlags() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        assertEquals(MATCH_PARENT, surface.window.height)
        assertEquals(MATCH_PARENT, surface.content.layoutParams.height)
        assertEquals(IamLayout.SCRIM, colorOf(surface.container))
        assertTrue(surface.fullscreenAsked())
    }

    @Test
    fun thePresenterPutsAMessageUpAndTakesTheLayoutItIsGiven() {
        val events = mutableListOf<PresenterEvent>()
        val popupPresenter = PopupPresenter()

        popupPresenter.show(message(), "https://example.invalid/doc", emptyMap()) { events += it }
        popupPresenter.applyLayout("top", 120, false)
        popupPresenter.hide()

        assertEquals(emptyList<PresenterEvent>(), events)
    }

    private class Surface(val window: PopupWindow, val container: FrameLayout, val content: View) {
        @Suppress("DEPRECATION")
        fun fullscreenAsked(): Boolean = container.systemUiVisibility == IamLayout.FULLSCREEN_FLAGS
    }

    /** A stand-in for what [PopupPresenter.show] builds, without the WebView. */
    private fun surface(): Surface {
        val content = View(activity)
        val container = FrameLayout(activity)
        container.setBackgroundColor(Color.TRANSPARENT)
        container.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val window = PopupWindow(container, MATCH_PARENT, MATCH_PARENT, false)
        window.showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.START, 0, 0)
        return Surface(window, container, content)
    }

    private fun density(): Float = activity.resources.displayMetrics.density

    private fun colorOf(view: View): Int = (view.background as ColorDrawable).color

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
