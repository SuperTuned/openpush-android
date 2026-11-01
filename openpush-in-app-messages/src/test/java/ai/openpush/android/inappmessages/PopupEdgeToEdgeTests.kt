package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPushInternalApi
import android.graphics.Color
import android.graphics.Insets
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import android.widget.FrameLayout
import android.widget.PopupWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * A modal's scrim covers the whole display — camera cutout and system bars included — while its
 * document stays inside the safe area; a banner's window keeps the platform defaults.
 */
@OptIn(OpenPushInternalApi::class)
internal class PopupEdgeToEdgeTests : IamTestCase() {

    @Test
    fun modalWindowsAreUnclippedAndLaidOutInScreen() {
        listOf("center", "full", "carousel").forEach { location ->
            val surface = surface()

            IamLayout.apply(surface.window, surface.container, surface.content, location, 320, 2000)

            val params = surface.params()
            assertFalse(location, surface.window.isClippingEnabled)
            assertTrue(location, surface.window.isLaidOutInScreen)
            assertTrue(location, params.flags and FLAG_LAYOUT_NO_LIMITS != 0)
            assertTrue(location, params.flags and FLAG_LAYOUT_IN_SCREEN != 0)
            assertEquals(location, LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
            surface.window.dismiss()
        }
    }

    @Test
    @Config(sdk = [30])
    fun onApi30AModalUsesTheWholeCutoutAndFitsNoInsets() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        val params = surface.params()
        assertEquals(LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        assertEquals(0, params.fitInsetsTypes)
        assertTrue(params.flags and FLAG_LAYOUT_NO_LIMITS != 0)
        assertTrue(params.flags and FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Test
    @Config(sdk = [28])
    fun onApi28AModalUsesTheShortEdgeCutout() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "center", 320, 2000)

        val params = surface.params()
        assertEquals(LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, params.layoutInDisplayCutoutMode)
        assertTrue(params.flags and FLAG_LAYOUT_NO_LIMITS != 0)
        assertTrue(params.flags and FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Test
    @Config(sdk = [30])
    fun laterUpdatesKeepTheModalWindowLayout() {
        val surface = surface()
        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        surface.window.update(0, 0, MATCH_PARENT, MATCH_PARENT)
        surface.window.update()
        // A multi-page document reports its layout again for every page.
        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        val params = surface.params()
        assertEquals(LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, params.layoutInDisplayCutoutMode)
        assertEquals(0, params.fitInsetsTypes)
        assertTrue(params.flags and FLAG_LAYOUT_NO_LIMITS != 0)
        assertTrue(params.flags and FLAG_LAYOUT_IN_SCREEN != 0)
    }

    @Test
    @Config(sdk = [30])
    fun bannersKeepThePlatformDefaults() {
        listOf("top", "bottom").forEach { location ->
            val surface = surface()
            val fitBefore = surface.params().fitInsetsTypes

            IamLayout.apply(surface.window, surface.container, surface.content, location, 120, 2000)

            val params = surface.params()
            assertTrue(location, surface.window.isClippingEnabled)
            assertFalse(location, surface.window.isLaidOutInScreen)
            assertEquals(location, 0, params.flags and FLAG_LAYOUT_NO_LIMITS)
            assertEquals(location, 0, params.flags and FLAG_LAYOUT_IN_SCREEN)
            assertEquals(location, LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, params.layoutInDisplayCutoutMode)
            assertEquals(location, fitBefore, params.fitInsetsTypes)
            assertFalse(location, surface.window.isFocusable)
            assertTrue(location, surface.window.isOutsideTouchable)
            assertEquals(location, Color.TRANSPARENT, surface.containerColor())
            surface.window.dismiss()
        }
    }

    @Test
    @Config(sdk = [30])
    fun aBannerPageAfterAModalPageGetsTheDefaultsBack() {
        val surface = surface()
        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        IamLayout.apply(surface.window, surface.container, surface.content, "top", 120, 2000)

        val params = surface.params()
        assertTrue(surface.window.isClippingEnabled)
        assertEquals(0, params.flags and FLAG_LAYOUT_NO_LIMITS)
        assertEquals(0, params.flags and FLAG_LAYOUT_IN_SCREEN)
        assertEquals(LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, params.layoutInDisplayCutoutMode)
        assertEquals(WindowInsets.Type.systemBars(), params.fitInsetsTypes)
    }

    @Test
    @Config(sdk = [30])
    fun theScrimIsNotPaddedWhileTheContentClearsTheCutout() {
        listOf("full", "carousel").forEach { location ->
            val surface = surface()
            IamLayout.apply(surface.window, surface.container, surface.content, location, -1, 2000)

            surface.container.dispatchApplyWindowInsets(cutoutInsets(top = 83))

            assertNoPadding(location, surface.container)
            val margins = surface.content.layoutParams as FrameLayout.LayoutParams
            assertEquals(location, 83, margins.topMargin)
            assertEquals(location, 0, margins.leftMargin)
            assertEquals(location, 0, margins.rightMargin)
            assertEquals(location, 0, margins.bottomMargin)
            assertEquals(location, IamLayout.SCRIM, surface.containerColor())
            surface.window.dismiss()
        }
    }

    @Test
    @Config(sdk = [30])
    fun theContentClearsTheSystemBarsAsWellAsTheCutout() {
        val surface = surface()
        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, 83, 0, 0))
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 60, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 126))
            .build()
        surface.container.dispatchApplyWindowInsets(insets)

