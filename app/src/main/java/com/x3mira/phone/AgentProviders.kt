package com.x3mira.phone

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The LLM side of the page agent, carved out of SmartView's GroqSpeech and
 * repointed at X3Mira's key storage.
 *
 * Two things are deliberately kept from SmartView rather than simplified:
 *
 *  1. The credential is attached NATIVELY, never handed to the page.
 *     page-agent stores its options verbatim on `this.config` in the page's
 *     own JS world, so an apiKey passed in would be readable by any script
 *     on any site the wearer visits. The bundle gets a placeholder and
 *     [rawRequest] adds the real header.
 *
 *  2. The host allowlist is enforced HERE, not only in the injected JS.
 *     The JS check runs in the page's world where any script can redefine
 *     it, so it is a convenience; this one is the actual control. SmartView
 *     learned that the hard way — its first version substring-matched the
 *     whole URL including the query, so a page could route arbitrary
 *     requests through the app just by naming an allowed host in a
 *     parameter.
 */
object AgentProviders {

    private const val TAG = "X3MiraAgentNet"

    data class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val model: String,
        /** Provider id used by KeyFile / the settings slots. */
        val keyName: String
    )

    val ALL = listOf(
        // gemini-flash-lite-latest is the only free Gemini model that is both
        // reliably available and does tool calling — full/preview Flash return
        // 503 "high demand", 2.0-flash 429s.
        Provider(
            "gemini", "Gemini Flash-Lite",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-lite-latest", "gemini"
        ),
        Provider(
            "cerebras", "Cerebras Llama-3.3 70B",
            "https://api.cerebras.ai/v1", "llama-3.3-70b", "cerebras"
        ),
        Provider(
            "groq", "Groq Llama-3.3 70B",
            "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "groq"
        )
    )

    /** Hosts page-agent fetches cross-origin; they must bypass the page CSP. */
    val HOSTS = listOf("api.groq.com", "generativelanguage.googleapis.com", "api.cerebras.ai")

    private const val PREFS_FILE = "x3mira_config"
    private const val PREF_PROVIDER = "agent_provider"

    fun provider(context: Context): Provider {
        val id = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_PROVIDER, null)
        return ALL.firstOrNull { it.id == id } ?: firstConfigured(context) ?: ALL.first()
    }

    fun setProvider(context: Context, id: String) {
        if (ALL.none { it.id == id }) return
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_PROVIDER, id).apply()
    }

    /**
     * With no explicit choice, use whichever provider actually has a key.
     * SmartView defaulted to Gemini unconditionally and then failed with an
     * auth error on a device where only Groq was set up.
     */
    private fun firstConfigured(context: Context): Provider? =
        ALL.firstOrNull { key(context, it).isNotBlank() }

    fun key(context: Context, p: Provider = provider(context)): String {
        KeyFile.resolveFromDir(context.getExternalFilesDir(null), p.keyName)
            ?.value?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getString("${p.keyName}_api_key", "").orEmpty().trim()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Perform one of page-agent's LLM calls natively. [onResult] gets
     * code=0 when the request could not be made at all.
     */
    fun rawRequest(
        context: Context,
        url: String,
        method: String,
        headersJson: String,
        body: String,
        onResult: (code: Int, ok: Boolean, bytes: ByteArray) -> Unit
    ) {
        Thread {
            try {
                val host = runCatching {
                    java.net.URI(url).host?.lowercase()
                }.getOrNull()
                val https = url.startsWith("https://", ignoreCase = true)
                val allowed = https && host != null &&
                    HOSTS.any { host == it || host.endsWith(".$it") }
                if (!allowed) {
                    Log.w(TAG, "REFUSED non-allowlisted url host=$host")
                    onResult(0, false, "blocked: host not allowed".toByteArray())
                    return@Thread
                }

                val builder = Request.Builder().url(url)
                var contentType = "application/json"
                runCatching {
                    val h = JSONObject(headersJson)
                    for (k in h.keys()) {
                        val v = h.optString(k)
                        // The page proposes headers; it does not get to set
                        // the credential. Anything authorization-shaped is
                        // dropped and replaced below.
                        if (k.equals("authorization", true)) continue
                        if (k.equals("x-goog-api-key", true)) continue
                        if (k.equals("content-type", true)) contentType = v
                        builder.header(k, v)
                    }
                }
                // Bearer for all three: every provider here is addressed
                // through its OpenAI-compatible endpoint (note Gemini's
                // baseUrl ends /v1beta/openai), so they all want a bearer.
                // x-goog-api-key is right for Google's NATIVE API and wrong
                // for its compat layer — it produced "Missing or invalid
                // Authorization header" on every agent request.
                val k = key(context)
                if (k.isNotBlank()) builder.header("Authorization", "Bearer $k")

                if (method.equals("GET", true) || method.equals("HEAD", true)) {
                    builder.method(method.uppercase(), null)
                } else {
                    builder.method(
                        method.uppercase(),
                        body.toRequestBody(contentType.toMediaType())
                    )
                }

                http.newCall(builder.build()).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "HTTP ${resp.code}: ${String(bytes).take(300)}")
                    }
                    onResult(resp.code, resp.isSuccessful, bytes)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "rawRequest failed", t)
                onResult(0, false, (t.message ?: "network error").toByteArray())
            }
        }.start()
    }
}
