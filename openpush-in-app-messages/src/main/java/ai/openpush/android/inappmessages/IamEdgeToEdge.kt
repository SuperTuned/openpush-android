package ai.openpush.android.inappmessages

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import kotlin.math.max

/**
 * Lets a modal's scrim reach every edge of the screen while its content stays clear of them.
 *
 * A `PopupWindow` with platform defaults is clipped to its parent's frame and kept out of the
 * display cutout, so the dimming stopped short of the camera band and, on a `center` message, of
 * the status and navigation bars. A modal therefore opts out of clipping, lays out in screen
 * coordinates, may use the cutout area, and fits no system insets: the window is the whole display.
 * The document is then kept inside the safe area by margins on the content view; the scrim itself
 * is never padded.
 *
 * A banner keeps the platform defaults; [retract] only undoes this on a window a modal page of
 * the same document extended first.
 */
internal object IamEdgeToEdge {
    /** The cutout mode a modal asks for on this API level. */
    val cutoutMode: Int
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else -> 0
        }

    /**
     * The flags a modal sets on [window] before `PopupWindow.update` pushes them: no clipping
     * (`FLAG_LAYOUT_NO_LIMITS`) and, where the platform exposes it, laid out in screen.
     * `PopupWindow` recomputes both from these fields on every `update`, so they hold.
     */
    fun prepare(window: PopupWindow) {
        window.isClippingEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setIsLaidOutInScreen(true)
    }

    /**
     * Once the window exists: the parts of its layout `PopupWindow` has no setter for, written on
     * the popup root's own layout params and pushed to the window manager. `PopupWindow.update`
     * edits that same params object and never resets these fields, so later updates keep them.
     *
     * Before API 29 `FLAG_LAYOUT_IN_SCREEN` is set here directly; `PopupWindow` never clears it.
     */
    fun extend(window: PopupWindow) {
        val root = window.contentView?.rootView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) params.layoutInDisplayCutoutMode = cutoutMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = 0
        val windowManager = root.context.getSystemService(WindowManager::class.java) ?: return
        windowManager.updateViewLayout(root, params)
    }

    /**
     * Puts back the defaults [prepare] and [extend] changed, for a banner shown on a window an
     * earlier modal page extended. A window that was never extended is left untouched.
     */
    fun retract(window: PopupWindow, container: FrameLayout) {
        container.setOnApplyWindowInsetsListener(null)
        if (window.isClippingEnabled) return
        window.isClippingEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setIsLaidOutInScreen(false)
        val root = window.contentView?.rootView ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = WindowInsets.Type.systemBars()
    }

    /**
     * Keeps [content] inside the safe area of [container]: its margins follow the system bars and
     * the display cutout, now from the last insets the container saw and again on every new
     * dispatch. The container keeps no padding, so the scrim it paints still fills the window.
     */
    fun keepSafe(container: FrameLayout, content: View, params: FrameLayout.LayoutParams) {
        container.rootWindowInsets?.let { setMargins(params, safeArea(it)) }
        container.setOnApplyWindowInsetsListener { _, insets ->
            val current = content.layoutParams as? FrameLayout.LayoutParams
            if (current != null) {
                setMargins(current, safeArea(insets))
                content.layoutParams = current
            }
            consumed(insets)
        }
        container.requestApplyInsets()
    }

    /** The edges covered by the system bars or the cutout, in pixels. */
    fun safeArea(insets: WindowInsets): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(safe.left, safe.top, safe.right, safe.bottom)
        }
        @Suppress("DEPRECATION")
        val area = Rect(
            insets.systemWindowInsetLeft,
            insets.systemWindowInsetTop,
            insets.systemWindowInsetRight,
            insets.systemWindowInsetBottom,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let { cutout ->
                area.left = max(area.left, cutout.safeInsetLeft)
                area.top = max(area.top, cutout.safeInsetTop)
                area.right = max(area.right, cutout.safeInsetRight)
                area.bottom = max(area.bottom, cutout.safeInsetBottom)
            }
        }
        return area
    }

    private fun setMargins(params: FrameLayout.LayoutParams, area: Rect) {
        params.setMargins(area.left, area.top, area.right, area.bottom)
    }

    /** The insets are spent on the content's margins; the WebView must not inset the page again. */
    @Suppress("DEPRECATION")
    private fun consumed(insets: WindowInsets): WindowInsets = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> WindowInsets.CONSUMED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> insets.consumeSystemWindowInsets().consumeDisplayCutout()
        else -> insets.consumeSystemWindowInsets()
    }
}
