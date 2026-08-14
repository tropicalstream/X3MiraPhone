package com.x3mira.phone

import java.io.File

/**
 * Reader for the pushed API-key files.
 *
 * README documents one bare key per file. What actually arrives is a key
 * with a label in front of it — `gemini api key = AQ…` — and frequently
 * several providers in the one file, because listing them is the natural
 * thing to write when you are pasting keys out of a browser. Read with
 * `readText().trim()` that yields the label as part of the key, and the app
 * then authenticates with nonsense and reports only that the key "did not
 * work" — the least useful failure available.
 *
 * So a file is read as labelled entries the moment it contains any
 * `name = value` line, and as one bare key otherwise. A provider also looks
 * through the OTHER key files, because a wearer who puts every key in
 * gemini_api_key.txt has done something entirely reasonable.
 */
object KeyFile {

    /** Provider id → words that identify it in a label. */
    private val ALIASES = mapOf(
        "gemini" to listOf("gemini", "google"),
        "groq" to listOf("groq", "whisper"),
        "cerebras" to listOf("cerebras")
    )

    /** Files worth searching, in the order a provider should prefer them. */
    private fun candidateNames(provider: String): List<String> = buildList {
        add("${provider}_api_key.txt")
        add("api_keys.txt")
        ALIASES.keys.forEach { p -> if (p != provider) add("${p}_api_key.txt") }
    }

    /**
     * Every labelled entry in [text], keyed by provider id. Empty when the
     * text is a single bare key — use [bareKey] for that.
     */
    fun parseLabelled(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val sep = line.indexOf('=')
            if (sep <= 0) continue
            val label = line.substring(0, sep).trim().lowercase()
            val value = line.substring(sep + 1).trim().trim('"', '\'')
            if (value.isEmpty()) continue
            val provider = ALIASES.entries
                .firstOrNull { (_, words) -> words.any { label.contains(it) } }
                ?.key
                ?: continue
            out.putIfAbsent(provider, value)
        }
        return out
    }

    /**
     * The whole file as one key, when it holds exactly one non-blank,
     * non-comment line and that line is not a `name = value` pair.
     */
    fun bareKey(text: String): String? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()
        if (lines.size != 1) return null
        val only = lines[0]
        // A lone "gemini api key = AQ…" is labelled, not bare.
        return if (only.contains('=')) null else only
    }

    /** [provider]'s key from one file's text, labelled or bare. */
    fun valueFrom(text: String, provider: String, allowBare: Boolean): String? =
        parseLabelled(text)[provider] ?: if (allowBare) bareKey(text) else null

    /**
     * [provider]'s key from [dir], searching its own file first and then the
     * other key files for a labelled entry. A BARE key is only ever taken
     * from the provider's own file — a lone key in gemini_api_key.txt is a
     * Gemini key and must not be handed to Groq.
     */
    fun resolveFromDir(dir: File?, provider: String): Result? {
        if (dir == null) return null
        for (name in candidateNames(provider)) {
            val f = File(dir, name)
            if (!f.exists()) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val ownFile = name == "${provider}_api_key.txt"
            val value = valueFrom(text, provider, allowBare = ownFile)
            if (!value.isNullOrBlank()) return Result(value, name)
        }
        return null
    }

    data class Result(val value: String, val fileName: String)
}
