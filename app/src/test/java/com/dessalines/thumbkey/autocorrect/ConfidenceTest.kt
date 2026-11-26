package com.dessalines.thumbkey.autocorrect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceTest {
    private val fixed =
        Confidence.Case(
            margin = 2.3,
            topScore = 7.9,
            topEditCost = 0.105,
            topEdits = 1,
            topSlips = 1,
            topAccents = 0,
            topCompleted = 0,
            topInDictionary = true,
            topInLexicon = false,
            twoSources = false,
            mixesSlips = false,
            typedLength = 6,
            hasRunner = true,
            runnerEditCost = 1.6,
            candidates = 4,
            lmTop = -2.0f,
            lmBest = -2.0f,
            timing = 0.0,
            typedShadowed = false,
            lmTyped = Float.NaN,
            lmTypedRank = -1,
            lmEntropy = 4.2f,
            lmSecond = -3.5f,
            lmTypedPieces = -28f,
        )

    @Test
    fun fixedCaseMatchesWmkeyboardReferenceMlp() {
        assertEquals(0.926967, Confidence.probability(fixed), 1e-4)
    }

    @Test
    fun widerMarginIsSurerAndThresholdsIncreaseMonotonically() {
        val narrow =
            Confidence.Case(
                margin = 0.3,
                topScore = 7.9,
                topEditCost = 0.105,
                topEdits = 1,
                topSlips = 0,
                topAccents = 0,
                topCompleted = 0,
                topInDictionary = true,
                topInLexicon = false,
                twoSources = false,
                mixesSlips = false,
                typedLength = 6,
                hasRunner = true,
                runnerEditCost = 1.6,
                candidates = 4,
            )
        val wide =
            Confidence.Case(
                margin = 6.0,
                topScore = 7.9,
                topEditCost = 0.105,
                topEdits = 1,
                topSlips = 0,
                topAccents = 0,
                topCompleted = 0,
                topInDictionary = true,
                topInLexicon = false,
                twoSources = false,
                mixesSlips = false,
                typedLength = 6,
                hasRunner = true,
                runnerEditCost = 1.6,
                candidates = 4,
            )
        assertTrue(Confidence.probability(wide) > Confidence.probability(narrow))
        assertEquals(0.8, Confidence.applyThreshold(4.0), 1e-9)
        assertTrue(Confidence.offerThreshold(4.0) < Confidence.applyThreshold(4.0))
        assertTrue(Confidence.applyThreshold(10.0) > Confidence.applyThreshold(1.5))
    }
}
