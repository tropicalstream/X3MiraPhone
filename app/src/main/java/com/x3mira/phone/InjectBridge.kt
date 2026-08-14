package com.x3mira.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

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

    /** Bounds on the search for a text field — see [editableIn]. */
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 12

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

    /**
     * Put [text] into whatever field currently has input focus.
     *
     * The ONLY use of window content in this app. It reaches for the focused
     * node — the one the wearer (or the agent's own tap) just put a cursor in
     * — sets its text, and stops. Nothing here walks the tree, reads
     * siblings, or looks at anything that is not the field being typed into.
     *
     * ACTION_SET_TEXT REPLACES the field rather than appending, which is the
     * right behaviour for the search boxes this exists for and worth knowing
     * before using it on a half-filled form.
     *
     * [submit] then presses the keyboard's action key — Search, Go, Send —
     * because a query typed into a box and left sitting there has not
     * actually done anything, and making the agent spend a whole extra step
     * and another model round trip to press Search is a poor trade.
     */
    fun type(text: String, submit: Boolean): Boolean {
        val s = service ?: run { warn(); return false }
        val root = s.rootInActiveWindow ?: run {
            Log.w(TAG, "type: no active window")
            return false
        }
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        Log.i(
            TAG,
            "type: focus=${focus?.className} editable=${focus?.isEditable} " +
                "settable=${focus?.canSetText()}"
        )
        // CANDIDATES, TRIED UNTIL ONE ACTUALLY TAKES THE TEXT — not one target
        // chosen by what a node claims about itself. Two different apps break
        // this in two different ways, and only trying the write tells them
        // apart:
        //
        //  - Spotify focuses a plain View wrapper. It reports editable=false,
        //    so a capability check catches it and the real EditText is one
        //    level below.
        //  - YouTube focuses a genuine EditText reporting editable=true AND
        //    settable=true, then refuses ACTION_SET_TEXT anyway. Nothing
        //    readable about that node predicts the failure, so choosing a
        //    target up front and giving up when it fails leaves the wearer
        //    with a search box that stays empty and no reason why.
        val candidates = ArrayList<AccessibilityNodeInfo>(3)
        focus?.takeIf { it.canSetText() }?.let { candidates.add(it) }
        focus?.let { editableIn(it) }?.let { candidates.add(it) }
        editableIn(root)?.let { n -> if (candidates.none { it == n }) candidates.add(n) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "type: no editable field found")
            return false
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        var target: AccessibilityNodeInfo? = null
        var ok = false
        for (c in candidates) {
            // Some fields only accept text once they hold input focus, and the
            // one the agent tapped may have lost it to the keyboard appearing.
            if (!c.isFocused) c.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            ok = c.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "type -> ${c.className} accepted=$ok")
            if (ok) { target = c; break }
        }
        Log.i(
            TAG,
            "type ${text.length} chars -> $ok after ${candidates.size} candidate(s) " +
                "(submit=$submit)"
        )
        if (ok && submit && target != null) {
            // IME_ENTER is the polite way and is not always implemented; a
            // literal ENTER key through the same node is the fallback that
            // most search boxes still answer.
            val sent = target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
            Log.i(TAG, "submit -> $sent")
        }
        return ok
    }

    /** Can this node actually be written to? */
    private fun AccessibilityNodeInfo.canSetText(): Boolean =
        isEditable || actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    /**
     * Breadth-first hunt for a writable field, shallowest first.
     *
     * Bounded on purpose: a deep view tree is expensive to walk and a text
     * field the wearer just tapped is never far from focus. Depth-first would
     * also find the WRONG box on a page with several, by diving into the first
     * branch rather than taking the nearest.
     */
    private fun editableIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            seen++
            if (node !== root && node.canSetText()) {
                Log.i(TAG, "type: found editable ${node.className} at depth $depth")
                return node
            }
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return null
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
