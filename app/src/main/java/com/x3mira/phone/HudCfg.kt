package com.x3mira.phone

import android.content.Context

/**
 * What the glasses HUD shows, decided on the PHONE — because the phone has a
 * real screen and keyboard-adjacent UI, and the glasses' own settings page is
 * deliberately tiny. Stored here, pushed over the link as a MAGIC_HUDCFG
 * message whenever it changes and whenever a client connects, so the glasses
 * never need their own copy of this UI.
 */
object HudCfg {

    /** Notification banner: 0 = hidden, else max wrapped lines (1..3). */
    fun notifLines(ctx: Context) = prefs(ctx).getInt("hud_notif_lines", 1)
    fun setNotifLines(ctx: Context, v: Int) = prefs(ctx).edit().putInt("hud_notif_lines", v).apply()

    /** Readout: 0 = off, 1 = time, 2 = time+battery, 3 = date+time+battery. */
    fun readoutMode(ctx: Context) = prefs(ctx).getInt("hud_readout_mode", 3)
    fun setReadoutMode(ctx: Context, v: Int) = prefs(ctx).edit().putInt("hud_readout_mode", v).apply()

    /** HUD text size as a percentage: 80 / 100 / 120. */
    fun fontPct(ctx: Context) = prefs(ctx).getInt("hud_font_pct", 100)
    fun setFontPct(ctx: Context, v: Int) = prefs(ctx).edit().putInt("hud_font_pct", v).apply()

    /** Page agent on the glasses: tap to ask, double-tap to stop. */
    fun agentOn(ctx: Context) = prefs(ctx).getBoolean("agent_on", true)
    fun setAgentOn(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("agent_on", v).apply()

    /**
     * OPT-IN, DEFAULT OFF. Lets the agent put text into a focused field.
     *
     * This replaces a "Read screen text" toggle that promised the agent could
     * read the screen through the accessibility service. That row was inert in
     * both directions — nothing consumed the flag, and the service declaration
     * forbade the retrieval it described — so it offered the wearer a choice
     * that did nothing whichever way they set it.
     *
     * What it gates now is real and much narrower: typing. The service is
     * allowed to reach the focused input node and set its text, which is the
     * one thing gestures cannot do — tapping out a word key by key costs a
     * model round trip per letter, and the agent's own repeat-guard blocks the
     * second "s" in "best". It stays off by default because it is the only
     * thing in the app that touches window content at all.
     */
    fun agentTyping(ctx: Context) = prefs(ctx).getBoolean("agent_typing", false)
    fun setAgentTyping(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("agent_typing", v).apply()

    /**
     * Whether to stream the phone's sound to the glasses.
     *
     * 0 = AUTO (default), 1 = always, 2 = never.
     *
     * AUTO exists because of a genuine collision: the glasses can also be
     * paired to the phone over Bluetooth, and A2DP already carries the sound
     * to the same speakers. Both paths at once is the same audio twice, a
     * couple of hundred milliseconds apart — which is heard as a slight echo
     * or reverb rather than as an obvious duplicate, so it is easy to blame on
     * the codec and hard to find. AUTO simply does not send a second copy
     * while Bluetooth is carrying the first.
     *
     * The setting is phone-side only and needs no wire change: the glasses
     * play whatever arrives, and silence is a valid thing to arrive.
     */
    fun audioMode(ctx: Context) = prefs(ctx).getInt("audio_mode", 0)
    fun setAudioMode(ctx: Context, v: Int) = prefs(ctx).edit().putInt("audio_mode", v).apply()

    /**
     * Mouse-pointer speed on the glasses, as a percentage.
     *
     * 100% is how the old aim point travelled, so this reads as a change from
     * a known feel. The default is 80: at full rate the pointer overshoots
     * small targets, because the temple pad is a couple of centimetres wide
     * and is being asked to cover a whole phone screen.
     *
     * Set here rather than on the glasses because judging a pointer's speed
     * means watching it move while you change the number, and the glasses'
     * own settings panel is driven by the very pad being tuned.
     */
    fun pointerPct(ctx: Context) = prefs(ctx).getInt("pointer_pct", 80)
    fun setPointerPct(ctx: Context, v: Int) = prefs(ctx).edit().putInt("pointer_pct", v).apply()

    /**
     * Host a Wi-Fi Direct group instead of relying on a shared network.
     *
     * OFF by default. Forming a P2P group can take the Wi-Fi radio away from
     * an ordinary connection, so a pair that already works on home Wi-Fi must
     * not have that changed underneath it — this is for the case where there
     * is no router, and it is the wearer who knows when that is.
     */
    // On by default: P2P is what makes the pair work with no router in
    // reach, and the group only exists while capture runs anyway.
    fun p2pHost(ctx: Context) = prefs(ctx).getBoolean("p2p_host", true)
    fun setP2pHost(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("p2p_host", v).apply()

    /**
     * The channel the glasses' infrastructure Wi-Fi was last seen on, learned
     * over the wire. The next P2P group forms HERE, so a single-radio client
     * serves its router and the mirror on one channel instead of time-slicing
     * two — which measured as the difference between 2 fps and 40. Zero means
     * never learned; the group then defaults to 2.4 GHz.
     */
    fun p2pFreq(ctx: Context) = prefs(ctx).getInt("p2p_freq", 0)
    fun setP2pFreq(ctx: Context, v: Int) = prefs(ctx).edit().putInt("p2p_freq", v).apply()

    /** Set by CaptureService so a settings change reaches the glasses live. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("hudcfg", Context.MODE_PRIVATE)
}
