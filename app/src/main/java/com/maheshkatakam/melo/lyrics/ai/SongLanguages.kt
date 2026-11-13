package com.maheshkatakam.melo.lyrics.ai

/**
 * Languages the lyrics AI can listen for, as Whisper language codes.
 *
 * [prompt] is a short sentence in the language's own script. Whisper treats it as the text that
 * came before the song, which keeps it writing in the right script (without it, the small model
 * often writes Telugu or Tamil words in Hindi letters).
 */
data class SongLanguage(val code: String, val name: String, val prompt: String)

object SongLanguages {
    val all: List<SongLanguage> = listOf(
        SongLanguage("te", "Telugu", "ఈ పాట తెలుగులో ఉంది."),
        SongLanguage("hi", "Hindi", "यह गाना हिंदी में है।"),
        SongLanguage("ta", "Tamil", "இந்தப் பாடல் தமிழில் உள்ளது."),
        SongLanguage("kn", "Kannada", "ಈ ಹಾಡು ಕನ್ನಡದಲ್ಲಿದೆ."),
        SongLanguage("ml", "Malayalam", "ഈ ഗാനം മലയാളത്തിലാണ്."),
        SongLanguage("mr", "Marathi", "हे गाणे मराठीत आहे."),
        SongLanguage("bn", "Bengali", "এই গানটি বাংলায়।"),
        SongLanguage("gu", "Gujarati", "આ ગીત ગુજરાતીમાં છે."),
        SongLanguage("pa", "Punjabi", "ਇਹ ਗੀਤ ਪੰਜਾਬੀ ਵਿੱਚ ਹੈ।"),
        SongLanguage("ur", "Urdu", "یہ گانا اردو میں ہے۔"),
        SongLanguage("as", "Assamese", "এই গীতটো অসমীয়াত।"),
        SongLanguage("ne", "Nepali", "यो गीत नेपालीमा छ।"),
        SongLanguage("sa", "Sanskrit", "इदं गीतं संस्कृते अस्ति।"),
        SongLanguage("en", "English", ""),
        SongLanguage("es", "Spanish", ""),
        SongLanguage("ko", "Korean", ""),
        SongLanguage("ar", "Arabic", ""),
    )

    private val byCode = all.associateBy { it.code }

    /**
     * Used when you haven't picked your languages. Sanskrit is left out on purpose: in tests the
     * AI often mistook Hindi singing for Sanskrit.
     */
    val defaultCodes: Set<String> = setOf("te", "hi", "ta", "kn", "ml", "mr", "bn", "gu", "pa", "ur", "en")

    fun get(code: String?): SongLanguage? = code?.let { byCode[it.substringBefore('-')] }

    fun name(code: String?): String = get(code)?.name ?: code.orEmpty()
}
