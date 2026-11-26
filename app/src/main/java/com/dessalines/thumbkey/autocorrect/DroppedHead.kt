package com.dessalines.thumbkey.autocorrect

/**
 * Guards against rewriting an unfamiliar word when a valid word simply lost its
 * first character (e.g. `nd` / `he` / `ll`), or when a short prefix/clip might
 * be intentional rather than a multi-edit typo.
 */
object DroppedHead {
    fun hasKnownSuffixHead(
        word: String,
        dictionary: WordSource,
    ): Boolean {
        if (word.length < 3 || word.length > 7) return false
        for (c in 'a'..'z') {
            val probe = c + word
            if (dictionary.frequencyOf(probe) >= MIN_CORPUS_HEAD_FREQ) {
                return true
            }
        }
        return false
    }

    fun shouldGuardShortSlip(
        typed: String,
        dictionary: WordSource,
    ): Boolean = typed.length <= 3 && hasKnownSuffixHead(typed, dictionary)

    private const val MIN_CORPUS_HEAD_FREQ = 500
}
