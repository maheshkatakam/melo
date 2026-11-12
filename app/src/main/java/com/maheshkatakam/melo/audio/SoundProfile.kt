package com.maheshkatakam.melo.audio

import java.util.Locale

/**
 * Everything that shapes the sound of a mood. Applied by Melo's own sound engine
 * ([MoodDsp]), so it sounds the same on every phone and every pair of headphones.
 */
data class SoundProfile(
    /** Gains in dB for the five tone bands (see [FREQUENCIES_HZ]), -12..+12. */
    val bands: List<Float>,
    /** Extra kick/bass punch around 60 Hz, 0..10 dB. */
    val bass: Float = 0f,
    /** Stereo width: 1 = as recorded, below 1 narrower, above 1 wider (0.5..1.6). */
    val width: Float = 1f,
    /** "Sound around you" effect, 0..1. Best with headphones. */
    val spatial: Float = 0f,
    /** Room ambience amount, 0..1. */
    val reverb: Float = 0f,
    /** Size of that room, 0 (small) .. 1 (hall). */
    val room: Float = 0.5f,
    /** Soft analogue-style saturation, 0..1. */
    val warmth: Float = 0f,
    /** Evens out quiet and loud parts, 0..1. */
    val levelling: Float = 0f,
    /** Overall loudness trim in dB, -8..+6. A limiter prevents distortion. */
    val gainDb: Float = 0f,
) {
    init {
        require(bands.size == BAND_COUNT)
    }

    val isNeutral: Boolean
        get() = bands.all { it == 0f } && bass == 0f && width == 1f && spatial == 0f &&
            reverb == 0f && warmth == 0f && levelling == 0f && gainDb == 0f

    /** Compact text form, stored for custom moods. */
    fun encode(): String = listOf(
        "v2",
        bands.joinToString(",") { f(it) },
        f(bass), f(width), f(spatial), f(reverb), f(room), f(warmth), f(levelling), f(gainDb),
    ).joinToString("|")

    companion object {
        const val BAND_COUNT = 5
        val FREQUENCIES_HZ = intArrayOf(90, 250, 1000, 3500, 9000)
        val BAND_LABELS = listOf("90 Hz", "250 Hz", "1 kHz", "3.5 kHz", "9 kHz")
        val BAND_NAMES = listOf("Bass", "Low mids", "Mids", "Presence", "Treble")
        const val MAX_DB = 12f
        val FLAT = SoundProfile(listOf(0f, 0f, 0f, 0f, 0f))

        private fun f(v: Float) = String.format(Locale.US, "%.2f", v)

        /**
         * Reads [encode]'s output. Also understands the first Melo format (five band gains
         * plus separate bass / surround / loudness numbers from the phone's built-in effects).
         */
        fun decode(text: String, legacyBass: Int = 0, legacySurround: Int = 0, legacyLoudness: Int = 0): SoundProfile {
            val parts = text.split('|')
            if (parts.size >= 10 && parts[0] == "v2") {
                val bands = parts[1].split(',').mapNotNull { it.toFloatOrNull() }
                val n = parts.drop(2).map { it.toFloatOrNull() ?: 0f }
                if (bands.size == BAND_COUNT) {
                    return SoundProfile(
                        bands = bands.map { it.coerceIn(-MAX_DB, MAX_DB) },
                        bass = n[0].coerceIn(0f, 10f),
                        width = n[1].coerceIn(0.5f, 1.6f),
                        spatial = n[2].coerceIn(0f, 1f),
                        reverb = n[3].coerceIn(0f, 1f),
                        room = n[4].coerceIn(0f, 1f),
                        warmth = n[5].coerceIn(0f, 1f),
                        levelling = n[6].coerceIn(0f, 1f),
                        gainDb = n[7].coerceIn(-8f, 6f),
                    )
                }
            }
            val old = text.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (old.size != BAND_COUNT) return FLAT
            return SoundProfile(
                bands = old.map { it.coerceIn(-MAX_DB, MAX_DB) },
                bass = (legacyBass / 1000f * 8f).coerceIn(0f, 10f),
                spatial = (legacySurround / 1000f).coerceIn(0f, 1f),
                gainDb = (legacyLoudness / 100f).coerceIn(0f, 6f),
            )
        }
    }
}

/**
 * The built-in mood sounds. Each one is tuned for how people actually listen in that mood,
 * not just "more bass" or "less treble".
 */
