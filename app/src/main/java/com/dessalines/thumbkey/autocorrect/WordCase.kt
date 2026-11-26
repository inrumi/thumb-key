package com.dessalines.thumbkey.autocorrect

/**
 * Preserves capitalization patterns when replacing [typed] with [replacement],
 * while keeping fixed lexical capitals (like `I'm` or `NASA`) intact.
 */
object WordCase {
    fun lettersAllUpper(word: CharSequence): Boolean {
        var letters = 0
        for (i in 0 until word.length) {
            val c = word[i]
            if (c.isLetter()) {
                letters++
                if (!c.isUpperCase()) return false
            }
        }
        return letters > 0
    }

    fun startsUpperCase(word: CharSequence): Boolean {
        for (i in 0 until word.length) {
            val c = word[i]
            if (c.isLetter()) return c.isUpperCase()
        }
        return false
    }

    fun capitalizeFirst(word: String): String {
        if (word.isEmpty()) return word
        for (i in word.indices) {
            if (word[i].isLetter()) {
                val up = word[i].uppercaseChar()
                if (up == word[i]) return word
                return word.substring(0, i) + up + word.substring(i + 1)
            }
        }
        return word
    }

    fun isMixedInternalCase(word: CharSequence): Boolean {
        var seenFirstLetter = false
        var seenLowerAfterFirst = false
        for (i in 0 until word.length) {
            val c = word[i]
            if (!c.isLetter()) continue
            if (!seenFirstLetter) {
                seenFirstLetter = true
            } else if (c.isLowerCase()) {
                seenLowerAfterFirst = true
            } else if (c.isUpperCase() && seenLowerAfterFirst) {
                return true
            }
        }
        return false
    }

    fun applyCase(
        typed: String,
        candidate: String,
        atSentenceStart: Boolean = false,
    ): String {
        if (candidate.isEmpty()) return candidate
        if (lettersAllUpper(typed) && typed.count { it.isLetter() } >= 2) {
            return candidate.uppercase()
        }
        if (startsUpperCase(typed) || atSentenceStart) {
            return if (startsUpperCase(candidate)) candidate else capitalizeFirst(candidate)
        }
        return candidate
    }
}
