package com.maheshkatakam.melo.audio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brightness3
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

@Immutable
data class Mood(
    val id: String,
    val name: String,
    /** One line shown under the mood. */
    val description: String,
    /** What this mood does to the sound, in plain words. */
    val details: String,
    val icon: ImageVector,
    val colors: List<Color>,
    val profile: SoundProfile,
    val custom: Boolean = false,
)

object Moods {
    val Normal = Mood(
        id = "normal", name = "Normal",
        description = "Untouched, exactly as the file sounds",
        details = "No processing at all. The audio goes to your headphones bit for bit.",
        icon = Icons.Rounded.GraphicEq,
        colors = listOf(Color(0xFF64748B), Color(0xFF334155)),
        profile = SoundProfile.FLAT,
    )
    val Happy = Mood(
        id = "happy", name = "Happy",
        description = "Bright and bouncy",
        details = "A lively bass bounce, sparkle on top so melodies and claps shine, and vocals a little forward.",
        icon = Icons.Rounded.SentimentVerySatisfied,
        colors = listOf(Color(0xFFFFB347), Color(0xFFFF6A3D)),
        profile = MoodProfiles.HAPPY,
    )
    val Party = Mood(
        id = "party", name = "Party",
        description = "Big kick, wide and loud",
        details = "Deep kick and sub you can feel, mud removed so it stays clean when loud, crisp highs, extra-wide " +
            "stereo with a touch of club room, and every song evened out so the energy never drops.",
        icon = Icons.Rounded.Celebration,
        colors = listOf(Color(0xFFFF4D8D), Color(0xFF8B5CF6)),
        profile = MoodProfiles.PARTY,
    )
    val Workout = Mood(
        id = "workout", name = "Workout",
        description = "Punchy and relentless",
        details = "Hard-hitting kick, snare and vocals that cut through gym noise, kept centred so it works with one " +
            "earbud, and strongly evened out so it stays intense from start to finish.",
        icon = Icons.Rounded.FitnessCenter,
        colors = listOf(Color(0xFFFF5F5F), Color(0xFFB91C1C)),
        profile = MoodProfiles.WORKOUT,
    )
    val Chill = Mood(
        id = "chill", name = "Chill",
        description = "Warm, smooth, laid back",
        details = "Round, warm bass, softened edges, a relaxed wide image and a little room so nothing pokes out.",
        icon = Icons.Rounded.Waves,
        colors = listOf(Color(0xFF38BDF8), Color(0xFF0E7490)),
        profile = MoodProfiles.CHILL,
    )
    val Romantic = Mood(
        id = "romantic", name = "Romantic",
        description = "Close, lush vocals",
        details = "The voice is the star: body and clarity on vocals, silky highs, gentle warmth and a soft hall around the singer.",
        icon = Icons.Rounded.Favorite,
        colors = listOf(Color(0xFFF472B6), Color(0xFFBE185D)),
        profile = MoodProfiles.ROMANTIC,
    )
    val Mellow = Mood(
        id = "mellow", name = "Mellow",
        description = "A soft blanket for low days",
        details = "Full, warm low end with harsh and bright parts tucked away, a little quieter, nothing sudden.",
        icon = Icons.Rounded.Umbrella,
        colors = listOf(Color(0xFF818CF8), Color(0xFF3730A3)),
        profile = MoodProfiles.MELLOW,
    )
    val Focus = Mood(
        id = "focus", name = "Focus",
        description = "Music stays in the background",
        details = "Vocals step back so the words don't pull your attention, no booming bass, a narrower image, and a steady, quieter level.",
        icon = Icons.Rounded.Psychology,
        colors = listOf(Color(0xFF34D399), Color(0xFF047857)),
        profile = MoodProfiles.FOCUS,
    )
    val Sleep = Mood(
        id = "sleep", name = "Sleep",
        description = "Dark, soft and even",
        details = "Highs rolled off, no sharp cymbals or consonants, loud moments pulled down so nothing startles you, quiet and distant.",
        icon = Icons.Rounded.Bedtime,
        colors = listOf(Color(0xFF6366F1), Color(0xFF1E1B4B)),
        profile = MoodProfiles.SLEEP,
    )
    val Spatial = Mood(
        id = "spatial", name = "Spatial",
        description = "Sound around you (headphones)",
        details = "Opens ordinary stereo into a wider, deeper stage with a light room. Best with headphones. " +
            "This is Melo's own effect, not Dolby Atmos.",
        icon = Icons.Rounded.SurroundSound,
        colors = listOf(Color(0xFF22D3EE), Color(0xFF7C3AED)),
        profile = MoodProfiles.SPATIAL,
    )

    val builtIn: List<Mood> = listOf(Normal, Happy, Party, Workout, Chill, Romantic, Mellow, Focus, Sleep, Spatial)

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
