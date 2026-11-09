package com.maheshkatakam.melo.audio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Brightness3
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A sound profile. Band gains are in dB at five reference frequencies; Melo maps them
 * onto whatever bands the phone's equalizer actually has.
 */
@Immutable
data class SoundProfile(
    val bands: List<Float>,
    /** 0..1000 */
    val bass: Int = 0,
    /** 0..1000, a wider "surround" feel */
    val virtualizer: Int = 0,
    /** 0..1000 millibels of extra loudness */
    val loudness: Int = 0,
) {
    init {
        require(bands.size == BAND_COUNT)
    }

    companion object {
        const val BAND_COUNT = 5
        val FREQUENCIES_HZ = intArrayOf(60, 230, 910, 3600, 14000)
        val BAND_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
        val BAND_NAMES = listOf("Deep bass", "Bass", "Mids", "Presence", "Treble")
        const val MAX_DB = 12f
        val FLAT = SoundProfile(listOf(0f, 0f, 0f, 0f, 0f))

        fun encodeBands(bands: List<Float>) = bands.joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
        fun decodeBands(text: String): List<Float> =
            text.split(',').mapNotNull { it.trim().toFloatOrNull() }.let { if (it.size == BAND_COUNT) it else FLAT.bands }
    }
}

@Immutable
data class Mood(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val profile: SoundProfile,
    val custom: Boolean = false,
)

object Moods {
    val Normal = Mood(
        id = "normal", name = "Normal", description = "As the artist mixed it",
        icon = Icons.Rounded.GraphicEq,
        colors = listOf(Color(0xFF64748B), Color(0xFF334155)),
        profile = SoundProfile.FLAT,
    )
    val Happy = Mood(
        id = "happy", name = "Happy", description = "Punchy bass, bright and lively",
        icon = Icons.Rounded.SentimentVerySatisfied,
        colors = listOf(Color(0xFFFFB347), Color(0xFFFF6A3D)),
        profile = SoundProfile(listOf(6f, 3.5f, 0f, 2f, 3f), bass = 600, virtualizer = 250, loudness = 250),
    )
    val Party = Mood(
        id = "party", name = "Party", description = "Heavy bass, wide and loud",
        icon = Icons.Rounded.Celebration,
        colors = listOf(Color(0xFFFF4D8D), Color(0xFF8B5CF6)),
        profile = SoundProfile(listOf(8f, 5f, -1f, 2f, 4f), bass = 900, virtualizer = 600, loudness = 500),
    )
    val Workout = Mood(
        id = "workout", name = "Workout", description = "Driving low end and energy",
        icon = Icons.Rounded.FitnessCenter,
        colors = listOf(Color(0xFFFF5F5F), Color(0xFFB91C1C)),
        profile = SoundProfile(listOf(7f, 4f, 0f, 3f, 3.5f), bass = 800, virtualizer = 200, loudness = 700),
    )
    val Chill = Mood(
        id = "chill", name = "Chill", description = "Warm, smooth and relaxed",
        icon = Icons.Rounded.Waves,
        colors = listOf(Color(0xFF38BDF8), Color(0xFF0E7490)),
        profile = SoundProfile(listOf(3f, 1.5f, 0f, -1f, 1f), bass = 300, virtualizer = 400),
    )
    val Romantic = Mood(
        id = "romantic", name = "Romantic", description = "Soft warmth, intimate vocals",
        icon = Icons.Rounded.Favorite,
        colors = listOf(Color(0xFFF472B6), Color(0xFFBE185D)),
        profile = SoundProfile(listOf(3f, 2f, 1.5f, 1f, 1.5f), bass = 250, virtualizer = 300),
    )
    val Mellow = Mood(
        id = "mellow", name = "Mellow", description = "Gentle and cosy for low days",
        icon = Icons.Rounded.Umbrella,
        colors = listOf(Color(0xFF818CF8), Color(0xFF3730A3)),
        profile = SoundProfile(listOf(2f, 2f, 1f, -2f, -3f), bass = 200, virtualizer = 200),
    )
    val Focus = Mood(
        id = "focus", name = "Focus", description = "Clear mids, nothing distracting",
        icon = Icons.Rounded.Psychology,
        colors = listOf(Color(0xFF34D399), Color(0xFF047857)),
        profile = SoundProfile(listOf(0f, -1f, 2f, 2f, 0.5f)),
    )
    val Sleep = Mood(
        id = "sleep", name = "Sleep", description = "Dark and soft, no harsh highs",
        icon = Icons.Rounded.Bedtime,
        colors = listOf(Color(0xFF6366F1), Color(0xFF1E1B4B)),
        profile = SoundProfile(listOf(1.5f, 1f, 0f, -3f, -7f), bass = 150),
    )

    val builtIn: List<Mood> = listOf(Normal, Happy, Party, Workout, Chill, Romantic, Mellow, Focus, Sleep)

    /** Icons a custom mood can use. */
    val customIcons: Map<String, ImageVector> = linkedMapOf(
        "star" to Icons.Rounded.Star,
        "fire" to Icons.Rounded.Whatshot,
        "spa" to Icons.Rounded.Spa,
        "night" to Icons.Rounded.Brightness3,
        "headphones" to Icons.Rounded.Headphones,
        "heart" to Icons.Rounded.Favorite,
        "waves" to Icons.Rounded.Waves,
        "party" to Icons.Rounded.Celebration,
    )

    private val customPalettes = listOf(
        listOf(Color(0xFFFACC15), Color(0xFFCA8A04)),
        listOf(Color(0xFF2DD4BF), Color(0xFF0F766E)),
        listOf(Color(0xFFA78BFA), Color(0xFF6D28D9)),
        listOf(Color(0xFFFB923C), Color(0xFFC2410C)),
        listOf(Color(0xFF60A5FA), Color(0xFF1D4ED8)),
    )

    fun paletteFor(id: String): List<Color> = customPalettes[(id.hashCode() and 0x7fffffff) % customPalettes.size]
}
