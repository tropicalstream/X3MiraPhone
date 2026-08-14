package com.x3mira.phone

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import android.util.Log

/**
 * A Wi-Fi Direct group with the phone as owner, so the glasses can reach it
 * with no router and no hotspot.
 *
 * WHY NOT THE HOTSPOT. Turning on the mobile hotspot works and is what the app
 * needed until now, but it makes every byte the glasses send TETHERED traffic —
 * which carriers commonly meter and throttle on a budget separate from
 * on-device traffic, over the same tower — and it puts the agent's uploads on
 * the same Wi-Fi channel the mirror is already filling with 4 Mbps of video.
 * It is also a trip through Settings that the wearer has to remember.
 *
 * Wi-Fi Direct is not tethering. The phone keeps CELLULAR as its default
 * network, so its own traffic — including the model calls it now performs for
 * the glasses — leaves over LTE untouched, while this group carries only the
 * mirror.
 *
 * THE PHONE IS DELIBERATELY THE GROUP OWNER. createGroup() makes it one
 * outright rather than negotiating for the role, because the owner is the one
 * with a fixed, known address (192.168.49.1) and this end is the one holding a
 * ServerSocket. Letting the role be negotiated would mean the socket sometimes
 * needed to live on the other device.
 *
 * This is ONLY viable because the agent's requests now go through the phone. A
 * P2P group has no route to the internet: if the glasses still had to reach
 * Gemini themselves, this would produce a perfect mirror attached to an agent
 * that failed every errand — which is worse than not working, because it looks
 * like the model is broken.
 */
object P2pHost {

    private const val TAG = "X3MiraP2p"

    /** Matches the service the glasses look for. */
    const val INSTANCE = "x3mira"
    const val SERVICE = "_x3mira._tcp"

    /**
     * A FIXED name and passphrase, rather than the random pair a plain
     * createGroup generates. Deterministic credentials mean the glasses can
     * join without being told anything out of band — and the name must begin
     * with DIRECT- because the platform requires it.
     */
    const val NET_NAME = "DIRECT-x3mira"
    const val PASSPHRASE = "x3mira-link-2026"
    /** Patience before conceding the learned channel is not working. */
    // Five minutes, not ninety seconds: the join includes a HUMAN tapping an
    // invitation dialog, and the first fallback raced the wearer to it — the
    // group was rebuilt on another channel while the accept was mid-air.
    const val JOIN_FALLBACK_MS = 300_000L

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var hostCtx: Context? = null

    @Volatile var active = false
        private set

    fun start(ctx: Context, port: Int) {
        if (active) return
        hostCtx = ctx.applicationContext
        val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: run {
            Log.w(TAG, "no Wi-Fi P2P service on this device")
            return
        }
        val c = m.initialize(ctx, Looper.getMainLooper(), null)
        manager = m; channel = c

        // Clear any group left behind by a previous run before making one.
        // A stale persistent group is the usual reason createGroup returns
        // BUSY and the glasses then find nothing to join.
        runCatching {
            m.removeGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { create(m, c, port) }
                override fun onFailure(reason: Int) { create(m, c, port) }
            })
        }.onFailure { create(m, c, port) }
    }

    private fun create(m: WifiP2pManager, c: WifiP2pManager.Channel, port: Int) {
        // Prefer the CHANNEL THE GLASSES ALREADY LIVE ON, learned over the
        // wire last session. A single-radio client associated to a router on
        // one channel and a group on another time-slices between the two and
        // sustained video collapses — measured at 2 fps against 40+ once the
        // channels agree. When nothing was ever learned, 2.4 GHz: the band
        // every peer supports and the one that found the glasses at all
        // (the framework's own choice, 5 GHz ch149, was invisible to them).
        val learned = hostCtx?.let { HudCfg.p2pFreq(it) } ?: 0
        createOn(m, c, port, learned.takeIf { it > 2000 })
    }

    private fun createOn(m: WifiP2pManager, c: WifiP2pManager.Channel, port: Int, freq: Int?) {
        runCatching {
            val b = android.net.wifi.p2p.WifiP2pConfig.Builder()
                .setNetworkName(NET_NAME)
                .setPassphrase(PASSPHRASE)
            if (freq != null) b.setGroupOperatingFrequency(freq)
            else b.setGroupOperatingBand(android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            m.createGroup(c, b.build(), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    active = true
                    Log.i(TAG, "group created on ${freq ?: "2.4GHz"} — owner at 192.168.49.1:$port")
                    advertise(m, c, port)
                    // A learned channel the GLASSES cannot actually join —
                    // wrong hardware, regulatory band, stale memory — would
                    // strand the pair silently. If nobody arrives, fall back
                    // to the band that is known to work.
                    if (freq != null) armJoinFallback(m, c, port)
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "createGroup(${freq ?: "2.4"}) failed: $reason")
                    if (freq != null) {
                        Log.i(TAG, "retrying on 2.4 GHz")
                        createOn(m, c, port, null)
                    }
                }
            })
        }.onFailure { Log.w(TAG, "createGroup threw: ${it.message}") }
    }

    /** If no client joins the learned-channel group, rebuild it on 2.4. */
    private fun armJoinFallback(m: WifiP2pManager, c: WifiP2pManager.Channel, port: Int) {
        beat.postDelayed({
            if (!active) return@postDelayed
            runCatching {
                m.requestGroupInfo(c) { g ->
                    if (g != null && g.clientList.isEmpty()) {
                        Log.w(TAG, "no client joined the learned channel — rebuilding on 2.4 GHz")
                        m.removeGroup(c, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { createOn(m, c, port, null) }
                            override fun onFailure(reason: Int) { createOn(m, c, port, null) }
                        })
                    }
                }
            }
        }, JOIN_FALLBACK_MS)
    }

    /**
     * Announce the service so the glasses connect to THIS phone rather than to
     * whatever Wi-Fi Direct device happens to be nearest. A printer and a TV
     * are both perfectly good P2P peers and neither is running the mirror.
     */
    private fun advertise(m: WifiP2pManager, c: WifiP2pManager.Channel, port: Int) {
        runCatching {
            val info = WifiP2pDnsSdServiceInfo.newInstance(
                INSTANCE, SERVICE, mapOf("port" to port.toString())
            )
            m.addLocalService(c, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "service advertised")
                    // Advertising is not enough on its own. A group owner that
                    // has only registered a local service does not reliably
                    // ANSWER service queries — the framework has to be in a
                    // discovery state to respond, which is why the glasses
                    // could sweep all day and find nothing while this end
                    // believed it was announcing itself.
                    keepDiscoverable(m, c)
                }
                override fun onFailure(reason: Int) { Log.w(TAG, "advertise failed: $reason") }
            })
        }
    }

    private val beat = android.os.Handler(Looper.getMainLooper())

    /** Discovery expires; re-arm it so the phone stays answerable. */
    private fun keepDiscoverable(m: WifiP2pManager, c: WifiP2pManager.Channel) {
        if (!active) return
        runCatching {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "discoverable") }
                override fun onFailure(reason: Int) { Log.w(TAG, "discoverPeers: $reason") }
            })
        }
        beat.postDelayed({ keepDiscoverable(m, c) }, 15_000L)
    }

    fun stop() {
        val m = manager; val c = channel
        if (m != null && c != null) {
            runCatching { m.clearLocalServices(c, null) }
            runCatching { m.removeGroup(c, null) }
        }
        active = false
        manager = null; channel = null
        Log.i(TAG, "group torn down")
    }
}