        assertNoPadding("full", surface.container)
        val margins = surface.content.layoutParams as FrameLayout.LayoutParams
        assertEquals(83, margins.topMargin)
        assertEquals(126, margins.bottomMargin)
    }

    @Test
    @Config(sdk = [29])
    fun beforeApi30TheSafeAreaComesFromTheSystemWindowInsets() {
        val surface = surface()
        IamLayout.apply(surface.window, surface.container, surface.content, "full", -1, 2000)

        @Suppress("DEPRECATION")
        val insets = WindowInsets.Builder().setSystemWindowInsets(Insets.of(0, 83, 0, 126)).build()
        surface.container.dispatchApplyWindowInsets(insets)

        assertNoPadding("full", surface.container)
        val margins = surface.content.layoutParams as FrameLayout.LayoutParams
        assertEquals(83, margins.topMargin)
        assertEquals(126, margins.bottomMargin)
        assertEquals(LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, surface.params().layoutInDisplayCutoutMode)
    }

    @Test
    @Config(sdk = [30])
    fun aCenterMessageStaysCentredInTheSafeArea() {
        val surface = surface()
        IamLayout.apply(surface.window, surface.container, surface.content, "center", 320, 2000)

        surface.container.dispatchApplyWindowInsets(cutoutInsets(top = 83))

        assertNoPadding("center", surface.container)
        val margins = surface.content.layoutParams as FrameLayout.LayoutParams
        assertEquals(Gravity.CENTER, margins.gravity)
        assertEquals(83, margins.topMargin)
        assertEquals(Math.round(320 * activity.resources.displayMetrics.density), margins.height)
    }

    @Test
    fun aCenterMessageLaysOutBehindTheBarsWithoutHidingThem() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "center", 320, 2000)

        @Suppress("DEPRECATION")
        val flags = surface.container.systemUiVisibility
        @Suppress("DEPRECATION")
        run {
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0)
            assertTrue(flags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0)
            assertEquals(0, flags and View.SYSTEM_UI_FLAG_FULLSCREEN)
            assertEquals(0, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            assertEquals(0, flags and View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        assertNotEquals(IamLayout.FULLSCREEN_FLAGS, flags)
    }

    @Test
    fun aBannerSetsNoSystemUiFlagsAndNoInsetsListener() {
        val surface = surface()

        IamLayout.apply(surface.window, surface.container, surface.content, "top", 120, 2000)
        surface.container.dispatchApplyWindowInsets(cutoutInsets(top = 83))

        @Suppress("DEPRECATION")
        assertEquals(0, surface.container.systemUiVisibility)
        val margins = surface.content.layoutParams as FrameLayout.LayoutParams
        assertEquals(0, margins.topMargin)
    }

    private fun cutoutInsets(top: Int): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, top, 0, 0))
        .build()

    private fun assertNoPadding(location: String, view: View) {
        assertEquals(location, 0, view.paddingLeft)
        assertEquals(location, 0, view.paddingTop)
        assertEquals(location, 0, view.paddingRight)
        assertEquals(location, 0, view.paddingBottom)
    }

    private class Surface(val window: PopupWindow, val container: FrameLayout, val content: View) {
        fun params(): WindowManager.LayoutParams =
            window.contentView.rootView.layoutParams as WindowManager.LayoutParams

        fun containerColor(): Int = (container.background as android.graphics.drawable.ColorDrawable).color
    }

    /** What [PopupPresenter] builds, without the WebView. */
    private fun surface(): Surface {
        val content = View(activity)
        val container = FrameLayout(activity)
        container.setBackgroundColor(Color.TRANSPARENT)
        container.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val window = PopupWindow(container, MATCH_PARENT, MATCH_PARENT, false)
        window.showAtLocation(activity.window.decorView, Gravity.TOP or Gravity.START, 0, 0)
        return Surface(window, container, content)
    }
}
