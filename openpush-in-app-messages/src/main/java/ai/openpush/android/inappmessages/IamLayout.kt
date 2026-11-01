package ai.openpush.android.inappmessages

import ai.openpush.android.OpenPush
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.PopupWindow
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a `displayLocation` means on screen.
 *
 * The server names four: `top`, `bottom`, `center` and `full`. They fall into two kinds, and the
 * difference is whether the message is asking for the user's attention or merely offering it.
 *
 * - **A banner** (`top`, `bottom`) is the app's own screen with a strip on it. Its window is exactly
 *   as tall as the document says it is, so everything outside that strip is still the app: the
 *   window takes no focus and is outside-touchable, which is what makes a tap on the rest of the
 *   screen reach the Activity instead of being swallowed. A full-screen window cannot do this —
 *   there is no "outside" to touch — so the sizing is not cosmetic.
 * - **A modal** (`center`, `full`) is asking a question. It takes focus so the back key closes it,
 *   and its container is painted with [SCRIM] so the app behind it reads as out of play. The scrim
 *   covers the whole display, camera cutout and system bars included ([IamEdgeToEdge]), while the
 *   document is kept inside the safe area. `full` additionally asks for the immersive system-UI
 *   flags, because a document designed to fill the screen should not be cut in half by the
 *   navigation bar; `center` only lays out behind the bars and leaves them showing.
 *
 * [paramsFor] is the whole decision and touches nothing, so it can be read and tested as the table
 * it is. [apply] is the part that has to talk to a live window.
 */
internal object IamLayout {
    /** The dimming behind a `center` or `full` message. */
    const val SCRIM: Int = 0xBB000000.toInt()

    /**
     * Immersive flags for a `full` message. Deprecated in API 30 in favour of `WindowInsetsController`,
     * which needs a window this SDK does not own — the popup's own decor view is not reachable — so
     * the view-level flags are what is left at `minSdk 24`.
     */
    @Suppress("DEPRECATION")
    const val FULLSCREEN_FLAGS: Int = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    /**
     * Layout-only flags for a `center` message: its scrim is drawn behind the status and navigation
     * bars, which stay visible. From API 30 the window's `fitInsetsTypes = 0` does the same.
     */
    @Suppress("DEPRECATION")
    const val BEHIND_BARS_FLAGS: Int = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

    /**
     * The layout for one `rendering_complete`.
     *
     * [heightDp] is the document's own measurement of itself (`pageMetaData.rect.height`); anything
     * at or below zero means it did not report one. [screenHeightPx] caps a banner, because a
     * document that measures itself taller than the screen would otherwise cover the app it is
     * supposed to be sitting on top of.
     */
    fun paramsFor(location: String, heightDp: Int, density: Float, screenHeightPx: Int): IamLayoutParams {
        val heightPx = heightPx(heightDp, density, screenHeightPx)
        return when (location.lowercase()) {
            "top" -> banner(Gravity.TOP, heightPx, popupYPx = 0)
            "bottom" -> banner(
                Gravity.BOTTOM,
                heightPx,
                // Nothing to subtract when the document reported no height: the banner starts at the
                // top and [apply] moves it down once the container has measured itself.
                popupYPx = if (heightPx > 0) (screenHeightPx - heightPx).coerceAtLeast(0) else 0,
            )
            "center" -> IamLayoutParams(
                gravity = Gravity.CENTER,
                popupHeightPx = MATCH_PARENT,
                popupYPx = 0,
                contentHeightPx = if (heightPx > 0) heightPx else MATCH_PARENT,
                focusable = true,
                outsideTouchable = false,
                scrim = SCRIM,
                fullscreen = false,
            )
            // `full`, `carousel`, and anything a newer server invents: fill the screen rather than
            // guess. An unknown location is still a message the user is meant to see.
            else -> IamLayoutParams(
                gravity = Gravity.CENTER,
                popupHeightPx = MATCH_PARENT,
                popupYPx = 0,
                contentHeightPx = MATCH_PARENT,
                focusable = true,
                outsideTouchable = false,
                scrim = SCRIM,
                fullscreen = true,
            )
        }
    }

