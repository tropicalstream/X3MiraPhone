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
     * OPT-IN, DEFAULT OFF. Lets the agent read the phone's screen TEXT through
     * the accessibility service instead of only seeing the mirrored picture.
     *
     * It is off by default and must stay a deliberate choice, because
     * res/xml/dex_input_service.xml is written as a service that "must be able
     * to touch the screen and must not be able to read it" — turning this on
     * reverses that, and the wearer is the only one who gets to do so. With it
     * off the agent still works: it looks at the same picture the wearer is
     * looking at.
     */
    fun a11yContext(ctx: Context) = prefs(ctx).getBoolean("agent_a11y", false)
    fun setA11yContext(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("agent_a11y", v).apply()

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

    /** Set by CaptureService so a settings change reaches the glasses live. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("hudcfg", Context.MODE_PRIVATE)
}
