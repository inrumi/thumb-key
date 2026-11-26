package com.dessalines.thumbkey.autocorrect

import java.text.Normalizer

/**
 * Strips combining diacritical marks so accentless typing can still match
 * accented dictionary words (e.g. `cafe` -> `café`, `naive` -> `naïve`).
 */
object Accents {
    private val LATIN_FOLDS: Map<Char, Char> =
        hashMapOf(
            'ł' to 'l',
            'ø' to 'o',
            'đ' to 'd',
            'ı' to 'i',
            'ş' to 's',
            'ğ' to 'g',
            'ç' to 'c',
            'ñ' to 'n',
            'ß' to 's',
        )

    private val DIACRITIC_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun foldChar(ch: Char): Char {
        val lower = ch.lowercaseChar()
        LATIN_FOLDS[lower]?.let { return it }
        if (lower <= '\u007F') return lower
        val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
        for (i in 0 until decomposed.length) {
            val c = decomposed[i]
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                return LATIN_FOLDS[c] ?: c
            }
        }
        return lower
    }

    fun foldWord(word: CharSequence): String {
        val sb = StringBuilder(word.length)
        var i = 0
        while (i < word.length) {
            val ch = word[i]
            if (ch == 'ß' || ch == 'ẞ') {
                sb.append("ss")
            } else {
                sb.append(foldChar(ch))
            }
            i++
        }
        return DIACRITIC_REGEX.replace(sb.toString(), "")
    }

    fun isAccentVariant(
        typed: String,
        candidate: String,
    ): Boolean = typed != candidate && foldWord(typed) == foldWord(candidate)

    fun accentCount(
        typed: String,
        candidate: String,
    ): Int {
        if (typed.length != candidate.length) return 0
        var diffs = 0
        for (i in typed.indices) {
            val a = typed[i].lowercaseChar()
            val b = candidate[i].lowercaseChar()
            if (a != b && foldChar(a) == foldChar(b)) diffs++
        }
        return diffs
    }
}
