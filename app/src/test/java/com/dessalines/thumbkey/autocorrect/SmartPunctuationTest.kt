package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPunctuationTest {
    @Test
    fun huggingSpaceBeforePunctuationRespectsFrenchSpacingAndLineStarts() {
        assertTrue(SmartPunctuation.shouldHugSpaceBefore(',', "hello ", "en", enabled = true))
        assertTrue(SmartPunctuation.shouldHugSpaceBefore('!', "hello ", "en", enabled = true))
        assertFalse(SmartPunctuation.shouldHugSpaceBefore(',', " ", "en", enabled = true))
        assertFalse(SmartPunctuation.shouldHugSpaceBefore(',', "hello  ", "en", enabled = true))
        assertFalse(SmartPunctuation.shouldHugSpaceBefore(',', "hello ", "en", enabled = false))

        // French preserves space before ; : ! ? while hugging , and .
        assertFalse(SmartPunctuation.shouldHugSpaceBefore('?', "bonjour ", "fr", enabled = true))
        assertFalse(SmartPunctuation.shouldHugSpaceBefore(':', "bonjour ", "fr", enabled = true))
        assertTrue(SmartPunctuation.shouldHugSpaceBefore(',', "bonjour ", "fr", enabled = true))
        assertTrue(SmartPunctuation.shouldHugSpaceBefore('.', "bonjour ", "fr", enabled = true))
    }

    @Test
    fun pendingAutoSpaceCollapsesForEllipsisDecimalsBracketsAndManualSpace() {
        assertEquals(
            SmartPunctuation.CollapseAction.DELETE_PENDING_SPACE,
            SmartPunctuation.shouldCollapsePendingAutoSpaceBefore(".", "wait. ", pendingAutoSpace = true),
        )
        assertEquals(
            SmartPunctuation.CollapseAction.DELETE_PENDING_SPACE,
            SmartPunctuation.shouldCollapsePendingAutoSpaceBefore(")", "hi. ", pendingAutoSpace = true),
        )
        assertEquals(
            SmartPunctuation.CollapseAction.DELETE_PENDING_SPACE,
            SmartPunctuation.shouldCollapsePendingAutoSpaceBefore("1", "3. ", pendingAutoSpace = true),
        )
        assertEquals(
            SmartPunctuation.CollapseAction.CONSUME_DUPLICATE_SPACE,
            SmartPunctuation.shouldCollapsePendingAutoSpaceBefore(" ", "hi, ", pendingAutoSpace = true),
        )
        assertEquals(
            SmartPunctuation.CollapseAction.NONE,
            SmartPunctuation.shouldCollapsePendingAutoSpaceBefore("w", "hi, ", pendingAutoSpace = true),
        )
    }

    @Test
    fun autoSpaceAfterPunctuationRespectsRestrictedFieldsAndUrls() {
        assertTrue(
            SmartPunctuation.shouldInsertAutoSpaceAfter(
                punct = '.',
                textBeforeCursorAfterPunct = "Hello.",
                textAfterCursor = "",
                enabled = true,
                isRestrictedField = false,
            ),
        )
        assertFalse(
            SmartPunctuation.shouldInsertAutoSpaceAfter(
                punct = '.',
                textBeforeCursorAfterPunct = "www.",
                textAfterCursor = "",
                enabled = true,
                isRestrictedField = false,
            ),
        )
        assertFalse(
            SmartPunctuation.shouldInsertAutoSpaceAfter(
                punct = ',',
                textBeforeCursorAfterPunct = "hi,",
                textAfterCursor = " there",
                enabled = true,
                isRestrictedField = false,
            ),
        )
        assertFalse(
            SmartPunctuation.shouldInsertAutoSpaceAfter(
                punct = '.',
                textBeforeCursorAfterPunct = "Hello.",
                textAfterCursor = "",
                enabled = true,
                isRestrictedField = true,
            ),
        )
    }
}
