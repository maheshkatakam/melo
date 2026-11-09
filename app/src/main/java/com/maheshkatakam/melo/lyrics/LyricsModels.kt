package com.maheshkatakam.melo.lyrics

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
data class LyricLine(val timeMs: Long?, val text: String)

@Immutable
data class Lyrics(val lines: List<LyricLine>) {
    val synced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs != null }
    val plainText: String get() = lines.joinToString("\n") { it.text }
    fun isBlank() = lines.all { it.text.isBlank() }
}

object LyricsSource {
    const val EMBEDDED = "embedded"
    const val LRC_FILE = "lrc"
    const val IMPORTED = "imported"
    const val PASTED = "pasted"
    const val AI = "ai"
    const val NONE = "none"
}

/** Human-friendly name for a language code like "te" or "hi-Latn". */
fun languageName(code: String?): String {
    if (code.isNullOrBlank() || code == "und") return "an unknown language"
    val base = code.substringBefore('-')
    val name = Locale.forLanguageTag(base).getDisplayLanguage(Locale.ENGLISH)
    val romanized = code.endsWith("-Latn", ignoreCase = true)
    return when {
        name.isNullOrBlank() || name == base -> code
        romanized -> "$name (in English letters)"
        else -> name
    }
}

fun isEnglish(code: String?): Boolean = code != null && code.substringBefore('-').equals("en", ignoreCase = true)
