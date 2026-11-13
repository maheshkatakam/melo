package com.maheshkatakam.melo.lyrics

import com.maheshkatakam.melo.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** Real lyrics found online. [text] is LRC when [synced], plain text otherwise. */
data class FoundLyrics(val text: String, val synced: Boolean, val title: String, val artist: String)

/** One entry from the lyrics database. */
data class LyricsCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,
    val instrumental: Boolean,
    val plain: String?,
    val synced: String?,
)

/**
 * Real, human-written lyrics from LRCLIB (lrclib.net): a free, open lyrics database with no
 * account or key. It has timed lyrics for a lot of Indian film music, usually in the song's own
 * script. Only the song's title, artist and album are sent. Found lyrics are stored, so after
 * that they work offline like everything else.
 */
object OnlineLyrics {

    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "Melo/1.0 (personal offline music player; https://github.com/maheshkatakam/melo)"

    suspend fun find(song: Song): FoundLyrics? = withContext(Dispatchers.IO) {
        val title = cleanTitle(song.title.ifBlank { song.fileName })
        if (title.length < 2) return@withContext null
        val artist = song.artist.takeUnless { isUnknown(it) }?.let { cleanArtist(it) }
        val album = song.album.takeUnless { isUnknown(it) }?.let { cleanTitle(it) }
        val queries = buildList {
            if (artist != null) add(listOf("track_name" to title, "artist_name" to artist))
            add(listOf("track_name" to title))
            if (album != null && album != title) add(listOf("q" to "$title $album"))
            add(listOf("q" to title))
        }
        val seen = HashSet<String>()
        val candidates = mutableListOf<LyricsCandidate>()
        for (q in queries) {
            val found = search(q) ?: return@withContext null // offline or server trouble
            found.filter { seen.add("${it.title}|${it.artist}|${it.durationSec}|${it.synced?.length}") }.let { candidates += it }
            val best = pick(candidates, title, artist, song.durationMs / 1000.0)
            if (best != null && candidates.size >= 3) break
        }
        val best = pick(candidates, title, artist, song.durationMs / 1000.0) ?: return@withContext null
        val synced = best.synced?.takeIf { it.isNotBlank() }
        FoundLyrics(
            text = synced ?: best.plain.orEmpty(),
            synced = synced != null,
            title = best.title,
            artist = best.artist,
        )
    }

