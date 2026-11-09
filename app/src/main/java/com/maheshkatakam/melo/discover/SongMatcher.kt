package com.maheshkatakam.melo.discover

import com.maheshkatakam.melo.data.Song
import com.maheshkatakam.melo.data.UNKNOWN_ARTIST
import java.text.Normalizer

/**
 * Decides whether a song in your library is the one you identified earlier, even when the
 * tags differ a little ("Kesariya (From \"Brahmastra\")" vs "Kesariya", "A & B" vs "A, B").
 */
object SongMatcher {

    private val bracketed = Regex("""\s*[\(\[\{].*?[\)\]\}]""")
    private val suffixes = Regex(
        """\s+-\s+.*(remaster|version|edit|mix|live|from|feat|lofi|slowed|reverb).*$""",
        RegexOption.IGNORE_CASE,
    )
    private val featuring = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase()

    fun normalizeTitle(title: String): String {
        var t = title.replace(bracketed, "")
        t = t.replace(suffixes, "")
        t = t.replace(featuring, "")
        return fold(t).replace(nonWord, " ").trim()
    }

    private fun artistTokens(artist: String?): Set<String> {
        if (artist.isNullOrBlank() || artist == UNKNOWN_ARTIST) return emptySet()
        return fold(artist)
            .split(Regex("""\s*(,|&|/|;|\bx\b|\band\b|\bfeat\.?|\bft\.?)\s*"""))
            .map { it.replace(nonWord, " ").trim() }
            .filter { it.length >= 3 }
            .toSet()
    }

    fun matches(song: Song, title: String?, artist: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val wanted = normalizeTitle(title)
        if (wanted.length < 2) return false
        val local = normalizeTitle(song.title)
        val fileName = normalizeTitle(song.fileName.substringBeforeLast('.'))
        val titleOk = local == wanted ||
            (wanted.length >= 5 && (local.startsWith(wanted) || wanted.startsWith(local) && local.length >= 5)) ||
            (wanted.length >= 5 && fileName.contains(wanted))
        if (!titleOk) return false

        val wantedArtists = artistTokens(artist)
        val localArtists = artistTokens(song.artist)
        if (wantedArtists.isEmpty() || localArtists.isEmpty()) {
            // No artist to compare: only trust an exact title match.
            return local == wanted || fileName == wanted
        }
        return wantedArtists.any { w -> localArtists.any { l -> l.contains(w) || w.contains(l) } } ||
            wantedArtists.any { w -> fileName.contains(w) }
    }

    fun find(songs: List<Song>, title: String?, artist: String?): Song? =
        songs.firstOrNull { matches(it, title, artist) }
}
