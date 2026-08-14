package com.x3mira.phone

/**
 * The one seam between "a notification was posted on the phone" and "the
 * glasses show it". [DexNotificationService] writes the most recent
 * notification here; [CaptureService] reads it and pushes it over the link,
 * and registers [onChange] so a notification that arrives mid-session reaches
 * the wearer without waiting for anything.
 *
 * Like [InjectBridge], this exists because the capture service must not depend
 * on a privileged service being alive: if notification access is off the
 * mirror still works and the banner is simply empty.
 */
object NotifBridge {

    @Volatile
    var latest: String = ""
        private set

    /** Set by CaptureService so a fresh notification is pushed immediately. */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    fun update(text: String) {
        if (text == latest) return
        latest = text
        onChange?.invoke(text)
    }
}
