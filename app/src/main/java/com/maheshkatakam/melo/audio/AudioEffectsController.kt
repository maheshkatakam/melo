package com.maheshkatakam.melo.audio

import com.maheshkatakam.melo.data.SettingsRepository
import com.maheshkatakam.melo.data.db.CustomMoodDao
import com.maheshkatakam.melo.data.db.CustomMoodEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Keeps track of the moods and feeds the active one's [SoundProfile] to Melo's sound
 * engine ([MoodAudioProcessor]), which the player runs all audio through.
 */
class AudioEffectsController(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val customDao: CustomMoodDao,
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

    init {
        scope.launch {
            combine(currentMood, preview) { mood, p -> p ?: mood.profile }.collect { processor.setProfile(it) }
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
}
