package com.x3mira.phone

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads the phone's notifications so the glasses can show the most recent one.
 *
 * Like the accessibility service, this is gated behind an explicit user grant
 * (Settings → Notification access) for good reason — it can see every
 * notification — so the app never assumes it is on: if it is off, [NotifBridge]
 * stays empty and the HUD banner is simply blank.
 *
 * Only real alerts are forwarded. Ongoing notifications (the capture service's
 * own "mirroring" chip, media transport controls, downloads) are persistent
 * status, not events, and would pin stale text to the banner — they are
 * skipped, as is anything with neither a title nor body.
 */
class DexNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName) return          // never mirror our own chip
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // The Bluetooth stack's device-battery chatter ("10% remaining for
        // RayNeo X3 Pro") reposts on every percent change, and each repost
        // reclaims the banner's single slot from whatever REAL notification
        // was there — the wearer is told about their own glasses' battery,
        // which the HUD readout already shows, at the price of their actual
        // messages. The list is these two packages and nothing more. Honest
        // cost: the same package also posts BT file-transfer results and
        // car-kit access prompts, so those lose their HUD echo too — the
        // phone still shows and sounds all of them, and acting on any of
        // them needs the phone in hand anyway. App notifications — the
        // messages the banner exists for — are untouched.
        if (sbn.packageName in TRANSPORT_NOISE) {
            Log.i(CaptureService.TAG, "notif dropped (transport noise): ${sbn.packageName}")
            return
        }

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        val app = appLabel(sbn.packageName)
        val full = buildString {
            if (app.isNotEmpty()) append(app).append("  ·  ")
            if (title.isNotEmpty()) append(title)
            if (body.isNotEmpty()) {
                if (title.isNotEmpty()) append("\n")
                append(body)
            }
        }
        Log.i(CaptureService.TAG, "notif -> ${full.replace('\n', ' ').take(80)}")
        NotifBridge.update(full)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) { /* keep last shown */ }

    private companion object {
        /** The BT stack under its AOSP and its Gabeldorsche package names. */
        val TRANSPORT_NOISE = setOf(
            "com.android.bluetooth",
            "com.google.android.bluetooth",
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")
}
