package com.maheshkatakam.melo.audio

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.db.CustomMoodDao
import com.maheshkatakam.melo.data.db.CustomMoodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ln

data class EffectSupport(
    val equalizer: Boolean = true,
    val bass: Boolean = true,
    val virtualizer: Boolean = true,
    val loudness: Boolean = true,
)

/**
 * Applies the active mood's sound profile to the player's audio session using Android's
 * built-in audio effects (equalizer, bass boost, virtualizer, loudness enhancer).
 */
class AudioEffectsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val customDao: CustomMoodDao,
) {
    val moods: StateFlow<List<Mood>> = customDao.observeAll()
        .map { list -> Moods.builtIn + list.map { it.toMood() } }
        .stateIn(scope, SharingStarted.Eagerly, Moods.builtIn)

    val currentMood: StateFlow<Mood> = combine(moods, settings.settings) { all, s ->
        all.firstOrNull { it.id == s.moodId } ?: Moods.Normal
    }.stateIn(scope, SharingStarted.Eagerly, Moods.Normal)

    /** While editing in the Sound screen we preview a profile without saving it. */
    private val preview = MutableStateFlow<SoundProfile?>(null)

    private val _support = MutableStateFlow(EffectSupport())
    val support: StateFlow<EffectSupport> = _support.asStateFlow()

    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    init {
        scope.launch(Dispatchers.Main) {
            combine(currentMood, preview) { mood, p -> p ?: mood.profile }.collect { apply(it) }
        }
    }

    fun setMood(id: String) {
        preview.value = null
        settings.setMood(id)
    }

    fun preview(profile: SoundProfile?) {
        preview.value = profile
    }

    fun saveCustomMood(name: String, iconKey: String, profile: SoundProfile, existingId: String? = null): String {
        val id = existingId ?: "custom_${UUID.randomUUID().toString().take(8)}"
        scope.launch {
            customDao.upsert(
                CustomMoodEntity(
                    id = id,
                    name = name.trim().ifEmpty { "My mood" },
                    iconKey = iconKey,
                    bands = SoundProfile.encodeBands(profile.bands),
                    bass = profile.bass,
                    virtualizer = profile.virtualizer,
                    loudness = profile.loudness,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            preview.value = null
            settings.setMood(id)
        }
        return id
    }

    fun deleteCustomMood(id: String) {
        scope.launch {
            customDao.delete(id)
            if (settings.settings.value.moodId == id) settings.setMood(Moods.Normal.id)
        }
    }

    /** Must be called on the main thread by the playback service. */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == sessionId && equalizer != null) return
        releaseEffects()
        if (audioSessionId == 0) return
        sessionId = audioSessionId
        var support = EffectSupport()
        equalizer = runCatching { Equalizer(PRIORITY, audioSessionId) }
            .onFailure { Log.w(TAG, "Equalizer unavailable", it) }.getOrNull()
        bassBoost = runCatching { BassBoost(PRIORITY, audioSessionId) }
            .onFailure { Log.w(TAG, "BassBoost unavailable", it) }.getOrNull()
        virtualizer = runCatching { Virtualizer(PRIORITY, audioSessionId) }
            .onFailure { Log.w(TAG, "Virtualizer unavailable", it) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(audioSessionId) }
            .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable", it) }.getOrNull()
        support = support.copy(
            equalizer = equalizer != null,
            bass = bassBoost?.strengthSupported == true,
            virtualizer = virtualizer?.strengthSupported == true,
            loudness = loudness != null,
        )
        _support.value = support
        apply(preview.value ?: currentMood.value.profile)
        // Let system-wide equalizer apps know about our session too.
        runCatching {
            context.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
            )
        }
    }

    fun release() {
        if (sessionId != 0) {
            runCatching {
                context.sendBroadcast(
                    Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                        .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName),
                )
            }
        }
        releaseEffects()
        sessionId = 0
    }

    private fun releaseEffects() {
        listOf(equalizer, bassBoost, virtualizer, loudness).forEach { effect ->
            runCatching { effect?.release() }
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudness = null
    }

    private fun apply(profile: SoundProfile) {
        val flat = profile == SoundProfile.FLAT
        equalizer?.let { eq ->
            runCatching {
                val range = eq.bandLevelRange // millibels [min, max]
                val bands = eq.numberOfBands.toInt()
                for (band in 0 until bands) {
                    val centerHz = eq.getCenterFreq(band.toShort()) / 1000f
                    val db = gainAt(profile.bands, centerHz)
                    val mb = (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(band.toShort(), mb.toShort())
                }
                eq.setEnabled(!flat)
            }.onFailure { Log.w(TAG, "Equalizer apply failed", it) }
        }
        bassBoost?.let { b ->
            runCatching {
                if (b.strengthSupported) b.setStrength(profile.bass.coerceIn(0, 1000).toShort())
                b.setEnabled(profile.bass > 0)
            }
        }
        virtualizer?.let { v ->
            runCatching {
                if (v.strengthSupported) v.setStrength(profile.virtualizer.coerceIn(0, 1000).toShort())
                v.setEnabled(profile.virtualizer > 0)
            }
        }
        loudness?.let { l ->
            runCatching {
                l.setTargetGain(profile.loudness.coerceIn(0, 1500))
                l.setEnabled(profile.loudness > 0)
            }
        }
    }

    /** Interpolates the 5-point curve in log-frequency space. */
    private fun gainAt(bands: List<Float>, hz: Float): Float {
        val freqs = SoundProfile.FREQUENCIES_HZ
        if (hz <= freqs.first()) return bands.first()
        if (hz >= freqs.last()) return bands.last()
        for (i in 0 until freqs.size - 1) {
            val lo = freqs[i].toFloat()
            val hi = freqs[i + 1].toFloat()
            if (hz in lo..hi) {
                val t = (ln(hz) - ln(lo)) / (ln(hi) - ln(lo))
                return bands[i] + (bands[i + 1] - bands[i]) * t
            }
        }
        return 0f
    }

    private fun CustomMoodEntity.toMood() = Mood(
        id = id,
        name = name,
        description = "Your custom sound",
        icon = Moods.customIcons[iconKey] ?: Moods.customIcons.values.first(),
        colors = Moods.paletteFor(id),
        profile = SoundProfile(SoundProfile.decodeBands(bands), bass, virtualizer, loudness),
        custom = true,
    )

    companion object {
        private const val TAG = "MeloEffects"
        private const val PRIORITY = 1000
    }
}
