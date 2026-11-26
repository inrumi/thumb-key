package com.dessalines.thumbkey.autocorrect

/**
 * Pure decision helper for optional smart punctuation spacing:
 * 1. Removing an accidental space right before `,`, `.`, `?`, `!`, `;`, `:`
 *    (respecting French spacing before `;`, `:`, `!`, `?`).
 * 2. Auto-inserting a space after punctuation while collapsing cleanly when
 *    typing ellipses (`...`), closing brackets (`).`), decimals (`3.14`),
 *    or pressing space manually right after auto-space.
 */
object SmartPunctuation {
    private val HUG_OR_SPACE_PUNCT = charArrayOf(',', '.', '?', '!', ';', ':')
    private val FRENCH_KEEP_SPACE_BEFORE = charArrayOf(';', ':', '!', '?')
    private val CLOSING_AFTER_PUNCT = charArrayOf(')', ']', '}', '"', '\'', '”', '’', '»')

    fun isTargetPunctuation(ch: Char): Boolean = ch in HUG_OR_SPACE_PUNCT

    fun shouldHugSpaceBefore(
        punct: Char,
        textBeforeCursor: CharSequence,
        primaryLanguage: String,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || punct !in HUG_OR_SPACE_PUNCT) return false
        if (primaryLanguage == "fr" && punct in FRENCH_KEEP_SPACE_BEFORE) return false
        val len = textBeforeCursor.length
        if (len < 2 || textBeforeCursor[len - 1] != ' ') return false
        return !textBeforeCursor[len - 2].isWhitespace()
    }

    /**
     * Whether a previously auto-inserted trailing space before cursor should be
     * deleted right before inserting [nextText] (e.g. `...`, `).`, `3.14`, manual space).
     */
    fun shouldCollapsePendingAutoSpaceBefore(
        nextText: String,
        textBeforeCursor: CharSequence,
        pendingAutoSpace: Boolean,
    ): CollapseAction {
        if (!pendingAutoSpace || textBeforeCursor.isEmpty()) return CollapseAction.NONE
        val len = textBeforeCursor.length
        if (textBeforeCursor[len - 1] != ' ') return CollapseAction.NONE
        if (nextText == " ") return CollapseAction.CONSUME_DUPLICATE_SPACE
        if (nextText.length != 1) return CollapseAction.NONE
        val nextCh = nextText[0]
        if (nextCh in HUG_OR_SPACE_PUNCT || nextCh in CLOSING_AFTER_PUNCT) {
            return CollapseAction.DELETE_PENDING_SPACE
        }
        if (nextCh.isDigit() && len >= 3) {
            val prevPunct = textBeforeCursor[len - 2]
            val beforePunct = textBeforeCursor[len - 3]
            if ((prevPunct == '.' || prevPunct == ',' || prevPunct == ':') && beforePunct.isDigit()) {
                return CollapseAction.DELETE_PENDING_SPACE
            }
        }
        return CollapseAction.NONE
    }

    fun shouldInsertAutoSpaceAfter(
        punct: Char,
        textBeforeCursorAfterPunct: CharSequence,
        textAfterCursor: CharSequence?,
        enabled: Boolean,
        isRestrictedField: Boolean,
    ): Boolean {
        if (!enabled || isRestrictedField || punct !in HUG_OR_SPACE_PUNCT) return false
        val nextCh = textAfterCursor?.firstOrNull()
        if (nextCh != null && (nextCh.isWhitespace() || nextCh in CLOSING_AFTER_PUNCT)) {
            return false
        }
        // Do not auto-space immediately after URL prefixes like `www.` or `http://example.`
        if (punct == '.') {
            val len = textBeforeCursorAfterPunct.length
            val start = (len - 32).coerceAtLeast(0)
            val tail = textBeforeCursorAfterPunct.subSequence(start, len).toString()
            if (tail.contains("://") || tail.contains("www.", ignoreCase = true) || tail.contains('@')) {
                return false
            }
        }
        return true
    }

    enum class CollapseAction {
        NONE,
        DELETE_PENDING_SPACE,
        CONSUME_DUPLICATE_SPACE,
    }
}
