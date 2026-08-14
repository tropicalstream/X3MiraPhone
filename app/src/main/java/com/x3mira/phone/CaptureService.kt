package com.x3mira.phone

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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    // Swapped on rotation from the rotate thread and read by pump/accept threads:
    // must be @Volatile so the new encoder/surface are seen promptly.
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
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

    /**
     * True while the phone has a Bluetooth output attached — which, when it is
     * the glasses, is already carrying the sound to the wearer's ears.
     *
     * Read from AudioManager's device list rather than from the Bluetooth
     * adapter on purpose: the output list needs no permission at all, while
     * asking BluetoothAdapter about connected profiles wants BLUETOOTH_CONNECT
     * and a runtime prompt. What matters here is only "is sound already
     * leaving over Bluetooth", and that is exactly what the output list says.
     */
    /**
     * Model calls the glasses handed over. Several can be in flight — an
     * errand transcribes while the previous hop's vision call is still
     * returning — and each occupies a thread for seconds.
     */
    private val rpcPool = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
        Thread(r, "x3mira-rpc").apply { isDaemon = true }
    }

    @Volatile private var btAudioOut = false
    private var audioDevices: AudioDeviceCallback? = null
    @Volatile private var audioOn = false
    private val pipelineLock = Any()
    @Volatile private var rebuilding = false
    @Volatile private var portraitBias = true   // which way the panel started
    private var displayListener: DisplayManager.DisplayListener? = null
    private var nsd: NsdManager? = null
    private var nsdReg: NsdManager.RegistrationListener? = null
    @Volatile private var running = false
    private var screenLock: android.os.PowerManager.WakeLock? = null

    // Rotation follow. The DisplayListener fires on this handler (a real Looper),
    // debounces rapid flips, then hands the actual reconfigure to a worker thread
    // so nothing heavy runs on the main thread.
    private val rotateHandler = Handler(Looper.getMainLooper())
    @Volatile private var pendingRotate: (() -> Unit)? = null
    private val runPendingRotate = Runnable {
        val job = pendingRotate ?: return@Runnable
        pendingRotate = null
        thread(name = "dexprobe-rotate") {
            runCatching { job() }.onFailure { Log.w(TAG, "rotate job failed: ${it.message}") }
        }
    }
    private val clearRebuilding = Runnable { rebuilding = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            // A second start while live must still honour the
            // startForegroundService contract — go (idempotently) foreground
            // again rather than silently returning, or the OS may kill the
            // process ~10s later for a start that never foregrounded.
            startForegroundNotice()
            return START_NOT_STICKY
        }
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
        live = true
        acquireScreenLock()
        // Guarded: an uncaught throw on a raw thread (encoder busy,
        // BindException on a fast restart) kills the entire process — which
        // to the user is "the app just crashes". Fail into a clean shutdown
        // instead, so the settings screen survives and can re-request.
        thread(name = "dexprobe-capture") {
            runCatching { runCapture(resultCode, data, w, h, fps, bitrate) }
                .onFailure { Log.e(TAG, "capture failed: ${it.message}", it); runCatching { shutdown() } }
        }
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
            // A refused projection (stale/reused token) must not leave a
            // zombie service latched live=true with a green status and no
            // pipeline — tear down properly so the next open re-requests.
            Log.w(TAG, "projection refused"); shutdown(); return
        }
        projection = mp
        projRef = mp
        reqFps = fps
        reqBitrate = bitrate
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
        portraitBias = capH >= capW
        buildPipeline(capW, capH)
        startAudioCapture(mp)
        // Follow rotation so a landscape app fills a landscape frame instead of
        // being letterboxed sideways into a portrait one. Guarded by a kill
        // switch and a capture-once fallback (see reconfigurePipeline).
        if (ROTATE_FOLLOW) watchRotation()
        // Push notifications to the glasses the instant they arrive, and HUD
        // settings the instant the wearer changes them on the phone. Both
        // hooks fire on main/binder threads (settings row clicks, the
        // notification listener), and a socket write on the main thread is a
        // NetworkOnMainThreadException that runCatching would swallow into a
        // silent no-op — so hop to a worker first.
        NotifBridge.onChange = { text ->
            thread(name = "dexprobe-notif", isDaemon = true) { pushNotif(text) }
        }
        HudCfg.onChange = {
            thread(name = "dexprobe-hudcfg", isDaemon = true) { pushHudCfg() }
        }

        // One client at a time, but ROBUST to churn: accept always keeps
        // running, and a NEW connection evicts the OLD one. Without this a
        // client that dies without a clean close — a restarted glasses app —
        // leaves the old pump blocked on a dead socket, and because a static
        // phone screen produces no frames the socket is never written to and
        // never detected as dead, so accept is never called again and the
        // next client hangs at "connecting" forever. That was the bug.
        // reuseAddress must be set BEFORE bind or it is a no-op — the bound
        // constructor made it decorative, and a quick stop/start of mirroring
        // could then hit EADDRINUSE from the previous session's TIME_WAIT.
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(PORT))
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
                // Only clear the shared slot if it is still OURS: the new
                // client that evicted us has already published its own stream,
                // and nulling it here would silently kill its audio and
                // notification/settings pushes for the whole session.
                synchronized(writeLock) { if (clientSock === sock) { clientOut = null; clientSock = null } }
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
    private val injectDisplay by lazy {
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
    }

    /**
     * The physical default-display size in its CURRENT rotation — the space
     * AccessibilityService.dispatchGesture actually lands taps in. Read per
     * event so it tracks rotation for free (landscape reports x>y), and never
     * the half-native capture dims, which would compress every click into the
     * top-left quadrant.
     */
    private fun injectSize(): android.graphics.Point {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") injectDisplay?.getRealSize(p)
        if (p.x <= 0) p.x = 1
        if (p.y <= 0) p.y = 1
        return p
    }

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
     *   'U' url            open a web address (UTF)
     *   'A' name           launch an installed app by label (UTF)
     *   'X' text submit    type into the focused field (UTF + int)
     */
    private fun readInput(sock: Socket, w: Int, h: Int) {
        val inp = DataInputStream(sock.getInputStream())
        while (running && !sock.isClosed) {
            val kind = inp.read()
            if (kind < 0) return
            when (kind.toChar()) {
                'T', 'L' -> {
                    val fx = inp.readFloat(); val fy = inp.readFloat()
                    // Inject in the PHYSICAL display's pixels — that is the space
                    // dispatchGesture lands in, not the half-native capture size —
                    // and read it live so a rotation needs no extra plumbing.
                    val s = injectSize()
                    val x = fx * s.x; val y = fy * s.y
                    if (kind.toChar() == 'T') InjectBridge.tap(x, y) else InjectBridge.longPress(x, y)
                }
                'S' -> {
                    val fx1 = inp.readFloat(); val fy1 = inp.readFloat()
                    val fx2 = inp.readFloat(); val fy2 = inp.readFloat()
                    val ms = inp.readInt().toLong()
                    val s = injectSize()
                    InjectBridge.swipe(fx1 * s.x, fy1 * s.y, fx2 * s.x, fy2 * s.y, ms)
                }
                'G' -> {
                    when (inp.readInt()) {
                        1 -> InjectBridge.global(GLOBAL_ACTION_BACK)
                        2 -> InjectBridge.global(GLOBAL_ACTION_HOME)
                        3 -> InjectBridge.global(GLOBAL_ACTION_RECENTS)
                    }
                }
                'U' -> openWebPage(inp.readUTF())
                'A' -> openApp(inp.readUTF())
                'Q' -> {
                    // Read the WHOLE request on this thread before handing it
                    // off: the reader owns the stream, and anything that
                    // returns before draining the body desyncs every message
                    // after it.
                    val id = inp.readInt()
                    val kind = inp.readInt()
                    val url = inp.readUTF()
                    val method = inp.readUTF()
                    val headers = inp.readUTF()
                    val blen = inp.readInt()
                    val body = ByteArray(blen)
                    inp.readFully(body)
                    // ...and perform it OFF this thread. A model call takes
                    // seconds; blocking here would stall every tap and scroll
                    // the wearer made in the meantime, and the glasses would
                    // look frozen while the agent was thinking.
                    if (kind == RPC_HTTP) {
                        rpcPool.execute { runHttp(id, url, method, headers, body) }
                    } else {
                        Log.w(TAG, "unknown rpc kind $kind")
                        sendReply(id, 0, "unknown rpc kind".toByteArray())
                    }
                }
                'X' -> {
                    // Read BOTH fields before deciding anything: bailing early
                    // on a disabled setting would leave the unread bytes in
                    // the stream and desync every message after it.
                    val text = inp.readUTF()
                    val submit = inp.readInt() == 1
                    if (HudCfg.agentTyping(this)) InjectBridge.type(text, submit)
                    else Log.w(TAG, "typing is off — ignoring ${text.length} chars")
                }
                else -> return   // desynchronised — drop the client
            }
        }
    }

    /**
     * Open a web address in the phone's default browser.
     *
     * The agent can tap and it can scroll, but it has no way to TYPE — that
     * would need the accessibility service to be able to read the screen,
     * which it deliberately cannot. So "open youtube.com" used to end with it
     * tapping the address bar, meeting a keyboard, and having nothing left to
     * do. Handing the phone a URL to open sidesteps the keyboard entirely and
     * is more reliable than spelling a word out through gestures anyway.
     *
     * The URL arrives from a model that is reading a web page, so treat it as
     * untrusted: an ACTION_VIEW will happily launch "intent:", "file:" or
     * "tel:" targets, and a page that displays such a string should not be
     * able to talk the agent into firing one. Web schemes only.
     */
    /**
     * Launch an installed app by the name a person would call it.
     *
     * "Open Spotify" is not a URL, and it was quietly unreachable: the agent
     * could open web addresses and press things it could see, so an app that
     * was not already on screen simply could not be got to. Sending it to
     * spotify.com instead would land in the web player, which is not what
     * anybody means.
     *
     * Matched on the LAUNCHER LABEL rather than a package name, because the
     * wearer says "Spotify", not "com.spotify.music", and the model has no way
     * to know the latter. Exact match first so "Photos" cannot be won by
     * "Google Photos Editor"; a contains-match is the fallback for the times
     * someone says "maps" and means "Google Maps".
     */
    /**
     * Perform one request the glasses asked for, and post the answer back.
     *
     * The allowlist is the point of doing it here rather than trusting the
     * caller. The glasses hand over a URL that ultimately came from a model
     * reading a web page, and this end holds the API keys and a cellular
     * connection — so a request is performed only if it is HTTPS and goes to
     * a host this app already talks to. A proxy that forwarded anything asked
     * of it would be an open relay wearing the phone's identity.
     */
    private fun runHttp(
        id: Int, url: String, method: String, headersJson: String, body: ByteArray
    ) {
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()
        val https = url.startsWith("https://", ignoreCase = true)
        val allowed = https && host != null &&
            AgentProviders.HOSTS.any { host == it || host.endsWith(".$it") }
        if (!allowed) {
            Log.w(TAG, "rpc REFUSED host=$host")
            sendReply(id, 0, "host not allowed".toByteArray())
            return
        }
        val started = SystemClock.uptimeMillis()
        val r = AgentProviders.rawBytes(this, url, method, headersJson, body)
        Log.i(
            TAG,
            "rpc $id -> ${r.first} ${r.second.size}b in ${SystemClock.uptimeMillis() - started}ms  $host"
        )
        sendReply(id, r.first, r.second)
    }

    private fun sendReply(id: Int, status: Int, body: ByteArray) {
        val out = clientOut ?: return
        synchronized(writeLock) {
            runCatching {
                out.writeInt(MAGIC_REPLY)
                out.writeInt(id)
                out.writeInt(status)
                out.writeInt(body.size)
                out.write(body)
                out.flush()
            }.onFailure { Log.w(TAG, "reply $id failed: ${it.message}") }
        }
    }

    private fun openApp(name: String) {
        val want = name.trim()
        if (want.isEmpty()) return
        val pm = packageManager
        val launchables = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
        }.getOrDefault(emptyList())
        val hit = launchables.firstOrNull {
            it.loadLabel(pm).toString().equals(want, ignoreCase = true)
        } ?: launchables.firstOrNull {
            it.loadLabel(pm).toString().contains(want, ignoreCase = true)
        }
        if (hit == null) {
            Log.w(TAG, "no installed app matching '$want' (${launchables.size} searched)")
            return
        }
        val pkg = hit.activityInfo.packageName
        Log.i(TAG, "agent opening app '${hit.loadLabel(pm)}' ($pkg)")
        runCatching {
            val launch = pm.getLaunchIntentForPackage(pkg)
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
                    setClassName(pkg, hit.activityInfo.name)
                }
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "app launch failed: ${it.message}") }
    }

    private fun openWebPage(raw: String) {
        val url = raw.trim().let { if (it.contains("://")) it else "https://$it" }
        val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()?.lowercase()
        if (scheme != "http" && scheme != "https") {
            Log.w(TAG, "refusing non-web url from agent: $raw")
            return
        }
        Log.i(TAG, "agent opening $url")
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Ask the browser to treat this as ITS OWN navigation rather than a
            // handoff from another app. Samsung Internet interrupts every
            // app-opened page with a "X3Mira opened this page" panel that sits
            // over the article — which is reasonable once and maddening on an
            // errand that opens several pages, and there is no setting to
            // silence it (the one it offers only makes app-opening stricter).
            //
            // EXTRA_APPLICATION_ID is the documented hint for "which app owns
            // this tab", and naming the browser itself is the long-standing way
            // to ask for a plain navigation. It is a HINT, not a contract: a
            // browser is free to show its banner anyway.
            val browser = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            if (browser != null) {
                intent.putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, browser)
            }
            startActivity(intent)
        }.onFailure { Log.w(TAG, "open failed: ${it.message}") }
    }

    /**
     * Send the wearer's HUD choices to the glasses: banner lines, readout
     * mode, text size. Three ints, framed under [writeLock] like everything
     * else on the wire.
     */
    private fun pushHudCfg() {
        val out = clientOut ?: return
        synchronized(writeLock) {
            runCatching {
                out.writeInt(MAGIC_HUDCFG)
                out.writeInt(HudCfg.notifLines(this))
                out.writeInt(HudCfg.readoutMode(this))
                out.writeInt(HudCfg.fontPct(this))
                // Agent flags ride the same message so the glasses never have
                // a half-applied config. Reader must consume all SIX — adding
                // a field here without the matching read desyncs the stream,
                // so the two apps ship together.
                out.writeInt(if (HudCfg.agentOn(this)) 1 else 0)
                out.writeInt(if (HudCfg.agentTyping(this)) 1 else 0)
                out.writeInt(HudCfg.pointerPct(this))
                out.flush()
            }
        }
    }

    /**
     * Send one notification to the connected glasses as a length-prefixed
     * UTF-8 string, framed like the others so it never splits a video or audio
     * chunk on the wire.
     */
    private fun pushNotif(text: String) {
        val out = clientOut ?: return
        synchronized(writeLock) {
            runCatching {
                val bytes = text.toByteArray(Charsets.UTF_8)
                out.writeInt(MAGIC_NOTIF)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
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
        // Hand the just-connected glasses the wearer's HUD settings first, so
        // the layout is right from the first frame, then the newest
        // notification so the banner is populated immediately.
        pushHudCfg()
        NotifBridge.latest.takeIf { it.isNotEmpty() }?.let { pushNotif(it) }

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
    /**
     * Follow rotation WITHOUT ever releasing the VirtualDisplay.
     *
     * Releasing/recreating the VD (what the old rebuildPipeline did) makes this
     * hardware treat the projection as stopped and kills the whole process —
     * proven repeatedly. So the ONE VirtualDisplay object lives the entire
     * session and is only MUTATED: a MediaCodec input Surface is fixed-size at
     * configure(), so a new capture resolution needs a fresh encoder + fresh
     * input Surface; the existing VD is then resize()'d to the new logical size
     * and setSurface()'d onto that new encoder. The old encoder is torn down
     * only after the swap, and the client is dropped so it reconnects and reads
     * the new geometry through the hello it already understands — no mid-stream
     * protocol on the glasses.
     *
     * FAIL-SAFE: if any step throws, the OLD pipeline is left exactly as it was —
     * still mirroring, still alive (capture-once for that one rotation). A failed
     * rotation is a soft letterboxed frame, never a dead session. The VD is never
     * released here under any path.
     */
    private fun reconfigurePipeline(newW: Int, newH: Int) {
        if (newW <= 0 || newH <= 0) return
        synchronized(pipelineLock) {
            val vd = virtualDisplay ?: return            // NEVER released

            // 1. Build the new encoder FIRST. A throw here leaves the old
            //    pipeline completely untouched and still live.
            val newEnc: MediaCodec
            val newSurf: Surface
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, newW, newH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, reqBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, reqFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                newEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                newEnc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                newSurf = newEnc.createInputSurface()
                newEnc.start()
            } catch (t: Throwable) {
                Log.w(TAG, "rotate: encoder build failed, staying capture-once: ${t.message}")
                return
            }

            // 2. Commit point. Latch `rebuilding` so an onStop the OEM may emit
            //    during the surface swap is ignored; cleared on a DELAY so a
            //    deferred onStop just after the swap is still swallowed.
            rebuilding = true
            try {
                vd.resize(newW, newH, resources.displayMetrics.densityDpi)  // logical size first
                vd.setSurface(newSurf)                                      // then re-point; never null
            } catch (t: Throwable) {
                Log.w(TAG, "rotate: VD mutate failed, keeping old pipeline: ${t.message}")
                runCatching { newEnc.stop(); newEnc.release() }
                runCatching { newSurf.release() }
                armRebuildingClear()
                return                                                       // old encoder still attached & alive
            }

            // 3. Publish new refs (volatile), then retire old.
            val oldEnc = encoder
            val oldSurf = inputSurface
            encoder = newEnc
            inputSurface = newSurf
            codecConfig = null            // fresh SPS/PPS come from the new encoder's first output

            // 4. Drop the client BEFORE releasing the old encoder, so its pump
            //    exits on the closed socket rather than racing a release().
            runCatching { clientSock?.close() }
            runCatching { oldEnc?.stop(); oldEnc?.release() }
            runCatching { oldSurf?.release() }

            Log.i(TAG, "reconfigured ${newW}x$newH (VD kept alive, client re-dials)")
        }
        armRebuildingClear()
    }

    /** Clear the rebuilding latch after a grace window so a deferred onStop is swallowed. */
    private fun armRebuildingClear() {
        rotateHandler.removeCallbacks(clearRebuilding)
        rotateHandler.postDelayed(clearRebuilding, 1500L)
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
     * On a change the pipeline is reconfigured at the swapped size (VD kept
     * alive) and the client is dropped, so it reconnects and reads the new
     * geometry through the handshake it already has — no mid-stream resolution
     * protocol needed, at the cost of one reconnect blip.
     */
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
                if (nowPortrait == portraitBias) return   // brightness/refresh churn, not a flip
                portraitBias = nowPortrait
                // Half of the phone's CURRENT real size, aligned to 16 — the
                // same "best" rule as start, now in the new orientation.
                val nw = ((real.x / 2) / 16) * 16
                val nh = ((real.y / 2) / 16) * 16
                Log.i(TAG, "rotation -> ${if (nowPortrait) "portrait" else "landscape"} ${nw}x$nh")
                pendingRotate = { reconfigurePipeline(nw, nh) }
                rotateHandler.removeCallbacks(runPendingRotate)
                rotateHandler.postDelayed(runPendingRotate, 150L)   // coalesce rapid flips
            }
        }
        displayListener = listener
        // MUST be a real Looper, never null: this runs from the Looper-less
        // capture thread, and a null handler would throw and unwind runCapture
        // before the ServerSocket is even created.
        dm.registerDisplayListener(listener, rotateHandler)
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
    /**
     * Should the sound go on the wire right now?
     *
     * The wearer can force it either way; AUTO is the default and answers no
     * while Bluetooth is already carrying the phone's sound, because both at
     * once arrive at the same ears a couple of hundred milliseconds apart and
     * are heard as an echo. Bluetooth is the path that gets to win: it also
     * carries the audio this one is not permitted to capture at all — a live
     * voice conversation runs as USAGE_VOICE_COMMUNICATION, which
     * AudioPlaybackCapture is forbidden to touch — so it is strictly the more
     * complete of the two.
     */
    private fun shouldStreamAudio(): Boolean = when (HudCfg.audioMode(this)) {
        1 -> true
        2 -> false
        else -> !btAudioOut
    }

    /**
     * Keep [btAudioOut] honest as headphones come and go mid-session. A
     * one-time check at start would be wrong the moment the wearer connects
     * the glasses over Bluetooth after the mirror is already running — which
     * is exactly the order it happens in.
     */
    private fun watchBluetoothOutput() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        fun refresh() {
            val bt = runCatching {
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(false)
            if (bt != btAudioOut) {
                btAudioOut = bt
                Log.i(TAG, "bluetooth audio out=$bt — streaming audio=${shouldStreamAudio()}")
            }
        }
        refresh()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }
        audioDevices = cb
        runCatching { am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
    }

    private fun startAudioCapture(mp: MediaProjection) {
        val cfg = runCatching {
            AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                // Assistant/TTS voices (a spoken reply from an assistant app)
                // play under USAGE_ASSISTANT, not USAGE_MEDIA, so without this
                // they never reach the glasses. NOTE: a live two-way voice mode
                // that runs as a call uses USAGE_VOICE_COMMUNICATION, which the
                // OS forbids capturing at all — that audio cannot be mirrored.
                .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
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

        watchBluetoothOutput()

        audioThread = kotlin.concurrent.thread(name = "dexprobe-audio") {
            val buf = ByteArray(minBuf)
            while (running && audioOn) {
                // Always DRAIN, even when not sending. Leaving the record
                // buffer to fill would make the audio that resumes on the far
                // side be whatever was captured seconds ago.
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (!shouldStreamAudio()) continue
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

    /**
     * A screen mirror of a sleeping display is a black rectangle. The whole
     * point of this app is that the wearer looks at the GLASSES and drives the
     * phone from the temple pad — they never touch the phone, so its display
     * would time out in seconds and the mirror would go dark. Injected
     * accessibility gestures do not count as user activity either, so nothing
     * in normal use keeps the panel lit. This does.
     *
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated in favour of a window flag, but a
     * background capture service has no window to hang FLAG_KEEP_SCREEN_ON on,
     * and on this Samsung the lock is honoured — verified by frame rate, which
     * collapses to <1fps the moment the phone dozes and holds at the requested
     * fps while it is held.
     */
    @Suppress("DEPRECATION")
    private fun acquireScreenLock() {
        if (screenLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        screenLock = pm.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "x3mira:mirror"
        ).apply { setReferenceCounted(false); acquire() }
        Log.i(TAG, "screen lock acquired — source display stays awake")
    }

    private fun shutdown() {
        runCatching { if (screenLock?.isHeld == true) screenLock?.release() }; screenLock = null
        runCatching { nsdReg?.let { nsd?.unregisterService(it) } }
        running = false
        audioOn = false
        runCatching { audioRecord?.stop(); audioRecord?.release() }; audioRecord = null
        runCatching {
            audioDevices?.let {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .unregisterAudioDeviceCallback(it)
            }
        }; audioDevices = null
        runCatching {
            displayListener?.let {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(it)
            }
        }
        rotateHandler.removeCallbacksAndMessages(null)
        NotifBridge.onChange = null
        HudCfg.onChange = null
        live = false
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
        const val TAG = "X3Mira"
        // Follow phone rotation by reconfiguring the pipeline in place. If the
        // isolation test ever shows the projection dying on setSurface/resize on
        // this hardware, set false to revert to guaranteed-alive capture-once.
        const val ROTATE_FOLLOW = true
        const val PORT = 7391
        const val CHAN = "dexprobe"
        const val MAGIC_HELLO = 0xDEC0DE00.toInt()
        const val MAGIC_FRAME = 0xDEC0DE01.toInt()
        const val MAGIC_AUDIO = 0xDEC0DE02.toInt()
        const val MAGIC_NOTIF = 0xDEC0DE03.toInt()
        const val MAGIC_HUDCFG = 0xDEC0DE04.toInt()
        const val MAGIC_REPLY = 0xDEC0DE05.toInt()
        /** RPC kinds carried by the 'Q' verb. */
        const val RPC_HTTP = 1
        /** True while the capture pipeline is up; read by MainActivity so a
         *  second icon-tap opens settings instead of re-requesting capture. */
        @Volatile var live = false
        const val AUDIO_RATE = 48_000
        const val SERVICE_TYPE = "_x3mira._tcp."
        const val SERVICE_NAME = "X3Mira"
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
