package com.maheshkatakam.melo.lyrics.ai

/**
 * Checks that a line is written in the script its language uses. When the AI loses track
 * during a loud instrumental it writes junk in random scripts ("Redmi 4", Cyrillic letters…);
 * those lines are easy to spot this way.
 */
object ScriptCheck {

    private val ranges: Map<String, List<IntRange>> = mapOf(
        "te" to listOf(0x0C00..0x0C7F),
        "kn" to listOf(0x0C80..0x0CFF),
        "ta" to listOf(0x0B80..0x0BFF),
        "ml" to listOf(0x0D00..0x0D7F),
        "hi" to listOf(0x0900..0x097F, 0xA8E0..0xA8FF),
        "mr" to listOf(0x0900..0x097F),
        "ne" to listOf(0x0900..0x097F),
        "sa" to listOf(0x0900..0x097F),
        "bn" to listOf(0x0980..0x09FF),
        "as" to listOf(0x0980..0x09FF),
        "gu" to listOf(0x0A80..0x0AFF),
        "pa" to listOf(0x0A00..0x0A7F),
        "ur" to listOf(0x0600..0x06FF, 0x0750..0x077F, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        "ar" to listOf(0x0600..0x06FF, 0x0750..0x077F, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
        "ko" to listOf(0xAC00..0xD7AF, 0x1100..0x11FF, 0x3130..0x318F),
    )

    /** True if [language] has its own script that Melo knows. */
    fun knows(language: String): Boolean = language in ranges

    /** Share of the letters in [text] that belong to [language]'s script (1.0 if unknown). */
    fun share(text: String, language: String): Float {
        val wanted = ranges[language] ?: return 1f
        var letters = 0
        var inScript = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp) && Character.getType(cp) != Character.NON_SPACING_MARK.toInt() &&
                Character.getType(cp) != Character.COMBINING_SPACING_MARK.toInt()
            ) {
                continue
            }
            letters++
            if (wanted.any { cp in it }) inScript++
        }
        return if (letters == 0) 0f else inScript.toFloat() / letters
    }
}
