package com.dessalines.thumbkey.autocorrect

import com.dessalines.thumbkey.utils.KeyboardLayout
import java.util.Locale

/**
 * Resolves each [KeyboardLayout] to the ordered ISO 639-1 language IDs used to
 * query bundled and imported dictionaries.
 *
 * Non-space-delimited, CJK, symbol-only, and mathematical layouts resolve to an
 * empty list so autocorrect cleanly steps aside without touching text.
 */
object LayoutLanguageResolver {
    private val NO_AUTOCORRECT_PREFIXES =
        listOf(
            "JA",
            "TH",
            "MY",
            "MATH",
            "Glagolitic",
            "TOKSitelen",
            "ENSymbols",
            "ENDoubleSymbols",
            "RUThumbKeySymbols",
            "BYThumbKeySymbols",
            "ENThumbKeySymbols",
            "IDThumbKeySymbols",
            "BGMessagEasePhoneticSymbols",
            "UKMessagEaseSymbols",
            "HEMessagEaseSymbols",
            "SLMessagEaseSymbols",
            "ITMessagEaseSymbols",
            "DEThumbKeySymbols",
            "ESThumbKeySymbols",
            "DEMessagEaseSymbols",
            "ENMessagEaseSymbols",
            "UKRUMessagEaseSymbols",
            "ENEOMessagEaseSymbols",
            "ENFRMessagEaseSymbols",
            "ENNOMessagEaseSymbols",
            "ENDAThumbKeySymbols",
            "ENMIThumbKeySymbols",
            "BGThumbKeySymbols",
            "HRThumbKeySymbols",
            "PLTypeSplitSymbols",
            "DEThumbKeySymNum",
            "DEThumbKey4x3SymNum",
            "RUMessagEaseSymbols",
        )

    private val OVERRIDES: Map<String, List<String>> =
        hashMapOf(
            "T9" to listOf("en"),
            "EuropeThumbKey" to listOf("en", "fr", "de", "es", "it", "pt", "nl"),
            "ScandinavianMessagEase" to listOf("sv", "nb", "da"),
            "CAThumbKey" to listOf("cr"), // Canadian Aboriginal Syllabics
            "DENordicMessagEase" to listOf("de", "da", "nb", "sv"),
            "SRLatnThumbKey" to listOf("sr"),
            "SRLatnEnDeThumbKey" to listOf("sr", "en", "de"),
            "FRENFrappeFluideV1" to listOf("fr", "en"),
            "FRENFrappeFluideV1ControlLeftSide" to listOf("fr", "en"),
            "FRENFrappeFluideV1LeftHanded" to listOf("fr", "en"),
            "FRENFrappeFluideV1LeftHandedControlLeftSide" to listOf("fr", "en"),
            "ENHyper" to listOf("en"),
            "ENHyperSpace" to listOf("en"),
            "EnTypeSplitProgramming" to listOf("en"),
            "TOKThumbKey" to listOf("tok"),
            "TOKThumbKeyLettersShift" to listOf("tok"),
        )

    private val SUFFIX_ANCHORS =
        listOf(
            "ThumbKey",
            "MessagEase",
            "MessageEase",
            "TypeSplit",
            "TwoHands",
            "Whale",
            "Marlin",
            "Minnow",
            "Hyper",
            "Hungram",
            "Arti",
            "FrappeFluide",
            "Columnar",
            "Dvorak",
            "Qwertease",
            "QwertyFour",
            "QBased",
            "Rsinoa",
            "MessageOwl",
        )

    // Longer codes first so compound tokens match unambiguously
    private val TOKEN_TO_ISO =
        listOf(
            "LVLTG" to "lv",
            "EN" to "en",
            "DE" to "de",
            "DA" to "da",
            "ES" to "es",
            "EU" to "eu",
            "FA" to "fa",
            "FI" to "fi",
            "FR" to "fr",
            "IT" to "it",
            "NL" to "nl",
            "PL" to "pl",
            "PT" to "pt",
            "RU" to "ru",
            "UK" to "uk",
            "HE" to "he",
            "BY" to "be",
            "NO" to "nb",
            "KA" to "ka",
            "ID" to "id",
            "SV" to "sv",
            "TR" to "tr",
            "HU" to "hu",
            "EO" to "eo",
            "SK" to "sk",
            "GR" to "el",
            "CZ" to "cs",
            "BG" to "bg",
            "HR" to "hr",
            "LV" to "lv",
            "LT" to "lt",
            "CA" to "ca",
            "AR" to "ar",
            "EE" to "et",
            "KZ" to "kk",
            "MI" to "mi",
            "SL" to "sl",
            "PH" to "tl",
            "RO" to "ro",
            "LA" to "la",
            "BR" to "br",
            "VN" to "vi",
            "SR" to "sr",
            "HI" to "hi",
            "KR" to "ko",
            "KN" to "kn",
            "SAH" to "sah",
            "BN" to "bn",
        )

