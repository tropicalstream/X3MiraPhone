package com.x3mira.phone

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing but exist and register itself. All the work is in
 * [InjectBridge]; this class is the platform's handle on it.
 *
 * It listens for no events. It CAN retrieve window content, and uses that for
 * exactly one thing — [InjectBridge.type] setting the text of the field that
 * already has focus — because typing is the one thing gestures cannot do and
 * tapping out a word key by key is a model round trip per letter. Nothing
 * here reads the screen: no tree walking, no event stream, no text pulled
 * from anything that is not the field being typed into.
 */
class DexInputService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        InjectBridge.service = this
        Log.i("DexInject", "input service connected — trackpad can drive the phone")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        InjectBridge.service = null
        Log.i("DexInject", "input service disconnected")
        return super.onUnbind(intent)
    }
}
