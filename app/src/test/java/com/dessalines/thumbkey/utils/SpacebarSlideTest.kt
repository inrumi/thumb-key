package com.dessalines.thumbkey.utils

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacebarSlideTest {
    @Test
    fun singleLineEditorDetectionDistinguishesSearchFromMultilineAndRawEditors() {
        assertTrue(isSingleLineEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH))
        assertTrue(isSingleLineEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO))
        assertTrue(isSingleLineEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE))

        assertFalse(
            isSingleLineEditor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_SEND,
            ),
        )
        assertFalse(
            isSingleLineEditor(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            ),
        )
        assertFalse(isSingleLineEditor(EditorInfo.TYPE_NULL, EditorInfo.IME_ACTION_NONE))
        assertFalse(isSingleLineEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE))
    }

    @Test
    fun verticalSlideGeometryFiltersSidewaysDriftAndScalesWithSensitivity() {
        val lowSens = verticalSlideStepDp(1)
        val defaultSens = verticalSlideStepDp(9)
        val highSens = verticalSlideStepDp(45)
        assertTrue(lowSens > defaultSens)
        assertTrue(defaultSens > highSens)
        assertTrue(highSens in 16f..40f)
        assertTrue(lowSens in 16f..40f)

        assertTrue(isVerticalSlideDominant(dx = 0f, dy = -12f))
        assertTrue(isVerticalSlideDominant(dx = 5f, dy = 10f))
        assertTrue(isVerticalSlideDominant(dx = 10f, dy = 9f))

        assertFalse(isVerticalSlideDominant(dx = 20f, dy = 5f))
        assertFalse(isVerticalSlideDominant(dx = -15f, dy = -4f))
    }
}
