package com.x3mira.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The phone-side face of the mirror: a STATUS + SETTINGS screen that stays
 * open.
 *
 * The previous design fired the capture request and finished instantly — with
 * capture pre-granted the app flashed and vanished, which reads exactly like a
 * crash. Now the window always opens and stays: it says whether mirroring is
 * live, and it holds the HUD settings, which live on the PHONE because this is
 * the device with a real screen. Capture still auto-starts — onCreate fires
 * the consent request whenever the service is not already running, so the
 * first icon-tap still goes straight to mirroring with zero extra taps; it
 * simply lands on this screen instead of nowhere.
 *
 * Every settings row cycles on tap and pushes to the glasses live via
 * [HudCfg.onChange] — no apply button, no reconnect.
 */
class MainActivity : Activity() {

    private var pending: Intent? = null
    private lateinit var status: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var notifRow: TextView
    private lateinit var readoutRow: TextView
    private lateinit var fontRow: TextView
    private lateinit var p2pRow: TextView
    private lateinit var pointerRow: TextView
    private lateinit var audioRow: TextView
    private lateinit var agentRow: TextView
    private lateinit var typingRow: TextView
    private lateinit var keyRow: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = intent

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0E1A.toInt())
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        col.addView(TextView(this).apply {
            text = "X3MIRA"
            setTextColor(0xFFDFF6FF.toInt())
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.25f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(10), 0, dp(12))
        }
        col.addView(status)

        // The one control the screen was missing: mirroring can be STOPPED
        // here, not just started — without this the only ways out were
        // force-stop or the system cast revoke, neither of which a user
        // should ever need.
        toggleBtn = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                if (CaptureService.live) {
                    stopService(Intent(this@MainActivity, CaptureService::class.java))
                    // shutdown() flips live=false on its way down; reflect it
                    // as soon as it lands rather than waiting for the ticker.
                    ui.postDelayed({ refreshStatus() }, 400L)
                } else {
                    requestCapture()
                }
                refreshStatus()
            }
        }
        col.addView(toggleBtn)

        col.addView(header("Glasses HUD"))
        notifRow = row(col) {
            val cur = HudCfg.notifLines(this)
            HudCfg.setNotifLines(this, (cur + 1) % 4)   // Off → 1 → 2 → 3 → Off
            refreshRows(); HudCfg.onChange?.invoke()
        }
        readoutRow = row(col) {
            val cur = HudCfg.readoutMode(this)
            HudCfg.setReadoutMode(this, (cur + 1) % 4)  // Off → Time → Time+Batt → Date+Time+Batt
            refreshRows(); HudCfg.onChange?.invoke()
        }
        fontRow = row(col) {
            val next = when (HudCfg.fontPct(this)) { 80 -> 100; 100 -> 120; else -> 80 }
            HudCfg.setFontPct(this, next)
            refreshRows(); HudCfg.onChange?.invoke()
        }
        p2pRow = row(col) {
            HudCfg.setP2pHost(this, !HudCfg.p2pHost(this))
            refreshRows()
            // Takes effect on the next start; say so rather than implying live.
            android.widget.Toast.makeText(
                this, "Restart mirroring to apply", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        pointerRow = row(col) {
            val next = when (HudCfg.pointerPct(this)) {
                50 -> 65; 65 -> 80; 80 -> 100; 100 -> 130; else -> 50
            }
            HudCfg.setPointerPct(this, next)
            refreshRows(); HudCfg.onChange?.invoke()
        }
        audioRow = row(col) {
            HudCfg.setAudioMode(this, (HudCfg.audioMode(this) + 1) % 3)  // Auto → Always → Never
            refreshRows()
        }
        col.addView(TextView(this).apply {
            text = "Sound: on Auto the phone stops streaming audio while Bluetooth is already " +
                "carrying it to the glasses. Both at once is the same sound twice, a fraction " +
                "of a second apart — it is heard as an echo."
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12.5f
            setPadding(dp(2), dp(10), dp(2), 0)
        })
        col.addView(TextView(this).apply {
            text = "The HUD keeps to bands above and below the mirror — it never covers the phone picture, in portrait or landscape. Changes apply on the glasses instantly."
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12.5f
            setPadding(dp(2), dp(10), dp(2), 0)
        })

        col.addView(header("Page agent"))
        agentRow = row(col) {
            HudCfg.setAgentOn(this, !HudCfg.agentOn(this))
            refreshRows(); HudCfg.onChange?.invoke()
        }
        typingRow = row(col) {
            HudCfg.setAgentTyping(this, !HudCfg.agentTyping(this))
            refreshRows(); HudCfg.onChange?.invoke()
        }
        keyRow = row(col) { refreshRows() }   // status only; tap re-checks
        col.addView(TextView(this).avatarNote())

        actionBar?.hide()   // the wordmark IS the title; the bar doubled it
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0A0E1A.toInt())   // fill past the content too
            isFillViewport = true
            addView(col)
        })
        refreshRows()
        refreshStatus()

        // Auto-start is preserved: if the mirror is not already running, ask
        // for the one consent the OS requires the moment the app opens.
        askNearbyPermission()
        if (!CaptureService.live) requestCapture()
    }

    /**
     * Wi-Fi Direct needs a runtime grant and does not complain without one —
     * discovery simply returns nothing, which reads as "the glasses are not
     * there". Asked once, up front, so that failure mode never happens.
     */
    private fun askNearbyPermission() {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.NEARBY_WIFI_DEVICES
        else android.Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(needed) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(needed), REQ_NEARBY) }
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onResume() { super.onResume(); ui.post(statusTick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(statusTick) }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) {
            Log.w(CaptureService.TAG, "capture denied")
            refreshStatus(); return          // stay open: the settings are still useful
        }
        val src = pending
        val svc = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            putExtra(CaptureService.EXTRA_WIDTH, src?.getIntExtra("w", 720) ?: 720)
            putExtra(CaptureService.EXTRA_HEIGHT, src?.getIntExtra("h", 1544) ?: 1544)
            putExtra(CaptureService.EXTRA_FPS, src?.getIntExtra("fps", 30) ?: 30)
            putExtra(CaptureService.EXTRA_BITRATE, src?.getIntExtra("bitrate", 4_000_000) ?: 4_000_000)
        }
        startForegroundService(svc)
        Log.i(CaptureService.TAG, "capture granted — service started")
        refreshStatus()
    }

    private fun refreshStatus() {
        if (CaptureService.live) {
            status.text = "●  Mirroring to glasses  ·  port ${CaptureService.PORT}\nPress Home — the glasses show whatever this phone shows."
            status.setTextColor(0xFF7FE4A0.toInt())
            toggleBtn.text = "Stop mirroring"
            toggleBtn.setTextColor(0xFFFF9DA0.toInt())
            toggleBtn.setBackgroundColor(0x2EFF2D4B)
        } else {
            status.text = "○  Not mirroring."
            status.setTextColor(0xFFFFC65A.toInt())
            toggleBtn.text = "Start mirroring"
            toggleBtn.setTextColor(0xFF7FE4FF.toInt())
            toggleBtn.setBackgroundColor(0x2E26C6FF)
        }
    }

    private fun refreshRows() {
        notifRow.text = "Notification banner:   " + when (HudCfg.notifLines(this)) {
            0 -> "Off"; 1 -> "1 line"; else -> "${HudCfg.notifLines(this)} lines"
        }
        readoutRow.text = "Clock readout:   " + when (HudCfg.readoutMode(this)) {
            0 -> "Off"; 1 -> "Time"; 2 -> "Time + battery"; else -> "Date + time + battery"
        }
        fontRow.text = "HUD text size:   " + when (HudCfg.fontPct(this)) {
            80 -> "Small"; 120 -> "Large"; else -> "Medium"
        }
        pointerRow.text = "Mouse pointer speed:   ${HudCfg.pointerPct(this)}%" +
            if (HudCfg.pointerPct(this) == 80) "  (default)" else ""
        // Say what Auto is doing RIGHT NOW, not just that it is on. "Auto" alone
        // leaves the wearer guessing which way it went, which is how a silent
        // mirror gets reported as broken audio.
        audioRow.text = "Sound to glasses:   " + when (HudCfg.audioMode(this)) {
            1 -> "Always stream"
            2 -> "Never stream"
            else -> if (btAudioOut()) "Auto — Bluetooth is carrying it" else "Auto — streaming"
        }
        agentRow.text = "Page agent:   " +
            if (HudCfg.agentOn(this)) "On — tap to ask or instruct" else "Off"
        typingRow.text = "Let the agent type:   " +
            if (HudCfg.agentTyping(this)) "On" else "Off — it can press, not type"
        // Status, not a secret: the wearer needs to know a key is THERE, and
        // the provider that would answer, never the key itself.
        val provider = AgentProviders.provider(this)
        val hasKey = AgentProviders.key(this).isNotBlank()
        keyRow.text = "API key:   " +
            if (hasKey) "set  ·  ${provider.label}" else "none — push a key file or broadcast it"
    }

    /** Is the phone's sound already leaving over Bluetooth? Needs no permission. */
    private fun btAudioOut(): Boolean = runCatching {
        val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    /** The one paragraph explaining what the agent does and what it sends. */
    private fun TextView.avatarNote(): TextView = apply {
        text = "RIGHT arm: tap and speak — ask about whatever is on this screen, or " +
            "tell it what to do: press something, scroll to find something, open a " +
            "site or an app. Swipe to pan the phone any direction. Double-tap to " +
            "summon the mouse pointer, then swipe to move it and tap to click; it " +
            "pulls the page along at the edges and fades after a few seconds.\n\n" +
            "LEFT arm: tap to cancel — it dismisses the pointer and stops the agent. " +
            "Its swipe is left alone, because that is the glasses' own volume " +
            "control. The assistant's face sits in the HUD band beside the clock — " +
            "never over the picture.\n\n" +
            "The agent sends one still image of this screen per step it takes, only " +
            "after you tap.\n\n" +
            "\"Let the agent type\" is off by default. Gestures cannot type — tapping a " +
            "word out key by key costs a round trip per letter — so turning it on lets " +
            "the input service put text straight into the field you are focused on. " +
            "That is the only thing in the app that touches window content, and it is " +
            "used for nothing else: no reading the screen, no watching what you type."
        setTextColor(0x99FFFFFF.toInt())
        textSize = 12.5f
        setPadding(dp(2), dp(10), dp(2), dp(24))
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFF7FE4FF.toInt())
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
        setPadding(dp(2), dp(8), 0, dp(8))
    }

    private fun row(parent: LinearLayout, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(0x14FFFFFF)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
        parent.addView(tv)
        return tv
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    companion object {
        private const val REQ_NEARBY = 4711
        private const val REQ = 7391
    }
}