    /**
     * Puts [paramsFor]'s answer on a live surface: [window] is the popup, [container] the scrim
     * layer that fills it, and [content] the document inside.
     *
     * The window is resized rather than re-shown. A `PopupWindow`'s gravity is fixed at
     * `showAtLocation` and [PopupPresenter] anchors every popup at the top-left corner, so a bottom
     * banner is placed by [IamLayoutParams.popupYPx] rather than by gravity; [IamLayoutParams.gravity]
     * positions the document inside the container, which is what a `center` message needs.
     *
     * Caller is on the main thread.
     */
    fun apply(
        window: PopupWindow,
        container: FrameLayout,
        content: View,
        location: String,
        heightDp: Int,
        screenHeightPx: Int,
    ) {
        val params = paramsFor(
            location,
            heightDp,
            container.resources.displayMetrics.density,
            screenHeightPx,
        )
        container.setBackgroundColor(params.scrim)
        @Suppress("DEPRECATION")
        container.systemUiVisibility = when {
            params.fullscreen -> FULLSCREEN_FLAGS
            params.modal -> BEHIND_BARS_FLAGS
            else -> 0
        }
        val contentParams = FrameLayout.LayoutParams(
            MATCH_PARENT,
            params.contentHeightPx,
            params.gravity,
        )
        if (params.modal) {
            IamEdgeToEdge.keepSafe(container, content, contentParams)
            IamEdgeToEdge.prepare(window)
        } else {
            IamEdgeToEdge.retract(window, container)
        }
        content.layoutParams = contentParams
        window.isFocusable = params.focusable
        window.isOutsideTouchable = params.outsideTouchable
        window.width = MATCH_PARENT
        window.height = params.popupHeightPx
        // `update` is what pushes the focus and touch flags — and a banner's new size — to the
        // window manager. A popup the framework has already torn down throws here rather than
        // resizing, and there is nothing left to resize.
        runCatching {
            window.update(0, params.popupYPx, MATCH_PARENT, params.popupHeightPx)
            if (params.modal) IamEdgeToEdge.extend(window)
        }
            .onFailure { OpenPush.onLog("✗ in-app surface could not be laid out (${it.message})") }
        if (params.popupHeightPx == WRAP_CONTENT && params.gravity == Gravity.BOTTOM) {
            // A bottom banner whose document reported no height has to be measured before it can be
            // placed, so the offset is corrected once the container knows how tall it is.
            container.post {
                val height = container.height
                if (height > 0) {
                    runCatching { window.update(0, screenHeightPx - height, MATCH_PARENT, WRAP_CONTENT) }
                }
            }
        }
    }

    private fun banner(gravity: Int, heightPx: Int, popupYPx: Int) = IamLayoutParams(
        gravity = gravity,
        popupHeightPx = if (heightPx > 0) heightPx else WRAP_CONTENT,
        popupYPx = popupYPx,
        contentHeightPx = MATCH_PARENT,
        focusable = false,
        outsideTouchable = true,
        scrim = Color.TRANSPARENT,
        fullscreen = false,
    )

    /** The document's reported height in pixels, or zero when it did not report one. */
    private fun heightPx(heightDp: Int, density: Float, screenHeightPx: Int): Int {
        if (heightDp <= 0) return 0
        val px = (heightDp * density).roundToInt()
        return if (screenHeightPx > 0) min(px, screenHeightPx) else px
    }
}

/**
 * One display location, resolved. Widths are always `MATCH_PARENT`: every location the server
 * defines spans the screen horizontally.
 */
internal data class IamLayoutParams(
    /** Where the document sits inside [IamLayout.apply]'s container. */
    val gravity: Int,
    /** The popup window's height: a pixel height, `MATCH_PARENT` or `WRAP_CONTENT`. */
    val popupHeightPx: Int,
    /** The popup window's offset from the top of the screen. */
    val popupYPx: Int,
    /** The document's height inside the container. */
    val contentHeightPx: Int,
    val focusable: Boolean,
    val outsideTouchable: Boolean,
    /** The container's background: [IamLayout.SCRIM] or transparent. */
    val scrim: Int,
    val fullscreen: Boolean,
) {
    /** A modal dims the app behind it and covers the whole display; a banner does neither. */
    val modal: Boolean get() = scrim != Color.TRANSPARENT
}
