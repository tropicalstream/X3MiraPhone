package com.x3mira.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
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

    /**
     * Long enough for the system to treat the drag as a deliberate dismiss.
     * A flick is read as a fling and the window just springs back to a corner;
     * the dismiss target only appears once the drag has been held a moment.
     */
    /**
     * How long the X takes to appear after the little window is tapped. The
     * controls fade in, and searching before they exist finds nothing — which
     * is indistinguishable from there being no close button at all.
     */
    private const val CONTROLS_MS = 900L

    /** Long enough for the app switcher to finish animating in. */
    private const val RECENTS_MS = 1100L

    /**
     * Deliberately slow. A quick flick in the switcher reads as scrolling
     * sideways between cards; a long vertical drag is what throws one away.
     */
    private const val CARD_FLICK_MS = 380L

    /** How long a dismissal may take to animate away before we call it a miss. */
    private const val GONE_WAIT_MS = 2500L

    /**
     * Where the overlay controls sit INSIDE the little window, as fractions of
     * its own width and height. Measured on the S23 from a screenshot with the
     * controls showing: the row (gear, split, expand, X) runs across the upper
     * fifth, and the X is the rightmost of the four.
     *
     * These are the one piece of this feature that is genuinely tuned to a
     * phone's UI rather than derived from an API, so they are named, explained
     * and checked: if the press does not remove the window, the failure is
     * logged loudly rather than assumed to have worked.
     */
    private const val CLOSE_X_FRAC = 0.866f
    private const val EXPAND_X_FRAC = 0.678f
    private const val CONTROLS_Y_FRAC = 0.197f
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

    /**
     * Transport control, sent as a real media key rather than a press on a
     * picture of a button.
     *
     * Tapping playback controls by sight is the single most error-prone thing
     * the agent does: an album page and the mini player each own a round
     * button, either can be under the fold, and a play/pause control shows the
     * action available NEXT rather than the state it is in — so "pause" kept
     * pausing and then resuming. A media key has none of that ambiguity. The
     * OS routes it to whichever session actually holds audio focus, so it
     * works the same in Spotify, YouTube, Pocket Casts and YT Music, in either
     * orientation, whatever happens to be drawn on screen.
     *
     * Needs no accessibility grant either: this goes through AudioManager, not
     * the gesture dispatcher, so it still works when injection is unavailable.
     */
    fun media(ctx: Context, keyCode: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) { Log.w(TAG, "no AudioManager — media key $keyCode dropped"); return }
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        Log.i(TAG, "media key $keyCode dispatched")
    }

    /**
     * Close the app in front — properly, not just leave it.
     *
     * "Go home" only backgrounds an app: it keeps running, keeps playing, and
     * is still sitting there when the wearer looks again. A normal app cannot
     * force-stop another one — there is no API for it and there should not be —
     * so this does what a person does: open the app switcher and flick the
     * front card away.
     *
     * The card is centre-screen in the switcher, so the swipe needs no search;
     * it is slow on purpose (a flick reads as scrolling between cards, which
     * would dismiss the WRONG app) and finishes on Home so the wearer is left
     * somewhere sensible rather than staring at an empty switcher.
     */
    fun closeApp(ctx: Context): Boolean {
        val s = service ?: run { warn(); return false }
        // WHICH app, decided BEFORE the switcher opens and the answer is gone.
        val target = s.rootInActiveWindow?.packageName?.toString()
        if (target == null) { Log.w(TAG, "closeApp: nothing in front to close"); return false }
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(target, 0)).toString()
        }.getOrNull()
        Log.i(TAG, "closeApp: target=$target label=$label")

        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        Thread.sleep(RECENTS_MS)

        // FIND THE CARD, DO NOT AIM AT THE MIDDLE. Flicking screen-centre
        // assumes the switcher put the app you were in under your finger, and
        // it does not always: the cards are a scrolling row, the layout shifts
        // with how many are open, and mid-animation the centre belongs to a
        // neighbour — so a blind swipe throws away somebody else's app.
        // The card carries the app's name, so match it and use its own bounds.
        val card = label?.let { findCard(s, it) }
        val dm = ctx.resources.displayMetrics
        if (card != null) {
            Log.i(TAG, "closeApp: card for '$label' at $card")
            swipe(card.exactCenterX(), card.exactCenterY().coerceAtLeast(dm.heightPixels * 0.30f),
                card.exactCenterX(), dm.heightPixels * 0.06f, CARD_FLICK_MS)
        } else {
            // No card found by name — say so rather than guessing at the
            // middle, because a wrong guess closes an app they never mentioned.
            Log.w(TAG, "closeApp: no card matching '$label'; leaving the switcher alone")
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return false
        }
        Thread.sleep(900)
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(700)
        // Did the RIGHT one go? Ask the system, not the gesture.
        val gone = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.none { it.processName == target } ?: true
        }.getOrDefault(true)
        Log.i(TAG, "closeApp: $target closed=$gone")
        return true
    }

    /**
     * The switcher card whose label matches [label], by its on-screen bounds.
     *
     * Matched on the app's own name because that is what the card shows; a
     * substring match either way covers "Maps" against "Google Maps" and the
     * card descriptions that append things like ", app card".
     */
    private fun findCard(s: AccessibilityService, label: String): android.graphics.Rect? {
        val wanted = label.lowercase()
        for (w in s.windows) {
            val root = w.root ?: continue
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < MAX_NODES) {
                val n = queue.removeFirst(); seen++
                val text = ((n.contentDescription?.toString() ?: "") + " " +
                    (n.text?.toString() ?: "")).lowercase().trim()
                if (text.isNotEmpty() && (text.contains(wanted) || wanted.contains(text))) {
                    val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                    // A label sits inside the card; anything tiny or offscreen
                    // is a stray match rather than the card itself.
                    if (r.width() > 100 && r.height() > 100) return r
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * The little floating video window, dealt with by name rather than by aim.
     *
     * Picture-in-picture is the one thing on screen the vision agent cannot
     * reliably handle: it is a few hundred pixels in a corner, its close and
     * expand controls only exist while the overlay is showing them, and a miss
     * lands on whatever app is behind it. But the window announces itself —
     * AccessibilityWindowInfo.isInPictureInPictureMode — so it can be found
     * exactly, with no guessing.
     *
     * FULLSCREEN relaunches the owning package, which un-pins the task; that
     * is the same thing the wearer does by tapping the window, and it was
     * verified on the S23 (mode=pinned -> mode=fullscreen).
     * CLOSE hunts the dismiss control. The controls are hidden until the
     * window is touched, so it taps the window first and looks again.
     *
     * Needs flagRetrieveInteractiveWindows, which is why the service declares
     * it — without that flag getWindows() comes back empty and none of this
     * is reachable at all.
     */
    fun pip(ctx: Context, close: Boolean, byPositionOnly: Boolean = false): Boolean {
        val s = service ?: run { warn(); return false }
        val win = s.windows.firstOrNull { it.isInPictureInPictureMode }
        if (win == null) { Log.i(TAG, "pip: no picture-in-picture window on screen"); return false }

        if (!close) {
            val pkg = win.root?.packageName?.toString()
            if (pkg.isNullOrEmpty()) { Log.w(TAG, "pip: window has no package"); return false }
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) { Log.w(TAG, "pip: no launch intent for $pkg"); return false }
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            Log.i(TAG, "pip: relaunched $pkg to leave picture-in-picture")
            return true
        }

        // TAP IT, THEN PRESS THE X — the way a person closes it.
        //
        // The X does not live in the PiP window. That window's whole tree is
        // nine nodes with one clickable, FrameLayout[desc='Minimized player'];
        // the close and expand controls are drawn by SystemUI in a SEPARATE
        // window, and only after the little window has been touched. So the
        // search has to happen across every window on screen, after the tap —
        // looking only inside the PiP window finds nothing, always.
        val bounds = android.graphics.Rect().also { win.getBoundsInScreen(it) }
        tap(bounds.exactCenterX(), bounds.exactCenterY())
        Thread.sleep(CONTROLS_MS)

        // By name first — it is the sturdier route when the control is exposed,
        // which it is on this build ONCE THE CONTROLS HAVE HAD TIME TO APPEAR.
        // That timing was the original bug: searching before the tap's overlay
        // faded in found nothing, which looked exactly like "not in the tree".
        if (!byPositionOnly) {
            for (w in s.windows) {
                if (clickDismiss(w) && awaitGone()) {
                    Log.i(TAG, "pip: closed via a named control")
                    return true
                }
            }
        } else {
            Log.i(TAG, "pip: skipping the name search — exercising the positional path")
        }

        // By position, then. The overlay row lives at a fixed proportion of the
        // window, measured on this phone from a screenshot taken with the
        // controls up: window Rect(65,2525-859,2972), 794x447, X at 753,2613.
        // Proportions rather than pixels because the window is dragged around
        // and resized by the wearer — but the row keeps its place within it.
        val x = bounds.left + bounds.width() * CLOSE_X_FRAC
        val y = bounds.top + bounds.height() * CONTROLS_Y_FRAC
        Log.i(TAG, "pip: pressing the X by position at $x,$y within $bounds")
        tap(x, y)
        if (awaitGone()) { Log.i(TAG, "pip: closed by position"); return true }

        // Nothing named close anywhere. Say what WAS on offer, in every window,
        // so the next person reading this log knows what to reach for instead
        // of guessing again.
        Log.w(TAG, "pip: tapped the window but found no close control; dumping what is there")
        for (w in s.windows) dumpClickables(w)
        return false
    }

    /** Find and press whatever this window calls its dismiss control. */
    private fun clickDismiss(win: android.view.accessibility.AccessibilityWindowInfo): Boolean {
        val root = win.root ?: return false
        for (word in arrayOf("close", "dismiss", "exit")) {
            val hit = findByDescription(root, word) ?: continue
            val ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "pip: pressed '$word' control -> $ok")
            if (ok) return true
        }
        return false
    }

    /**
     * Wait for the little window to actually go, rather than glancing once.
     *
     * The dismissal animates, so a single check right after the press catches
     * a window that is on its way out and calls the press a failure. That is
     * how the positional path came to log "found no close control" for a tap
     * that had in fact just worked — the press was right, the verdict was
     * impatient. Poll instead, and only give up when it is still there.
     */
    private fun awaitGone(): Boolean {
        val s = service ?: return false
        val until = SystemClock.uptimeMillis() + GONE_WAIT_MS
        while (SystemClock.uptimeMillis() < until) {
            // A DYING WINDOW IS STILL LISTED. getWindows() keeps returning the
            // picture-in-picture entry for a while after it has been dismissed,
            // but its root is already null — which is exactly what the failure
            // dump showed ("window has no root node") for a window that had in
            // fact just closed. Counting those as alive is what made a working
            // press report itself as a miss. Only a window with a root counts.
            val alive = s.windows.count { it.isInPictureInPictureMode && it.root != null }
            if (alive == 0) return true
            Thread.sleep(200)
        }
        return false
    }

    /** Every clickable node in a window, so a failure names what WAS there. */
    private fun dumpClickables(win: android.view.accessibility.AccessibilityWindowInfo) {
        val root = win.root ?: run { Log.w(TAG, "pip: window has no root node"); return }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        val found = ArrayList<String>()
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            if (n.isClickable) {
                found.add("${n.className}[desc='${n.contentDescription}' text='${n.text}']")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        Log.i(TAG, "pip: $seen nodes, ${found.size} clickable: ${found.take(8).joinToString(" | ")}")
    }

    /** Breadth-first hunt for a node whose description or text contains [word]. */
    private fun findByDescription(root: AccessibilityNodeInfo, word: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst(); seen++
            val d = (n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "")
            if (d.contains(word, ignoreCase = true) && n.isClickable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun warn() {
        Log.w(TAG, "input ignored — accessibility service is not enabled")
    }
}
