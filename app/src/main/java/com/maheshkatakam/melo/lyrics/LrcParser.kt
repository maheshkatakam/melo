package com.maheshkatakam.melo.lyrics

import java.util.Locale

/** Reads and writes LRC ("[01:23.45] line") lyrics, falling back to plain text. */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[(ar|ti|al|by|au|length|re|ve|#|la|lang):.*]$""", RegexOption.IGNORE_CASE)

    fun looksSynced(text: String): Boolean = timeTag.containsMatchIn(text)

    fun parse(text: String): Lyrics {
        if (!looksSynced(text)) return parsePlain(text)
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || metaTag.matches(line) || offsetTag.matches(line)) return@forEach
            val stamps = timeTag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val content = line.substring(stamps.last().range.last + 1)
                .replace(Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>"""), "") // word-level karaoke tags
                .trim()
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100
                    2 -> fracRaw.toLong() * 10
                    else -> fracRaw.take(3).toLong()
                }
                val ms = (min * 60_000 + sec * 1000 + frac - offset).coerceAtLeast(0)
                lines += LyricLine(ms, content)
            }
        }
        return Lyrics(lines.sortedBy { it.timeMs })
    }

    fun parsePlain(text: String): Lyrics {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
            .map { LyricLine(null, it) }
        return Lyrics(lines)
    }

    /** Synced lyrics become LRC text; plain lyrics stay plain. */
    fun serialize(lyrics: Lyrics): String =
        if (lyrics.synced) {
            lyrics.lines.joinToString("\n") { "[${format(it.timeMs ?: 0)}]${it.text}" }
        } else {
            lyrics.plainText
        }

    private fun format(ms: Long): String {
        val min = ms / 60_000
        val sec = (ms % 60_000) / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs)
    }
}
