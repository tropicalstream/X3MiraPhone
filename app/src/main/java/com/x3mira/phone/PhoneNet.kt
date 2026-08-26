package com.x3mira.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * The phone's route to the internet, kept current as the phone moves.
 *
 * The glasses have no SIM and no internet of their own: every model call is
 * made by this phone on their behalf. So when the wearer walks out of the
 * house, "the agent stopped working" and "this phone changed networks" are
 * the same event, and how quickly the second is noticed decides how long the
 * first lasts.
 *
 * WHAT THIS DOES NOT DO IS THE POINT. It does not measure signal strength,
 * race Wi-Fi against cellular, ping a chosen host to decide if a link is
 * "really" up, or prefer one transport over another. Android already makes
 * that decision continuously, with information no app can see — whether the
 * link validated, whether a captive portal is in the way, what the modem is
 * doing, what the user asked for — and it publishes the answer as the
 * DEFAULT NETWORK. Second-guessing it is how an app ends up tuned to one
 * router, one carrier and one building, which is exactly the wrong shape for
 * something other people will install.
 *
 * So the platform picks; this reacts, and the reaction is the part that was
 * actually missing:
 *
 *  1. THE POOL IS EVICTED ON EVERY CHANGE. This is the real bug. OkHttp keeps
 *     connections alive to reuse them, and a pooled socket belongs to the
 *     interface it was opened on. Move from Wi-Fi to cellular and those
 *     sockets are dead, but nothing says so — the next request adopts one and
 *     waits for a reply that can never come, until the read timeout. A
 *     handover the platform completed in a second could still cost the wearer
 *     a minute of silence, which reads as "the model is busy" rather than as
 *     what it is.
 *
 *  2. REQUESTS ARE BOUND TO THE CHOSEN NETWORK, sockets and DNS both. An
 *     unbound socket takes whatever route the system picks at connect time,
 *     which is usually right and occasionally is a link that is up but going
 *     nowhere. Binding also matters because this app may be hosting a Wi-Fi
 *     Direct group at the same time, adding routes that have no path to the
 *     internet at all.
 *
 *  3. EVERY CALL IS BOUNDED END TO END. A per-stage timeout does not bound a
 *     request that keeps making slow progress, and a wearer waiting on a
 *     spoken answer would rather be told it failed than wait two minutes.
 *
 * Degrading gracefully is deliberate: with no network known yet, or on a
 * device where the callback never fires, the client is simply unbound and the
 * system routes it as it always did. Nothing here assumes cellular exists —
 * plenty of Android devices have only Wi-Fi, and the default network is
 * whatever they have.
 */
object PhoneNet {

    private const val TAG = "X3MiraNet"

    @Volatile private var current: Network? = null
    @Volatile private var cached: OkHttpClient? = null
    @Volatile private var started = false

    /** Safe to call more than once; only the first registration takes. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "no ConnectivityManager — requests stay unbound")
            return
        }
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = adopt(network, "available")

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    // Validated means the platform reached the internet on it.
                    // A link can be associated and useless — the case that
                    // makes a phone sit on dead Wi-Fi — so this is the signal
                    // worth acting on, not mere availability.
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        adopt(network, "validated")
                    }
                }

                override fun onLost(network: Network) {
                    if (network == current) adopt(null, "lost")
                }
            })
        }.onFailure { Log.w(TAG, "default-network callback failed: ${it.message}") }
    }

    private fun adopt(network: Network?, why: String) {
        if (network == current) return
        current = network
        // The old pool's sockets live on an interface we have just left.
        // Evicting is what turns a handover into a hiccup instead of a hang.
        runCatching { cached?.connectionPool?.evictAll() }
        cached = null
        Log.i(TAG, "default network -> ${network ?: "none"} ($why); pool evicted")
    }

    /**
     * A client bound to the current default network, rebuilt whenever that
     * changes. Cached in between so connection reuse — the thing that makes
     * the second call to a host fast — still works within one network.
     */
    fun client(): OkHttpClient {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: build(current).also { cached = it }
        }
    }

    private fun build(net: Network?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // The end-to-end bound. Vision calls over a busy link have been
            // measured in the tens of seconds, so this is generous — it is
            // here to catch the request that will never finish, not to hurry
            // the one that is merely slow.
            .callTimeout(75, TimeUnit.SECONDS)
        if (net != null) {
            b.socketFactory(net.socketFactory)
            // DNS on the same network as the socket. Resolving on one
            // interface and connecting on another is a subtle way to get
            // answers that do not route.
            b.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    runCatching { net.getAllByName(hostname).toList() }
                        .getOrElse { Dns.SYSTEM.lookup(hostname) }
            })
        }
        return b.build()
    }
}
