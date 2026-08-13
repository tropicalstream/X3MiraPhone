package com.dexprobe.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One button's worth of UI: ask for the projection grant, hand it to
 * [CaptureService], and get out of the way. The sweep parameters arrive by
 * intent extra so a measurement run is a shell loop, not a rebuild.
 */
class MainActivity : Activity() {

    private var pending: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "DexProbe\n\nGrant capture to start streaming on :${CaptureService.PORT}"
            textSize = 18f
            setPadding(48, 96, 48, 48)
        }
        setContentView(LinearLayout(this).apply { addView(tv) })
        pending = intent
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ) return
        if (resultCode != RESULT_OK || data == null) {
            Log.w(CaptureService.TAG, "capture denied")
            finish(); return
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
    }

    companion object { private const val REQ = 7391 }
}