    private fun search(params: List<Pair<String, String>>): List<LyricsCandidate>? {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = (URL("$BASE/search?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LyricsCandidate(
                    title = o.optString("trackName"),
                    artist = o.optString("artistName"),
                    album = o.optString("albumName"),
                    durationSec = o.optDouble("duration", 0.0),
                    instrumental = o.optBoolean("instrumental", false),
                    plain = o.optString("plainLyrics").takeUnless { o.isNull("plainLyrics") || it.isBlank() },
                    synced = o.optString("syncedLyrics").takeUnless { o.isNull("syncedLyrics") || it.isBlank() },
                )
            }
        } catch (t: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Chooses the entry that is really this song: the length must match (within a few
     * seconds) and the title must match. Timed lyrics and lyrics in the song's own script
     * (which the phone can translate) win ties.
     */
    fun pick(candidates: List<LyricsCandidate>, title: String, artist: String?, durationSec: Double): LyricsCandidate? {
        val wantTitle = tokens(title)
        if (wantTitle.isEmpty()) return null
        val wantArtist = artist?.let { tokens(it) }.orEmpty()
        var best: LyricsCandidate? = null
        var bestScore = 0.0
        for (c in candidates) {
            if (c.instrumental || (c.synced == null && c.plain == null)) continue
            val titleSim = similarity(wantTitle, tokens(cleanTitle(c.title)))
            if (titleSim < 0.5) continue
            val artistSim = if (wantArtist.isEmpty()) 0.0 else similarity(wantArtist, tokens(c.artist))
            val diff = if (durationSec > 0 && c.durationSec > 0) abs(c.durationSec - durationSec) else 99.0
            // Without a matching length we need a strong title + artist match.
            val lengthOk = diff <= 4.0 || (diff <= 10.0 && artistSim >= 0.5 && titleSim >= 0.8)
            if (!lengthOk) continue
            var score = titleSim * 2 + artistSim + (4.0 - diff.coerceAtMost(4.0)) / 4.0
            if (c.synced != null) score += 0.6
            if (!isMostlyLatin(c.synced ?: c.plain.orEmpty())) score += 0.4
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    /**
     * Turns file names and download-site titles into a plain song title:
     * "Chiluka_Kshemama_oAoqcn_alZg" → "Chiluka Kshemama",
     * "Samajavaragamana (From \"Ala Vaikunthapurramuloo\") [Lyrical Video]" → "Samajavaragamana".
     */
    fun cleanTitle(raw: String): String {
        var t = raw.substringBeforeLast('.').takeIf { raw.matches(Regex(""".*\.(mp3|m4a|flac|ogg|opus|wav|aac|wma)$""", RegexOption.IGNORE_CASE)) } ?: raw
        t = stripVideoId(t)
        t = t.replace('_', ' ')
        t = t.replace(Regex("""\((?:[^)]*\b(from|feat|ft|official|video|lyric|lyrical|audio|full|hd|4k|remaster|remastered|version|song|songs)\b[^)]*)\)""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\[[^\]]*]"""), " ")
        t = t.replace(
            Regex("""\b(full video song|full audio song|full song|video song|audio song|lyrical video|lyric video|official|lyrical|lyrics?|video|hd|4k|320 ?kbps|128 ?kbps|mp3)\b""", RegexOption.IGNORE_CASE),
            " ",
        )
        t = t.replace(Regex("""\s+-\s+[^-]*\b(songs?|music|mp3|com|in|net)\b.*$""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""^\d{1,3}\s*[-.]\s*"""), "") // track numbers
        return t.replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
    }

    /** Downloaders add the 11-character YouTube video id: "Song_Name_oAoqcn_alZg". */
    private fun stripVideoId(t: String): String {
        val m = Regex("""([_ ])([A-Za-z0-9_-]{11})$""").find(t) ?: return t
        val id = m.groupValues[2]
        val looksLikeId = m.groupValues[1] == "_" || id.any { it.isDigit() || it == '-' || it == '_' } ||
            id.drop(1).any { it.isUpperCase() }
        return if (looksLikeId) t.substring(0, m.range.first) else t
    }

    private fun cleanArtist(raw: String): String = raw.split(Regex("""\s*[,;&/]\s*|\s+feat\.?\s+|\s+ft\.?\s+""", RegexOption.IGNORE_CASE))
        .firstOrNull { it.isNotBlank() }?.trim() ?: raw

    private fun isUnknown(s: String) = s.isBlank() || s.equals("<unknown>", true) || s.startsWith("Unknown", true)

    private fun tokens(s: String): Set<String> = s.lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .split(' ')
        .filter { it.length > 1 && it !in STOP }
        .map { normalizeRoman(it) }
        .toSet()

    /** Romanised Indian titles are spelt many ways ("Kshemama"/"Kshemamaa", "Naatu"/"Natu"). */
    private fun normalizeRoman(w: String): String = w
        .replace(Regex("(.)\\1+"), "$1")
        .replace("ee", "i").replace("oo", "u").replace("aa", "a")
        .replace("th", "t").replace("dh", "d").replace("bh", "b").replace("kh", "k").replace("ph", "p")
        .replace(Regex("a$"), "")

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val common = a.count { it in b }
        return common.toDouble() / a.size.coerceAtLeast(1)
    }

    private fun isMostlyLatin(text: String): Boolean {
        var latin = 0
        var other = 0
        for (ch in text) {
            if (!ch.isLetter()) continue
            if (ch.code < 0x250) latin++ else other++
        }
        return latin >= other
    }

    private val STOP = setOf("the", "a", "an", "from", "feat", "ft", "song", "version", "remix", "of")
}
