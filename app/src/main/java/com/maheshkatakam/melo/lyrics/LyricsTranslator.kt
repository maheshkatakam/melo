package com.maheshkatakam.melo.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * On-device text translation (Google ML Kit). Each language needs a one-time ~30 MB pack;
 * after that, translation works with no internet at all.
 */
class LyricsTranslator(private val context: Context) {

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.35f).build(),
    )
    private val models = RemoteModelManager.getInstance()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /** Language packs offered in Settings (codes ML Kit can translate to English). */
    val available: List<String> = TranslateLanguage.getAllLanguages().filter { it != TranslateLanguage.ENGLISH }

    suspend fun refreshDownloaded() {
        _downloaded.value = runCatching {
            models.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        }.getOrDefault(emptySet())
    }

    /** BCP-47 code like "te", "hi", "hi-Latn" or "und". */
    suspend fun detectLanguage(text: String): String =
        runCatching { identifier.identifyLanguage(text.take(2000)).await() }.getOrDefault("und")

    /** The ML Kit language for a detected code, or null if ML Kit can't translate it. */
    fun translatable(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        if (code.contains("-Latn", ignoreCase = true)) return null // romanised text isn't supported
        return TranslateLanguage.fromLanguageTag(code.substringBefore('-'))
    }

    suspend fun isReady(language: String): Boolean {
        refreshDownloaded()
        return language in _downloaded.value
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun download(language: String): Boolean {
        _downloading.value = _downloading.value + language
        return try {
            models.download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().build()).await()
            true
        } catch (t: Throwable) {
            false
        } finally {
            _downloading.value = _downloading.value - language
            refreshDownloaded()
        }
    }

    suspend fun delete(language: String) {
        runCatching { models.deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await() }
        refreshDownloaded()
    }

    /** Translates line by line so timing stays attached to each line. */
    suspend fun translateToEnglish(lyrics: Lyrics, language: String): Lyrics {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(language)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
        try {
            val cache = HashMap<String, String>()
            val lines = lyrics.lines.map { line ->
                val text = line.text.trim()
                if (text.isEmpty()) line
                else LyricLine(line.timeMs, cache.getOrPut(text) { translator.translate(text).await() })
            }
            return Lyrics(lines)
        } finally {
            translator.close()
        }
    }
}
