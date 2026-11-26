package com.dessalines.thumbkey.autocorrect

/**
 * Guards tokens that should never be rewritten by autocorrect: URLs, emails,
 * handles, paths, code identifiers (`snake_case` / `camelCase`), and numbers
 * with units/ordinals (`1st`, `24px`, `10km`).
 */
object AddressGuard {
    private val NUMBER_SUFFIXES: Set<String> =
        hashSetOf(
            "st",
            "nd",
            "rd",
            "th",
            "am",
            "pm",
            "s",
            "k",
            "m",
            "g",
            "h",
            "d",
            "p",
            "x",
            "km",
            "kg",
            "cm",
            "mm",
            "ml",
            "mg",
            "gb",
            "mb",
            "kb",
            "tb",
            "hz",
            "fps",
            "mp",
            "px",
            "pt",
            "min",
            "hr",
            "hrs",
            "yr",
            "yrs",
            "mo",
            "wk",
            "ft",
            "in",
            "lb",
            "lbs",
            "oz",
            "ms",
            "sec",
            "secs",
            "mins",
            "kw",
        )

    private val COMMON_TLDS: Set<String> =
        hashSetOf(
            "com",
            "org",
            "net",
            "edu",
            "gov",
            "io",
            "dev",
            "app",
            "co",
            "uk",
            "de",
            "fr",
            "es",
            "it",
            "nl",
            "ca",
            "au",
            "jp",
            "ru",
            "br",
            "in",
            "me",
            "ai",
            "xyz",
            "info",
            "biz",
        )

    fun shouldSkipToken(token: String): Boolean {
        if (token.isEmpty()) return true
        if (token.contains('@') || token.contains('/') || token.contains('\\')) return true
        if (token.contains('_') || token.contains('#') || token.contains(':')) return true
        if (token.startsWith("http", ignoreCase = true) && token.contains('.')) return true
        if (token.startsWith("www.", ignoreCase = true)) return true

        val dotIdx = token.indexOf('.')
        if (dotIdx in 1 until token.length - 1) {
            val suffix = token.substring(dotIdx + 1).lowercase()
            if (suffix in COMMON_TLDS || suffix.all { it.isDigit() }) return true
            return true
        }

        var hasDigit = false
        var digitPrefixEnd = 0
        while (digitPrefixEnd < token.length && token[digitPrefixEnd].isDigit()) {
            hasDigit = true
            digitPrefixEnd++
        }
        if (hasDigit) {
            val rest = token.substring(digitPrefixEnd).lowercase()
            if (rest.isEmpty() || rest in NUMBER_SUFFIXES) return true
            return true
        }
        for (c in token) {
            if (c.isDigit()) return true
        }

        // camelCase or PascalCase identifier (e.g. `myVar`, `getItem`, `iPhone`)
        if (WordCase.isMixedInternalCase(token)) return true

        return false
    }
}