object MoodProfiles {
    /**
     * Party: music filling a room. Big kick and sub you can feel, the muddy low-mids pulled
     * out so it stays clean when loud, crisp hi-hats, extra-wide stereo, a touch of club
     * room, and every song levelled so the energy never drops between tracks.
     */
    val PARTY = SoundProfile(
        bands = listOf(4f, -2.5f, -0.5f, 1.5f, 3.5f),
        bass = 5f, width = 1.3f, spatial = 0.15f, reverb = 0.10f, room = 0.45f,
        warmth = 0.15f, levelling = 0.55f, gainDb = 2f,
    )

    /**
     * Happy: bright and bouncy. A lively bass bounce (not heavy), sparkle on top so
     * melodies and claps shine, vocals a little forward, slightly open stereo.
     */
    val HAPPY = SoundProfile(
        bands = listOf(2.5f, -1f, 0f, 2f, 3f),
        bass = 2.5f, width = 1.15f, levelling = 0.2f, gainDb = 1f,
    )

    /**
     * Workout: drive that cuts through gym noise and one-ear listening. Punchy kick,
     * hard-hitting snare and vocals, kept centred (not wide), strongly levelled so it
     * stays intense the whole way through, a little grit.
     */
    val WORKOUT = SoundProfile(
        bands = listOf(3f, -1.5f, 1f, 3f, 2f),
        bass = 5f, width = 1f, warmth = 0.25f, levelling = 0.7f, gainDb = 3f,
    )

    /**
     * Chill: laid back. Warm, round bass, softened edges (less presence and sizzle),
     * a relaxed wide image and a little room so nothing pokes out.
     */
    val CHILL = SoundProfile(
        bands = listOf(2.5f, 1f, 0f, -2f, -1.5f),
        bass = 1.5f, width = 1.2f, spatial = 0.2f, reverb = 0.12f, room = 0.55f,
        warmth = 0.3f, levelling = 0.2f,
    )

    /**
     * Romantic: close and lush. The voice is the star: body and clarity on vocals, silky
     * (not sharp) highs, gentle warmth, and a soft hall around the singer.
     */
    val ROMANTIC = SoundProfile(
        bands = listOf(1.5f, 1.5f, 1f, 1.5f, 0.5f),
        bass = 1f, width = 1.1f, spatial = 0.15f, reverb = 0.2f, room = 0.7f,
        warmth = 0.35f, levelling = 0.15f,
    )

    /**
     * Mellow: for low or tired days. Like a soft blanket: full warm low end, harsh and
     * bright parts tucked away, slightly quieter, nothing sudden.
     */
    val MELLOW = SoundProfile(
        bands = listOf(2f, 2f, 0f, -3f, -4f),
        bass = 1f, width = 0.95f, reverb = 0.08f, room = 0.5f,
        warmth = 0.4f, levelling = 0.35f, gainDb = -1f,
    )

    /**
     * Focus: music as a background. Vocals step back so words don't pull your attention,
     * no booming bass, narrower so it sits "in front" of you, and a steady, quieter level.
     */
    val FOCUS = SoundProfile(
        bands = listOf(-1.5f, -1f, -1.5f, -3.5f, -1f),
        width = 0.8f, levelling = 0.6f, gainDb = -2f,
    )

    /**
     * Sleep: dark, soft and even. Highs rolled right off, no sharp consonants or cymbals,
     * loud moments pulled down so nothing startles you, a distant roomy feel, quiet.
     */
    val SLEEP = SoundProfile(
        bands = listOf(0f, 1f, -1f, -5f, -9f),
        width = 0.85f, reverb = 0.12f, room = 0.8f,
        warmth = 0.2f, levelling = 0.8f, gainDb = -4f,
    )

    /**
     * Spatial: the "sound around you" mode for headphones. Opens ordinary stereo into a
     * wider, deeper stage with a light room. Not Dolby Atmos; an effect applied on the phone.
     */
    val SPATIAL = SoundProfile(
        bands = listOf(1f, -0.5f, 0f, 0.5f, 1.5f),
        bass = 1f, width = 1.3f, spatial = 0.8f, reverb = 0.08f, room = 0.5f,
    )

    val ALL = mapOf(
        "party" to PARTY, "happy" to HAPPY, "workout" to WORKOUT, "chill" to CHILL,
        "romantic" to ROMANTIC, "mellow" to MELLOW, "focus" to FOCUS, "sleep" to SLEEP,
        "spatial" to SPATIAL,
    )
}
