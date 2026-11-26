package com.dessalines.thumbkey.ui.components.keyboard

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretMagnifierTest {
    @Test
    fun magnifierLineClampsToCurrentLineAndTracksSelection() {
        val line = magnifierLine("hello\nlp some", "", "thing\nnext", focusAtEnd = true)
        assertEquals("lp something", line.text)
        assertEquals(7, line.caret)
        assertEquals(7, line.selStart)
        assertEquals(7, line.selEnd)
        assertEquals(SelectionBeyond.NONE, line.selectedBeyond)

        val selectedEnd = magnifierLine("lp ", "some", "thing", focusAtEnd = true)
        assertEquals("lp something", selectedEnd.text)
        assertEquals(7, selectedEnd.caret)
        assertEquals(3, selectedEnd.selStart)
        assertEquals(7, selectedEnd.selEnd)

        val selectedStart = magnifierLine("lp ", "some", "thing", focusAtEnd = false)
        assertEquals(3, selectedStart.caret)
        assertEquals(3, selectedStart.selStart)
        assertEquals(7, selectedStart.selEnd)
    }

    @Test
    fun surroundingAndLongSelectionsBuildValidLines() {
        val fromSurr = magnifierLineFromSurrounding("lp something else", 3, 7, focusAtEnd = true)
        assertEquals("lp something else", fromSurr?.text)
        assertEquals(7, fromSurr?.caret)
        assertEquals(3, fromSurr?.selStart)
        assertEquals(7, fromSurr?.selEnd)

        assertNull(magnifierLineFromSurrounding("abc", -1, 2, focusAtEnd = true))
        assertNull(magnifierLineFromSurrounding("abc", 0, 5, focusAtEnd = true))

        val beyondStart = magnifierLineBeyondSelection("tail text", focusAtEnd = true)
        assertEquals(SelectionBeyond.BEFORE, beyondStart.selectedBeyond)
        assertEquals("tail text", beyondStart.text)
        assertEquals(0, beyondStart.caret)

        val beyondEnd = magnifierLineBeyondSelection("head text", focusAtEnd = false)
        assertEquals(SelectionBeyond.AFTER, beyondEnd.selectedBeyond)
        assertEquals("head text", beyondEnd.text)
        assertEquals(9, beyondEnd.caret)
    }

    @Test
    fun maskedReplacesCodePointsAndHidesPasswordInputTypes() {
        val raw = MagnifierLine(text = "secr😀t", caret = 4, selStart = 1, selEnd = 6)
        val hidden = raw.masked()
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022", hidden.text)
        assertEquals(4, hidden.caret)
        assertEquals(1, hidden.selStart)
        assertEquals(5, hidden.selEnd)

        assertTrue(hidesTypedText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(hidesTypedText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(hidesTypedText(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(hidesTypedText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertFalse(hidesTypedText(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }
}
