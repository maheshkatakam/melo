package com.maheshkatakam.melo.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** What a recognition service found. */
data class Recognized(
    val title: String,
    val artist: String,
    val album: String?,
    val releaseDate: String?,
    val artworkUrl: String?,
    val songLink: String?,
    val spotifyUrl: String?,
    val appleMusicUrl: String?,
)

sealed interface RecognitionResult {
    data class Found(val song: Recognized) : RecognitionResult
    data object NotFound : RecognitionResult
    /** A problem the user can fix (bad key, out of free identifications…). */
    data class KeyProblem(val message: String) : RecognitionResult
    data class Failed(val message: String) : RecognitionResult
}

/**
 * AudD (audd.io): send a short recording, get the song back. Your key comes from
 * dashboard.audd.io; new accounts get 300 free identifications.
 */
object AudDClient {
    private const val ENDPOINT = "https://api.audd.io/"

    suspend fun recognize(clip: File, apiToken: String): RecognitionResult = withContext(Dispatchers.IO) {
        val boundary = "----melo" + UUID.randomUUID().toString().replace("-", "")
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 45_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "Melo-Android")
        }
        try {
            connection.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("api_token", apiToken.trim())
                field("return", "apple_music,spotify")
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\"\r\n" +
                        "Content-Type: audio/mp4\r\n\r\n").toByteArray(),
                )
                clip.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            parse(body, code)
        } catch (e: IOException) {
            RecognitionResult.Failed("Couldn't reach the recognition service. Check your internet.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String, httpCode: Int): RecognitionResult {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return RecognitionResult.Failed("The recognition service had a problem (HTTP $httpCode).")
        if (json.optString("status") == "error") {
            val error = json.optJSONObject("error")
            val code = error?.optInt("error_code") ?: 0
            val message = error?.optString("error_message").orEmpty()
            return when {
                code == 900 -> RecognitionResult.KeyProblem("Your AudD key isn't valid. Check it in Melo's settings.")
                code == 901 || code == 902 || message.contains("limit", ignoreCase = true) ->
                    RecognitionResult.KeyProblem("Your AudD key has used up its identifications. Add credit at dashboard.audd.io, or use Shazam.")
                code == 300 -> RecognitionResult.Failed("The recording was too quiet. Hold your phone closer to the music.")
                else -> RecognitionResult.Failed(message.ifBlank { "The recognition service returned error $code." })
            }
        }
        val result = json.optJSONObject("result") ?: return RecognitionResult.NotFound
        val title = result.optString("title").takeIf { it.isNotBlank() } ?: return RecognitionResult.NotFound
        val apple = result.optJSONObject("apple_music")
        val spotify = result.optJSONObject("spotify")
        val appleArt = apple?.optJSONObject("artwork")?.optString("url")
            ?.takeIf { it.isNotBlank() }
            ?.replace("{w}", "800")?.replace("{h}", "800")
        val spotifyArt = spotify?.optJSONObject("album")?.optJSONArray("images")?.firstUrl()
        return RecognitionResult.Found(
            Recognized(
                title = title,
                artist = result.optString("artist").ifBlank { "Unknown artist" },
                album = result.optString("album").takeIf { it.isNotBlank() },
                releaseDate = result.optString("release_date").takeIf { it.isNotBlank() },
                artworkUrl = appleArt ?: spotifyArt,
                songLink = result.optString("song_link").takeIf { it.isNotBlank() },
                spotifyUrl = spotify?.optJSONObject("external_urls")?.optString("spotify")?.takeIf { it.isNotBlank() },
                appleMusicUrl = apple?.optString("url")?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun JSONArray.firstUrl(): String? {
        for (i in 0 until length()) {
            val url = optJSONObject(i)?.optString("url")
            if (!url.isNullOrBlank()) return url
        }
        return null
    }
}

/**
 * Apple's free iTunes Search API (no key). Used to add a cover, album and link to songs
 * shared from Shazam, which only come with a title and artist.
 */
object ITunesLookup {
    suspend fun find(title: String, artist: String?): Recognized? = withContext(Dispatchers.IO) {
        val term = URLEncoder.encode(listOfNotNull(title, artist).joinToString(" "), "UTF-8")
        val connection = (URL("https://itunes.apple.com/search?term=$term&entity=song&limit=5").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext null
            val wanted = SongMatcher.normalizeTitle(title)
            var best: JSONObject? = null
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (SongMatcher.normalizeTitle(r.optString("trackName")) == wanted) {
                    best = r
                    break
                }
                if (best == null) best = r
            }
            val r = best ?: return@withContext null
            Recognized(
                title = title,
                artist = artist ?: r.optString("artistName"),
                album = r.optString("collectionName").takeIf { it.isNotBlank() },
                releaseDate = r.optString("releaseDate").take(10).takeIf { it.isNotBlank() },
                artworkUrl = r.optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100", "800x800"),
                songLink = null,
                spotifyUrl = null,
                appleMusicUrl = r.optString("trackViewUrl").takeIf { it.isNotBlank() },
            )
        } catch (t: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

/** Downloads a picture to a file. Returns false on any problem. */
internal suspend fun downloadTo(url: String, file: File): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching false
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
                out.toByteArray()
            }
            if (bytes.size < 512) return@runCatching false
            file.writeBytes(bytes)
            true
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
