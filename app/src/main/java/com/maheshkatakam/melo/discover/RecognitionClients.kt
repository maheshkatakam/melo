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
import java.util.Locale
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
    /** Couldn't get an answer at all (no internet, server trouble). */
    data class Failed(val message: String, val offline: Boolean) : RecognitionResult
}

/**
 * Free song recognition through Shazam's catalogue, the same way the open-source
 * SongRec and ShazamIO clients do it: Melo makes the audio fingerprint on the phone
 * ([ShazamSignature]) and sends only that (a few KB, no audio) to Shazam's servers.
 *
 * This is not an official Shazam API, so it could stop working if Shazam changes it.
 */
object ShazamClient {
    private val USER_AGENTS = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
        "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
        "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
        "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G928F Build/MMB29K)",
    )

    suspend fun recognize(signatureUri: String, sampleMs: Int): RecognitionResult = withContext(Dispatchers.IO) {
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val url = "https://amp.shazam.com/discovery/v5/en-US/$country/iphone/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true" +
            "&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val body = JSONObject()
            .put("timezone", "Europe/Paris")
            .put("signature", JSONObject().put("uri", signatureUri).put("samplems", sampleMs))
            .put("timestamp", System.currentTimeMillis())
            .put("context", JSONObject())
            .put("geolocation", JSONObject())
            .toString()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "en-US")
            setRequestProperty("X-Shazam-Platform", "IPHONE")
            setRequestProperty("X-Shazam-AppVersion", "14.1.0")
            setRequestProperty("User-Agent", USER_AGENTS.random())
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code == 429) {
                return@withContext RecognitionResult.Failed("Shazam is busy. Wait a minute and try again.", offline = false)
            }
            if (code !in 200..299) {
                return@withContext RecognitionResult.Failed("Shazam didn't answer properly (error $code).", offline = false)
            }
            val text = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(text)
        } catch (e: IOException) {
            RecognitionResult.Failed("Couldn't reach Shazam. Check your internet.", offline = true)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(text: String): RecognitionResult {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return RecognitionResult.Failed("Shazam sent something unexpected.", offline = false)
        val matches = json.optJSONArray("matches")
        val track = json.optJSONObject("track")
        if (matches == null || matches.length() == 0 || track == null) return RecognitionResult.NotFound
        val title = track.optString("title").takeIf { it.isNotBlank() } ?: return RecognitionResult.NotFound

        val images = track.optJSONObject("images")
        val cover = (images?.optString("coverarthq")?.takeIf { it.isNotBlank() }
            ?: images?.optString("coverart")?.takeIf { it.isNotBlank() })
            ?.replace(Regex("""/\d+x\d+cc\.jpg$"""), "/800x800cc.jpg")

        var album: String? = null
        var released: String? = null
        val sections = track.optJSONArray("sections")
        for (i in 0 until (sections?.length() ?: 0)) {
            val section = sections?.optJSONObject(i) ?: continue
            if (section.optString("type") != "SONG") continue
            val meta = section.optJSONArray("metadata") ?: continue
            for (j in 0 until meta.length()) {
                val m = meta.optJSONObject(j) ?: continue
                when (m.optString("title")) {
                    "Album" -> album = m.optString("text").takeIf { it.isNotBlank() }
                    "Released" -> released = m.optString("text").takeIf { it.isNotBlank() }
                }
            }
        }

        val hub = track.optJSONObject("hub")
        val appleMusic = track.optJSONObject("myshazam")?.optJSONObject("apple")?.optJSONArray("actions")?.firstUri()
            ?: hub?.optJSONArray("options")?.let { options ->
                (0 until options.length()).firstNotNullOfOrNull { options.optJSONObject(it)?.optJSONArray("actions")?.firstHttpUri() }
            }
        val spotify = hub?.optJSONArray("providers")?.let { providers ->
            (0 until providers.length())
                .mapNotNull { providers.optJSONObject(it) }
                .firstOrNull { it.optString("type") == "SPOTIFY" }
                ?.optJSONArray("actions")?.firstUri()
        }?.let { uri ->
            // "spotify:search:Song%20Artist" opens only in the app; the web link works everywhere.
            if (uri.startsWith("spotify:search:")) "https://open.spotify.com/search/" + uri.removePrefix("spotify:search:") else uri
        }

        return RecognitionResult.Found(
            Recognized(
                title = title,
                artist = track.optString("subtitle").ifBlank { "Unknown artist" },
                album = album,
                releaseDate = released,
                artworkUrl = cover,
                songLink = track.optString("url").takeIf { it.isNotBlank() },
                spotifyUrl = spotify,
                appleMusicUrl = appleMusic,
            ),
        )
    }

    private fun JSONArray.firstUri(): String? {
        for (i in 0 until length()) {
            val uri = optJSONObject(i)?.optString("uri")
            if (!uri.isNullOrBlank()) return uri
        }
        return null
    }

    private fun JSONArray.firstHttpUri(): String? {
        for (i in 0 until length()) {
            val uri = optJSONObject(i)?.optString("uri")
            if (!uri.isNullOrBlank() && uri.startsWith("http")) return uri
        }
        return null
    }
}

/**
 * Apple's free iTunes Search API (no key). Used to add a cover, album and link to songs
 * shared from other apps, which only come with a title and artist.
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
