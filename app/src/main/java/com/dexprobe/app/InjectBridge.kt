package com.dexprobe.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * The one seam between "a socket told us to click" and "a click happened".
 *
 * A normal Android app cannot inject input into other apps — that is the
 * whole reason this class exists and the whole reason the wearer has to
 * turn on an accessibility service by hand. AccessibilityService is the
 * only public API that can synthesise a touch anywhere on the screen, and
 * the platform gates it behind an explicit, scary, user-only toggle for
 * good reason. So the capture service never touches input directly: it
 * parks the request here, and whichever service is enabled picks it up.
 *
 * If nothing is enabled the app still MIRRORS perfectly and simply cannot
 * click — which is the honest degraded state, and worth saying out loud in
 * the UI rather than failing silently.
 */
object InjectBridge {

    private const val TAG = "DexInject"

    @Volatile
    var service: AccessibilityService? = null

    val ready: Boolean get() = service != null

    /**
     * A tap at absolute screen pixels. Duration is deliberately short but
     * not zero: a 1ms gesture is dropped by some views as a stray, and
     * ~40ms reads to every app as a deliberate finger.
     */
    fun tap(x: Float, y: Float, durationMs: Long = 40L) {
        val s = service ?: return warn()
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        s.dispatchGesture(g, null, null)
    }

    /** A long press — what opens context menus and starts drags. */
    fun longPress(x: Float, y: Float) = tap(x, y, 600L)

    /**
     * A drag/swipe. Used both for scrolling a list and for dragging a DeX
     * window by its title bar, which is why the duration is a parameter:
     * a flick and a deliberate drag are the same gesture at different
     * speeds, and apps read them very differently.
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 120L) {
        val s = service ?: return warn()
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(20L)))
            .build()
        s.dispatchGesture(g, null, null)
    }

    /** BACK / HOME / RECENTS — the buttons a mouse has no equivalent for. */
    fun global(action: Int) {
        val s = service ?: return warn()
        s.performGlobalAction(action)
    }

    private fun warn() {
        Log.w(TAG, "input ignored — accessibility service is not enabled")
    }
}
