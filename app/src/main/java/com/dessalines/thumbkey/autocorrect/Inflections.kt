package com.dessalines.thumbkey.autocorrect

/**
 * Recognizes valid regular English inflections (`walked`, `walks`, `walking`,
 * `boxes`, `happily`, `bigger`, `biggest`, `tried`, etc.) that compact word
 * lists sometimes omit, preventing false corrections over real inflected forms.
 */
object Inflections {
    private val IRREGULAR: Set<String> =
        hashSetOf(
            "arise",
            "awake",
            "bear",
            "beat",
            "become",
            "begin",
            "bend",
            "bet",
            "bind",
            "bite",
            "bleed",
            "blow",
            "break",
            "breed",
            "bring",
            "build",
            "burst",
            "buy",
            "cast",
            "catch",
            "choose",
            "cling",
            "come",
            "cost",
            "creep",
            "cut",
            "deal",
            "dig",
            "draw",
            "drink",
            "drive",
            "eat",
            "fall",
            "feed",
            "feel",
            "fight",
            "find",
            "flee",
            "fling",
            "fly",
            "forbid",
            "forget",
            "forgive",
            "freeze",
            "get",
            "give",
            "grind",
            "grow",
            "hang",
            "have",
            "hear",
            "hide",
            "hit",
            "hold",
            "hurt",
            "keep",
            "kneel",
            "know",
            "lay",
            "lead",
            "leave",
            "lend",
            "let",
            "lie",
            "light",
            "lose",
            "make",
            "mean",
            "meet",
            "mistake",
            "overcome",
            "pay",
            "put",
            "quit",
            "read",
            "ride",
            "ring",
            "rise",
            "run",
            "say",
            "see",
            "seek",
            "sell",
            "send",
            "set",
            "shake",
            "shed",
            "shine",
            "shoot",
            "shrink",
            "shut",
            "sing",
            "sink",
            "sit",
            "slay",
            "sleep",
            "slide",
            "sling",
            "slit",
            "speak",
            "speed",
            "spend",
            "spin",
            "spit",
            "split",
            "spread",
            "spring",
            "stand",
            "steal",
            "stick",
            "sting",
            "stink",
            "stride",
            "strike",
            "string",
            "strive",
            "swear",
            "sweep",
            "swim",
            "swing",
            "take",
            "teach",
            "tear",
            "tell",
            "think",
            "throw",
            "thrust",
            "tread",
            "understand",
            "undertake",
            "undo",
            "upset",
            "wake",
            "wear",
            "weave",
            "weep",
            "win",
            "wind",
            "withdraw",
            "wring",
            "write",
        )

    fun isKnownInflection(
        word: String,
        dictionary: WordSource,
    ): Boolean {
        val w = word.lowercase()
        if (w.length < 4) return false

        // Plural / 3sg present -s / -es / -ies
        if (w.endsWith("ies") && w.length >= 5) {
            val base = w.dropLast(3) + "y"
            if (dictionary.contains(base)) return true
        }
        if (w.endsWith("es") && w.length >= 4) {
            val base = w.dropLast(2)
            if (dictionary.contains(base)) return true
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length >= 4) {
            val base = w.dropLast(1)
            if (dictionary.contains(base)) return true
        }

        // Past -ed / -d / -ied
        if (w.endsWith("ied") && w.length >= 5) {
            val base = w.dropLast(3) + "y"
            if (dictionary.contains(base)) return true
        }
        if (w.endsWith("ed") && w.length >= 5) {
            val stem2 = w.dropLast(2)
            if (stem2.length >= 3 && stem2 !in IRREGULAR && dictionary.contains(stem2)) return true
            val stem1 = w.dropLast(1)
            if (stem1.length >= 3 && stem1 !in IRREGULAR && dictionary.contains(stem1)) return true
            if (stem2.length >= 4 && stem2.last() == stem2[stem2.length - 2]) {
                val undoubled = stem2.dropLast(1)
                if (undoubled !in IRREGULAR && dictionary.contains(undoubled)) return true
            }
        }

        // Participle -ing
        if (w.endsWith("ing") && w.length >= 5) {
            val stem = w.dropLast(3)
            if (dictionary.contains(stem)) return true
            if (dictionary.contains(stem + "e")) return true
            if (stem.length >= 3 && stem.last() == stem[stem.length - 2]) {
                if (dictionary.contains(stem.dropLast(1))) return true
            }
        }

        // Adverb -ly / Comparative -er / Superlative -est
        if (w.endsWith("ly") && w.length >= 5) {
            val stem = w.dropLast(2)
            if (dictionary.contains(stem)) return true
            if (w.endsWith("ily") && dictionary.contains(w.dropLast(3) + "y")) return true
        }
        if (w.endsWith("er") && w.length >= 5) {
            val stem = w.dropLast(2)
            if (dictionary.contains(stem) || dictionary.contains(stem + "e")) return true
        }
        if (w.endsWith("est") && w.length >= 6) {
            val stem = w.dropLast(3)
            if (dictionary.contains(stem) || dictionary.contains(stem + "e")) return true
        }

        return false
    }
}
