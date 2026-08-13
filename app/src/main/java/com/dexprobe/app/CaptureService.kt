package com.dexprobe.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Display
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The phone half of the probe: capture the screen, encode it, and ship it
 * over TCP with enough timing information to answer one question honestly —
 * how long does a pixel take to get from this phone to the glasses, and at
 * what resolution is it still worth looking at?
 *
 * This is a MEASUREMENT RIG, not a product. It exists to decide whether a
 * phone-screen window inside the AR hub is pleasant enough to build for
 * real, so everything here is chosen to make the numbers trustworthy
 * rather than to make the picture pretty:
 *
 *  - Every frame carries the SystemClock.elapsedRealtimeNanos of the moment
 *    the encoder released it. The receiver subtracts its own clock to get
 *    one-way latency; on the glasses that clock is comparable because both
 *    are Android and both are sampled against a shared adb-read baseline.
 *  - Capture→encode time is logged separately from network time, because
 *    the two have completely different fixes if the total is too slow.
 *  - Resolution, bitrate and frame rate are all settable from the intent,
 *    so a sweep is a shell loop rather than five rebuilds.
 *
 * MediaProjection is the whole reason this can be a normal app: it needs a
 * user consent dialog and a foreground service, and nothing else. No root,
 * no adb, no OEM cooperation — which is what makes the phone-window idea
 * viable where a true Miracast/DeX sink is not.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var server: ServerSocket? = null

    /**
     * SPS/PPS, kept for the life of the encoder.
     *
     * MediaCodec emits the parameter sets exactly ONCE, in a buffer flagged
     * CODEC_CONFIG, when the encoder starts. A client that connects later
     * therefore receives nothing but P-frames describing a picture it has
     * never been told the shape of, and the decoder rejects every one of
     * them ("Unsupported input buffer") while the wearer looks at black.
     * So the config is cached here and replayed as the first thing any new
     * client sees.
     */
    @Volatile private var codecConfig: ByteArray? = null

    /** Everything a rebuild needs to reproduce the pipeline after a rotation. */
    private var projRef: MediaProjection? = null
    private var reqFps = 30
    private var reqBitrate = 4_000_000
    @Volatile private var clientSock: Socket? = null
    @Volatile private var clientOut: DataOutputStream? = null
    private val writeLock = Any()
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioOn = false
    private val pipelineLock = Any()
    @Volatile private var rebuilding = false
    @Volatile private var portraitBias = true   // which way the panel started
    private var displayListener: DisplayManager.DisplayListener? = null
    private var nsd: NsdManager? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "no projection grant — nothing to capture")
            stopSelf()
            return START_NOT_STICKY
        }
        // "Best" is not "native". The glasses panel is 640x480 per eye, so
        // sending 1440x3088 spends bitrate and encoder time on detail the
        // wearer can never resolve. Half-native keeps text crisp after the
        // downscale and halves the pixel budget; it is the default, and the
        // glasses can ask for anything else at connect time.
        val dm = resources.displayMetrics
        val bestW = ((dm.widthPixels / 2) / 16) * 16
        val bestH = ((dm.heightPixels / 2) / 16) * 16
        val w = intent.getIntExtra(EXTRA_WIDTH, if (bestW > 0) bestW else 720)
        val h = intent.getIntExtra(EXTRA_HEIGHT, if (bestH > 0) bestH else 1544)
        val fps = intent.getIntExtra(EXTRA_FPS, 30)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)

        startForegroundNotice()
        running = true
        thread(name = "dexprobe-capture") { runCapture(resultCode, data, w, h, fps, bitrate) }
        return START_NOT_STICKY
    }

    private fun runCapture(
        resultCode: Int,
        data: Intent,
        w: Int,
        h: Int,
        fps: Int,
        bitrate: Int
    ) {
        Log.i(TAG, "probe start ${w}x$h @${fps}fps ${bitrate / 1000}kbps")
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, data) ?: run {
            Log.w(TAG, "projection refused"); return
        }
        projection = mp
        projRef = mp
        reqFps = fps
        reqBitrate = bitrate
        portraitBias = h >= w
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // A rotation rebuild briefly has no display; ignore the stop
                // it can trigger. Only a stop while we are NOT rebuilding is
                // the user or system genuinely ending the projection.
                if (rebuilding) { Log.i(TAG, "projection onStop during rebuild — ignored"); return }
                Log.i(TAG, "projection stopped by system"); shutdown()
            }
        }, null)

        // Capture at the phone's CURRENT real orientation and never rebuild.
        // Rebuilding the VirtualDisplay to follow rotation stops the
        // MediaProjection on this hardware and kills the whole capture
        // process — proven repeatedly. So the orientation the phone is held
        // in when the wearer grants capture is the one that fills the frame;
        // rotating the phone after that letterboxes, which is stable and
        // predictable, where the rebuild was neither.
        val dm2 = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val real = android.graphics.Point()
        @Suppress("DEPRECATION") dm2.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(real)
        val capW = if (real.x > 0) ((real.x / 2) / 16) * 16 else w
        val capH = if (real.y > 0) ((real.y / 2) / 16) * 16 else h
        Log.i(TAG, "capturing at phone-native ${capW}x$capH")
        buildPipeline(capW, capH)
        startAudioCapture(mp)

        // One client at a time, but ROBUST to churn: accept always keeps
        // running, and a NEW connection evicts the OLD one. Without this a
        // client that dies without a clean close — a restarted glasses app —
        // leaves the old pump blocked on a dead socket, and because a static
        // phone screen produces no frames the socket is never written to and
        // never detected as dead, so accept is never called again and the
        // next client hangs at "connecting" forever. That was the bug.
        val ss = ServerSocket(PORT)
        ss.reuseAddress = true
        server = ss
        Log.i(TAG, "listening on $PORT")
        advertise()
        while (running) {
            val sock = try { ss.accept() } catch (e: Throwable) { break }
            Log.i(TAG, "client ${sock.inetAddress.hostAddress}")
            // Evict whoever held the slot; its pump unblocks on the close.
            runCatching { clientSock?.close() }
            thread(name = "dexprobe-client", isDaemon = true) {
                runCatching { pump(sock, w, h, fps) }
                    .onFailure { Log.w(TAG, "client ended: ${it.message}") }
                clientOut = null
                runCatching { sock.close() }
            }
        }
        shutdown()
    }

    /**
     * Frame wire format, deliberately trivial so the receiver can be twenty
     * lines of anything:
     *
     *   magic  int32   0xDEC0DE01
     *   tNanos int64   encoder output time (SystemClock.elapsedRealtimeNanos)
     *   flags  int32   MediaCodec buffer flags (bit 1 = codec config)
     *   len    int32   payload bytes
     *   data   [len]   one H.264 access unit (Annex-B)
     */
    /**
     * The return channel. The glasses send pointer work in NORMALISED
     * coordinates (0..1 of the captured surface) so neither end has to know
     * the other's geometry, and a resolution change mid-session cannot
     * silently move every click.
     *
     *   'T' x y            tap
     *   'L' x y            long press
     *   'S' x1 y1 x2 y2 ms drag / scroll
     *   'G' action         global: 1=back 2=home 3=recents
     */
    private fun readInput(sock: Socket, w: Int, h: Int) {
        val inp = DataInputStream(sock.getInputStream())
        while (running && !sock.isClosed) {
            val kind = inp.read()
            if (kind < 0) return
            when (kind.toChar()) {
                'T', 'L' -> {
                    val x = inp.readFloat() * w
                    val y = inp.readFloat() * h
                    if (kind.toChar() == 'T') InjectBridge.tap(x, y) else InjectBridge.longPress(x, y)
                }
                'S' -> {
                    val x1 = inp.readFloat() * w; val y1 = inp.readFloat() * h
                    val x2 = inp.readFloat() * w; val y2 = inp.readFloat() * h
                    val ms = inp.readInt().toLong()
                    InjectBridge.swipe(x1, y1, x2, y2, ms)
                }
                'G' -> {
                    when (inp.readInt()) {
                        1 -> InjectBridge.global(GLOBAL_ACTION_BACK)
                        2 -> InjectBridge.global(GLOBAL_ACTION_HOME)
                        3 -> InjectBridge.global(GLOBAL_ACTION_RECENTS)
                    }
                }
                else -> return   // desynchronised — drop the client
            }
        }
    }

    private fun pump(sock: Socket, wIgnored: Int, hIgnored: Int, fps: Int) {
        sock.tcpNoDelay = true
        sock.keepAlive = true            // detect a silently-dead peer
        clientSock = sock
        val vd = virtualDisplay
        val w = vd?.display?.let { android.graphics.Point().also { p -> @Suppress("DEPRECATION") it.getRealSize(p) }.x } ?: wIgnored
        val h = vd?.display?.let { android.graphics.Point().also { p -> @Suppress("DEPRECATION") it.getRealSize(p) }.y } ?: hIgnored
        // Input runs on its own thread: a blocking read must never stall the
        // frame pump, or a still trackpad would freeze the picture.
        thread(name = "dexprobe-input", isDaemon = true) {
            runCatching { readInput(sock, w, h) }
                .onFailure { Log.w(TAG, "input channel ended: ${it.message}") }
        }
        val out = DataOutputStream(sock.getOutputStream().buffered(1 shl 16))
        synchronized(writeLock) {
            out.writeInt(MAGIC_HELLO); out.writeInt(w); out.writeInt(h); out.writeInt(fps)
            // Whether clicks do anything, and whether audio follows, so the
            // glasses can show the true state and size their AudioTrack.
            out.writeInt(if (InjectBridge.ready) 1 else 0)
            out.writeInt(if (audioOn) AUDIO_RATE else 0)
            out.writeInt(2)   // stereo
            out.flush()
        }
        clientOut = out

        // Replay the parameter sets before anything else, then ask for a
        // fresh keyframe so the picture starts from a complete image rather
        // than from whatever half-updated frame the stream is mid-way
        // through.
        codecConfig?.let { cfg ->
            synchronized(writeLock) {
                out.writeInt(MAGIC_FRAME)
                out.writeLong(SystemClock.elapsedRealtimeNanos())
                out.writeInt(MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                out.writeInt(cfg.size)
                out.write(cfg)
                out.flush()
            }
            Log.i(TAG, "replayed codec config to new client")
        }
        runCatching {
            encoder?.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }

        val info = MediaCodec.BufferInfo()
        var frames = 0
        var bytes = 0L
        var worstEncodeUs = 0L
        var sumEncodeUs = 0L
        val started = SystemClock.elapsedRealtime()
        var lastReport = started

        while (running && !sock.isClosed) {
            val codec = encoder ?: break   // rebuilt out from under us: reconnect
            val idx = try { codec.dequeueOutputBuffer(info, 100_000) }
                catch (e: IllegalStateException) { break }
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)
            if (buf == null) {
                codec.releaseOutputBuffer(idx, false)
                continue
            }
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val payload = ByteArray(info.size)
            buf.get(payload)

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codecConfig = payload.copyOf()
                Log.i(TAG, "cached codec config (${payload.size} B)")
            }

            val nowNanos = SystemClock.elapsedRealtimeNanos()
            // presentationTimeUs is set by the surface at COMPOSITION time,
            // so this difference is capture→encoded, the part a faster
            // encoder or lower resolution would improve.
            val encodeUs = (nowNanos / 1000) - info.presentationTimeUs
            if (encodeUs in 0..1_000_000) {
                sumEncodeUs += encodeUs
                if (encodeUs > worstEncodeUs) worstEncodeUs = encodeUs
            }

            synchronized(writeLock) {
                out.writeInt(MAGIC_FRAME)
                out.writeLong(nowNanos)
                out.writeInt(info.flags)
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()
            }

            runCatching { codec.releaseOutputBuffer(idx, false) }
            frames++
            bytes += payload.size

            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= 2000) {
                val secs = (now - started) / 1000.0
                Log.i(
                    TAG,
                    "probe-stat frames=%d fps=%.1f kbps=%.0f encode_avg_ms=%.1f encode_worst_ms=%.1f"
                        .format(
                            frames, frames / secs, (bytes * 8 / 1000.0) / secs,
                            if (frames > 0) sumEncodeUs / frames / 1000.0 else 0.0,
                            worstEncodeUs / 1000.0
                        )
                )
                lastReport = now
            }
        }
    }

    /**
     * Build the encoder and the mirror surface at (w, h). Called once at
     * start and again on every rotation, because a MediaCodec input surface
     * is fixed in size at creation — resolution change means a fresh
     * encoder, so the whole pipeline is rebuilt as a unit under
     * [pipelineLock].
     */
    private fun buildPipeline(w: Int, h: Int): Unit = synchronized(pipelineLock) {
        val mp = projRef ?: return
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, reqBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, reqFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        // A rebuilt encoder emits fresh SPS/PPS; drop the stale ones so a
        // reconnecting client is not replayed config for the old geometry.
        codecConfig = null
        virtualDisplay = mp.createVirtualDisplay(
            "dexprobe", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )
        Log.i(TAG, "pipeline built ${w}x$h")
    }

    /**
     * Rotate without ever leaving the projection displayless.
     *
     * The crash this fixes: releasing the only VirtualDisplay made the
     * platform believe the projection had stopped, which killed the whole
     * capture process — server socket and all — so the glasses could never
     * reconnect. So the NEW encoder, surface and display are stood up
     * FIRST; only once the projection is mirroring into them are the old
     * ones released. The client is dropped in between so it reconnects and
     * reads the new geometry.
     */
    private fun rebuildPipeline(w: Int, h: Int): Unit = synchronized(pipelineLock) {
        val mp = projRef ?: return
        rebuilding = true
        try {
            val oldVd = virtualDisplay
            val oldEnc = encoder
            val oldSurf = inputSurface

            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, reqBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, reqFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = codec.createInputSurface()
            codec.start()
            val newVd = mp.createVirtualDisplay(
                "dexprobe", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surf, null, null
            )

            // Swap the live refs, then drop the client so pump re-accepts on
            // the new encoder and the glasses re-handshake the new geometry.
            encoder = codec
            inputSurface = surf
            virtualDisplay = newVd
            codecConfig = null
            runCatching { clientSock?.close() }

            // Now safe to release the old ones — the projection is already
            // mirroring into the new display.
            runCatching { oldVd?.release() }
            runCatching { oldEnc?.stop(); oldEnc?.release() }
            runCatching { oldSurf?.release() }
            Log.i(TAG, "rebuilt ${w}x$h (new-before-old)")
        } finally {
            rebuilding = false
        }
    }

    private fun teardownPipeline(): Unit = synchronized(pipelineLock) {
        runCatching { virtualDisplay?.release() }; virtualDisplay = null
        runCatching { encoder?.stop(); encoder?.release() }; encoder = null
        runCatching { inputSurface?.release() }; inputSurface = null
    }

    /**
     * Follow the phone's rotation so the mirror is always the SHAPE of what
     * the phone is showing.
     *
     * The bug this fixes: the surface was created once, in portrait, and a
     * landscape app then had to be letterboxed into a portrait frame — a
     * thin band with black above and below — which the glasses letterboxed
     * AGAIN into their own landscape viewport, leaving the picture a sliver.
     * Rotating the capture surface with the phone means a landscape app
     * fills a landscape surface, which then fills the glasses cleanly.
     *
     * On a change the pipeline is rebuilt at the swapped size and the client
     * is dropped, so it reconnects and reads the new geometry through the
     * handshake it already has — no mid-stream resolution protocol needed,
     * at the cost of one reconnect blip.
     */
    @Suppress("unused")
    private fun watchRotation() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) {
                if (id != Display.DEFAULT_DISPLAY) return
                val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                @Suppress("DEPRECATION") val real = android.graphics.Point()
                    .also { d.getRealSize(it) }
                val nowPortrait = real.y >= real.x
                if (nowPortrait == portraitBias) return   // same orientation
                portraitBias = nowPortrait
                // Half of the phone's CURRENT real size, aligned to 16 — the
                // same "best" rule as start, now in the new orientation.
                val nw = ((real.x / 2) / 16) * 16
                val nh = ((real.y / 2) / 16) * 16
                Log.i(TAG, "rotation -> ${if (nowPortrait) "portrait" else "landscape"} ${nw}x$nh")
                thread(name = "dexprobe-rotate") { rebuildPipeline(nw, nh) }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, null)
    }

    /**
     * Capture the phone's PLAYBACK audio and stream it beside the video.
     *
     * AudioPlaybackCapture (API 29+) taps what the phone is PLAYING —
     * media, games, browser video — not the microphone, which is why it
     * pairs with the same MediaProjection grant. Apps can opt out and
     * system/DRM audio is never capturable, but ordinary media plays
     * through. Raw 48 kHz stereo PCM is ~1.5 Mbps: a rounding error next to
     * the link and cheaper in code than an AAC round trip, and audio wants
     * the low latency raw gives.
     *
     * It runs continuously and independently of clients; frames are written
     * only when a client is attached, and under [writeLock] so an audio
     * chunk never splits a video frame on the wire.
     */
    private fun startAudioCapture(mp: MediaProjection) {
        val cfg = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        }.getOrElse { Log.w(TAG, "audio config failed: ${it.message}"); return }

        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }.getOrElse {
            // Almost always a missing RECORD_AUDIO grant. Mirror still works;
            // say so rather than crash.
            Log.w(TAG, "audio record failed (RECORD_AUDIO?): ${it.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "audio record not initialized")
            return
        }
        audioRecord = rec
        audioOn = true
        rec.startRecording()
        Log.i(TAG, "audio capture started ${AUDIO_RATE}Hz stereo")

        audioThread = kotlin.concurrent.thread(name = "dexprobe-audio") {
            val buf = ByteArray(minBuf)
            while (running && audioOn) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val o = clientOut ?: continue
                runCatching {
                    synchronized(writeLock) {
                        o.writeInt(MAGIC_AUDIO)
                        o.writeInt(n)
                        o.write(buf, 0, n)
                        o.flush()
                    }
                }
            }
        }
    }

    /**
     * Advertise over mDNS so the glasses find this phone by NAME, never by
     * IP. A phone's DHCP address changes — a new lease, a different network —
     * and a hardcoded IP in the glasses breaks the moment it does (the
     * "wrong .11" failure). A service registered as "$SERVICE_NAME" of type
     * "$SERVICE_TYPE" is resolvable wherever the phone lands, so reconnect
     * just works after an address change.
     */
    private fun advertise() {
        val mgr = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered as ${s.serviceName}")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, err: Int) {
                Log.w(TAG, "mDNS registration failed: $err")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo, err: Int) {}
        }
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) }
            .onSuccess { nsd = mgr; nsdReg = reg }
            .onFailure { Log.w(TAG, "mDNS register threw: ${it.message}") }
    }

    private fun shutdown() {
        runCatching { nsdReg?.let { nsd?.unregisterService(it) } }
        running = false
        audioOn = false
        runCatching { audioRecord?.stop(); audioRecord?.release() }; audioRecord = null
        runCatching {
            displayListener?.let {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(it)
            }
        }
        teardownPipeline()
        runCatching { server?.close() }
        runCatching { virtualDisplay?.release() }
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { projection?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHAN, "Screen probe", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHAN)
            .setContentTitle("DexProbe capturing")
            .setContentText("Measuring screen-to-glasses latency")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, n)
    }

    companion object {
        const val TAG = "DexProbe"
        const val PORT = 7391
        const val CHAN = "dexprobe"
        const val MAGIC_HELLO = 0xDEC0DE00.toInt()
        const val MAGIC_FRAME = 0xDEC0DE01.toInt()
        const val MAGIC_AUDIO = 0xDEC0DE02.toInt()
        const val AUDIO_RATE = 48_000
        const val SERVICE_TYPE = "_dexprobe._tcp."
        const val SERVICE_NAME = "DexProbe"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
        const val EXTRA_WIDTH = "w"
        const val EXTRA_HEIGHT = "h"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val GLOBAL_ACTION_BACK = 1
        const val GLOBAL_ACTION_HOME = 2
        const val GLOBAL_ACTION_RECENTS = 3
    }
}
