package com.maheshkatakam.melo.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import android.util.Log
import com.maheshkatakam.melo.data.MusicRepository
import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.db.CustomMoodDao
import com.maheshkatakam.melo.data.db.CustomMoodEntity
import com.maheshkatakam.melo.quality.QualityChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Treble-rebuild settings for the song that is playing. */
data class RestoreInfo(val songId: Long, val cutoffHz: Int, val gainDb: Float)

/**
 * Decides what Melo's sound engine should be doing right now (mood, 3D, treble rebuild) and
 * tells the player's [MoodAudioProcessor].
 */
class AudioEffectsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val customDao: CustomMoodDao,
    private val quality: QualityChecker,
    private val music: MusicRepository,
) {
    /** Installed into the player by the playback service. */
    val processor = MoodAudioProcessor()

    val moods: StateFlow<List<Mood>> = customDao.observeAll()
        .map { list -> Moods.builtIn + list.map { it.toMood() } }
        .stateIn(scope, SharingStarted.Eagerly, Moods.builtIn)

    val currentMood: StateFlow<Mood> = combine(moods, settings.settings) { all, s ->
        all.firstOrNull { it.id == s.moodId } ?: Moods.Normal
    }.stateIn(scope, SharingStarted.Eagerly, Moods.Normal)

    /** While editing in the Sound screen we preview a profile without saving it. */
    private val preview = MutableStateFlow<SoundProfile?>(null)

    private val _headphones = MutableStateFlow(false)
    /** True while headphones or earbuds are connected. 3D sound only makes sense on those. */
    val headphones: StateFlow<Boolean> = _headphones.asStateFlow()

    private val _restore = MutableStateFlow<RestoreInfo?>(null)
    /** Set when the current song is missing treble that Melo is rebuilding. */
    val restore: StateFlow<RestoreInfo?> = _restore.asStateFlow()

    /** Whether this phone has its own spatial audio that Melo can hand 5.1 sound to. */
    val phoneSpatialSupported: Boolean = runCatching {
        Build.VERSION.SDK_INT >= 32 &&
            context.getSystemService(AudioManager::class.java).spatializer.immersiveAudioLevel !=
            Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
    }.getOrDefault(false)

    /** True when stereo should leave Melo as 5.1 for the phone's spatial audio. Changing it restarts the audio output. */
    val surroundOutput: StateFlow<Boolean> = settings.settings
        .map { it.immersive && it.systemSpatial && phoneSpatialSupported }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var analysisJob: Job? = null
    private var currentSongId: Long? = null

    init {
        runCatching {
            processor.hrtf = HrtfSet.parse(context.assets.open("hrtf/kemar.bin").use { it.readBytes() })
        }.onFailure { Log.w("MeloSound", "3D sound data missing", it) }

        scope.launch { surroundOutput.collect { processor.surroundOut = it } }
        scope.launch {
            combine(currentMood, preview, settings.settings, _headphones, _restore) { mood, p, s, phones, restore ->
                val phoneDoesIt = s.systemSpatial && phoneSpatialSupported
                SoundPipeline.Config(
                    profile = p ?: mood.profile,
                    immersive = s.immersive && (phones || phoneDoesIt),
                    strength = s.immersiveStrength,
                    restoreCutoffHz = restore?.cutoffHz?.takeIf { s.restoreTreble },
                    restoreGainDb = restore?.gainDb?.takeIf { s.restoreTreble },
                )
            }.collect { processor.setConfig(it) }
        }
    }

    fun setMood(id: String) {
        preview.value = null
        settings.setMood(id)
    }

    fun preview(profile: SoundProfile?) {
        preview.value = profile
    }

    fun setHeadphones(connected: Boolean) {
        _headphones.value = connected
    }

    /** Looks at what is connected right now. */
    fun refreshHeadphones() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return
        _headphones.value = outputs.any { it.type in HEADPHONE_TYPES }
    }

    /** Called by the player when a new song starts: finds out whether it is missing treble. */
    fun onSongChanged(songId: Long?) {
        if (songId == currentSongId) return
        currentSongId = songId
        analysisJob?.cancel()
        _restore.value = null
        val song = songId?.let { music.song(it) } ?: return
        analysisJob = scope.launch {
            val real = runCatching { quality.analyze(song).real }.getOrNull() ?: return@launch
            val gain = real.restoreGainDb ?: return@launch
            if (currentSongId == songId) _restore.value = RestoreInfo(songId, real.cutoffHz, gain)
        }
    }

    fun saveCustomMood(name: String, iconKey: String, profile: SoundProfile, existingId: String? = null): String {
        val id = existingId ?: "custom_${UUID.randomUUID().toString().take(8)}"
        scope.launch {
            customDao.upsert(
                CustomMoodEntity(
                    id = id,
                    name = name.trim().ifEmpty { "My mood" },
                    iconKey = iconKey,
                    bands = profile.encode(),
                    bass = 0,
                    virtualizer = 0,
                    loudness = 0,
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

    private fun CustomMoodEntity.toMood() = Mood(
        id = id,
        name = name,
        description = "Your custom sound",
        details = "A sound you tuned yourself.",
        icon = Moods.customIcons[iconKey] ?: Moods.customIcons.values.first(),
        colors = Moods.paletteFor(id),
        profile = SoundProfile.decode(bands, bass, virtualizer, loudness),
        custom = true,
    )

    companion object {
        private val HEADPHONE_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
    }
}