    fun languagesFor(layout: KeyboardLayout): List<String> = languagesForName(layout.name)

    fun parseAdditionalLanguages(csv: String): List<String> =
        csv
            .split(',')
            .map { DictionaryManager.normalizeLang(it) }
            .filter { it.isNotEmpty() }
            .distinct()

    fun resolveLanguages(
        layout: KeyboardLayout,
        additionalLanguagesCsv: String,
    ): List<String> = resolveLanguagesForName(layout.name, additionalLanguagesCsv)

    fun resolveLanguagesForName(
        layoutName: String,
        additionalLanguagesCsv: String,
    ): List<String> {
        val primary = languagesForName(layoutName)
        if (primary.isEmpty()) return emptyList()
        val extra = parseAdditionalLanguages(additionalLanguagesCsv)
        if (extra.isEmpty()) return primary
        val out = LinkedHashSet<String>(primary.size + extra.size)
        out.addAll(primary)
        out.addAll(extra)
        return out.toList()
    }

    fun enabledLanguageCycle(
        layout: KeyboardLayout,
        additionalLanguagesCsv: String,
    ): List<String> = resolveLanguages(layout, additionalLanguagesCsv)

    fun enabledLanguageCycleForName(
        layoutName: String,
        additionalLanguagesCsv: String,
    ): List<String> = resolveLanguagesForName(layoutName, additionalLanguagesCsv)

    fun resolveActiveLanguage(
        layout: KeyboardLayout,
        additionalLanguagesCsv: String,
        activeLanguage: String,
    ): String? = resolveActiveLanguageForName(layout.name, additionalLanguagesCsv, activeLanguage)

    fun resolveActiveLanguageForName(
        layoutName: String,
        additionalLanguagesCsv: String,
        activeLanguage: String,
    ): String? {
        val cycle = enabledLanguageCycleForName(layoutName, additionalLanguagesCsv)
        if (cycle.isEmpty()) return null
        val norm = DictionaryManager.normalizeLang(activeLanguage)
        return if (norm in cycle) norm else cycle.first()
    }

    fun nextActiveLanguage(
        cycle: List<String>,
        currentActive: String?,
    ): String? {
        if (cycle.isEmpty()) return null
        if (cycle.size == 1) return cycle[0]
        val norm = DictionaryManager.normalizeLang(currentActive.orEmpty())
        val idx = cycle.indexOf(norm)
        return if (idx < 0) cycle[1 % cycle.size] else cycle[(idx + 1).mod(cycle.size)]
    }

    fun displayNameForSpacebar(langId: String): String {
        val norm = DictionaryManager.normalizeLang(langId)
        if (norm.isEmpty()) return ""
        val locale = Locale.forLanguageTag(norm)
        val native = locale.getDisplayLanguage(locale).trim()
        if (native.isEmpty() || native.equals(norm, ignoreCase = true)) {
            return norm.uppercase(Locale.ROOT)
        }
        return native.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    fun languagesForName(layoutName: String): List<String> {
        OVERRIDES[layoutName]?.let { return it }
        for (prefix in NO_AUTOCORRECT_PREFIXES) {
            if (layoutName.startsWith(prefix)) return emptyList()
        }

        val prefixPart = stripVariantSuffix(layoutName).uppercase()
        if (prefixPart.isEmpty()) return emptyList()

        val result = ArrayList<String>(3)
        var cursor = 0
        while (cursor < prefixPart.length) {
            var matched = false
            for ((token, iso) in TOKEN_TO_ISO) {
                if (prefixPart.startsWith(token, startIndex = cursor)) {
                    if (iso !in result) result.add(iso)
                    cursor += token.length
                    matched = true
                    break
                }
            }
            if (!matched) break
        }
        return result
    }

    private fun stripVariantSuffix(name: String): String {
        var cut = name.length
        for (anchor in SUFFIX_ANCHORS) {
            val idx = name.indexOf(anchor)
            if (idx in 1 until cut) cut = idx
        }
        return name.substring(0, cut)
    }
}
