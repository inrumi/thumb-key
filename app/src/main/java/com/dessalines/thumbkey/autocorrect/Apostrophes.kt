package com.dessalines.thumbkey.autocorrect

/**
 * Unambiguous apostrophe contraction repairs (e.g. `dont` -> `don't`,
 * `im` -> `I'm`), keeping ambiguous real words (`were`, `well`, `hell`, `ill`,
 * `id`, `its`, `wont`) untouched unless the user explicitly declared an
 * apostrophe.
 */
object Apostrophes {
    private val CONTRACTIONS: Map<String, String> =
        hashMapOf(
            "im" to "I'm",
            "ive" to "I've",
            "imma" to "I'mma",
            "youre" to "you're",
            "youve" to "you've",
            "youll" to "you'll",
            "youd" to "you'd",
            "hes" to "he's",
            "shes" to "she's",
            "thats" to "that's",
            "thatll" to "that'll",
            "thatd" to "that'd",
            "whats" to "what's",
            "whatll" to "what'll",
            "whatre" to "what're",
            "whatve" to "what've",
            "whos" to "who's",
            "wholl" to "who'll",
            "whod" to "who'd",
            "whove" to "who've",
            "whore" to "who're",
            "wheres" to "where's",
            "whered" to "where'd",
            "whereve" to "where've",
            "whens" to "when's",
            "whys" to "why's",
            "hows" to "how's",
            "heres" to "here's",
            "theres" to "there's",
            "therell" to "there'll",
            "thered" to "there'd",
            "theyre" to "they're",
            "theyve" to "they've",
            "theyll" to "they'll",
            "theyd" to "they'd",
            "weve" to "we've",
            "aint" to "ain't",
            "arent" to "aren't",
            "cant" to "can't",
            "couldnt" to "couldn't",
            "couldve" to "could've",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "dont" to "don't",
            "hadnt" to "hadn't",
            "hasnt" to "hasn't",
            "havent" to "haven't",
            "isnt" to "isn't",
            "mightnt" to "mightn't",
            "mightve" to "might've",
            "mustnt" to "mustn't",
            "mustve" to "must've",
            "neednt" to "needn't",
            "shant" to "shan't",
            "shouldnt" to "shouldn't",
            "shouldve" to "should've",
            "wasnt" to "wasn't",
            "werent" to "weren't",
            "wouldnt" to "wouldn't",
            "wouldve" to "would've",
            "maam" to "ma'am",
            "oclock" to "o'clock",
            "yall" to "y'all",
            "tis" to "'tis",
            "twas" to "'twas",
            "em" to "'em",
            "idve" to "I'd've",
            "youdve" to "you'd've",
            "itll" to "it'll",
            "itd" to "it'd",
        )
    // Ambiguous standalone words (`its`, `id`, `ill`, `well`, `were`, `hell`, `shell`,
    // `wed`, `shed`, `hed`, `lets`, `wont`) stay in DECLARED so bare words are preserved.

    // Extra common French / Italian / Catalan fused elisions when those languages are active
    private val FRENCH_ELISIONS: Map<String, String> =
        hashMapOf(
            "cest" to "c'est",
            "cetait" to "c'était",
            "jai" to "j'ai",
            "jarrive" to "j'arrive",
            "jeune" to "jeune", // skip if already right; only fused forms below
            "jy" to "j'y",
            "jen" to "j'en",
            "jusqua" to "jusqu'à",
            "jusquau" to "jusqu'au",
            "aujourdhui" to "aujourd'hui",
            "quelquun" to "quelqu'un",
            "dun" to "d'un",
            "dune" to "d'une",
            "daccord" to "d'accord",
            "quil" to "qu'il",
            "quelle" to "quelle",
            "quon" to "qu'on",
            "naime" to "n'aime",
            "nest" to "n'est",
            "netait" to "n'était",
            "nai" to "n'ai",
            "sil" to "s'il",
            "sils" to "s'ils",
        ).apply {
            remove("jeune")
            remove("quelle")
        }

    private val DECLARED: Map<String, String> =
        hashMapOf(
            "id" to "I'd",
            "ill" to "I'll",
            "hed" to "he'd",
            "hell" to "he'll",
            "shed" to "she'd",
            "shell" to "she'll",
            "wed" to "we'd",
            "well" to "we'll",
            "were" to "we're",
            "wont" to "won't",
            "its" to "it's",
            "lets" to "let's",
        )

    private fun tableFor(languageId: String): Map<String, String>? =
        when (languageId.substringBefore('-').substringBefore('_')) {
            "en" -> CONTRACTIONS
            "fr" -> FRENCH_ELISIONS
            else -> null
        }

    fun fix(
        word: String,
        languageId: String = "en",
    ): String? {
        val table = tableFor(languageId) ?: return null
        return fixFromTable(word, table)
    }

    fun fixExplicit(word: String): String? = fixFromTable(word, DECLARED) ?: fixFromTable(word, CONTRACTIONS)

    fun isContractionRepairTarget(word: String): Boolean =
        CONTRACTIONS.values.any { it.equals(word, ignoreCase = true) } ||
            DECLARED.values.any { it.equals(word, ignoreCase = true) }

    private fun fixFromTable(
        word: String,
        table: Map<String, String>,
    ): String? {
        val fixed = table[word.lowercase()] ?: return null
        val shouted =
            word.codePointCount(0, word.length) > 1 &&
                WordCase.lettersAllUpper(word)
        if (shouted && '.' in fixed) return null
        val result =
            when {
                shouted -> fixed.uppercase()
                WordCase.startsUpperCase(word) -> WordCase.capitalizeFirst(fixed)
                else -> fixed
            }
        return if (result == word) null else result
    }
}
