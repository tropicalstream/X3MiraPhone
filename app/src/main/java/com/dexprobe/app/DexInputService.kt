package com.dexprobe.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing but exist and register itself. All the work is in
 * [InjectBridge]; this class is the platform's handle on it.
 *
 * It listens for no events and reads no window content — the service
 * declaration asks for gesture dispatch only. That matters: an
 * accessibility service is the most invasive permission on the device, and
 * this one should be inspectable as "can touch the screen, cannot read it".
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
