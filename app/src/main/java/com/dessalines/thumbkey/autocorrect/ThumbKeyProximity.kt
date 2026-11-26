package com.dessalines.thumbkey.autocorrect

import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyC
import com.dessalines.thumbkey.utils.KeyItemC
import com.dessalines.thumbkey.utils.KeyboardDefinition
import kotlin.math.abs

/**
 * Keyboard-geometry cost model for substitution and insertion slips.
 */
interface KeyboardProximity {
    /**
     * Normalized slip factor in `[0.0 .. 1.0]`:
     * - `0.15` = center <-> swipe on the same 3x3 key
     * - `0.25` = adjacent compass swipe direction on the same key
     * - `0.55` = adjacent 3x3 grid cell
     * - `0.75` = near swipe/neighbor
     * - `1.0`  = unrelated / distant key
     */
    fun distanceFactor(
        typed: Char,
        target: Char,
    ): Double

    fun substituteCost(
        typed: Char,
        target: Char,
    ): Double

    fun isAdjacent(
        a: Char,
        b: Char,
    ): Boolean

    companion object {
        const val SAME_KEY_CENTER_SWIPE = 0.15
        const val SAME_KEY_RING_ADJACENT = 0.25
        const val ADJACENT_CELL = 0.55
        const val OTHER_NEAR = 0.75
        const val FAR = 1.0

        val NONE: KeyboardProximity =
            object : KeyboardProximity {
                override fun distanceFactor(
                    typed: Char,
                    target: Char,
                ): Double = if (typed == target) 0.0 else FAR

                override fun substituteCost(
                    typed: Char,
                    target: Char,
                ): Double = if (typed == target) 0.0 else FuzzyBeamSearch.COST_SUB_FAR

                override fun isAdjacent(
                    a: Char,
                    b: Char,
                ): Boolean = a == b
            }
    }
}

/**
 * Builds Thumb-Key-specific proximity costs directly from [KeyboardDefinition.modes.main].
 *
 * Models how 3x3 directional swiping mis-aims:
 * 1. Tapping center vs swiping on the same key (`0.15`) -> [FuzzyBeamSearch.COST_SUB_ADJACENT]
 * 2. Missing compass ring direction by 1 step on the same key (`0.25`) -> [FuzzyBeamSearch.COST_SUB_RING]
 * 3. Center tap slipping onto an orthogonally adjacent 3x3 key cell (`0.55`) -> [FuzzyBeamSearch.COST_SUB_NEAR_CELL]
 * 4. 90-degree swipe slip, same swipe on an orthogonal neighbor key, or diagonal center slip (`0.75`) -> [FuzzyBeamSearch.COST_SUB_NEAR]
 */
class ThumbKeyProximity private constructor(
    private val placements: Map<Char, List<Placement>>,
) : KeyboardProximity {
    private data class Placement(
        val row: Int,
        val col: Int,
        val ringSlot: Int, // -1 for center, 0..7 clockwise starting LEFT
    )

    override fun distanceFactor(
        typed: Char,
        target: Char,
    ): Double {
        val a = typed.lowercaseChar()
        val b = target.lowercaseChar()
        if (a == b) return 0.0
        val pa = placements[a] ?: return KeyboardProximity.FAR
        val pb = placements[b] ?: return KeyboardProximity.FAR

        var best = KeyboardProximity.FAR
        for (x in pa) {
            for (y in pb) {
                val factor = pairFactor(x, y)
                if (factor < best) best = factor
                if (best <= KeyboardProximity.SAME_KEY_CENTER_SWIPE) return best
            }
        }
        return best
    }

    override fun substituteCost(
        typed: Char,
        target: Char,
    ): Double =
        when (val f = distanceFactor(typed, target)) {
            0.0 -> 0.0
            KeyboardProximity.SAME_KEY_CENTER_SWIPE -> FuzzyBeamSearch.COST_SUB_ADJACENT
            KeyboardProximity.SAME_KEY_RING_ADJACENT -> FuzzyBeamSearch.COST_SUB_RING
            KeyboardProximity.ADJACENT_CELL -> FuzzyBeamSearch.COST_SUB_NEAR_CELL
            KeyboardProximity.OTHER_NEAR -> FuzzyBeamSearch.COST_SUB_NEAR
            else -> if (f < 1.0) FuzzyBeamSearch.COST_SUB_NEAR else FuzzyBeamSearch.COST_SUB_FAR
        }

    override fun isAdjacent(
        a: Char,
        b: Char,
    ): Boolean = distanceFactor(a, b) <= KeyboardProximity.ADJACENT_CELL

    private fun pairFactor(
        x: Placement,
        y: Placement,
    ): Double {
        if (x.row == y.row && x.col == y.col) {
            if (x.ringSlot == -1 || y.ringSlot == -1) {
                return KeyboardProximity.SAME_KEY_CENTER_SWIPE
            }
            val diff = abs(x.ringSlot - y.ringSlot)
            val ringDist = minOf(diff, 8 - diff)
            return when {
                ringDist == 0 -> KeyboardProximity.SAME_KEY_CENTER_SWIPE
                ringDist == 1 -> KeyboardProximity.SAME_KEY_RING_ADJACENT
                ringDist == 2 -> KeyboardProximity.OTHER_NEAR
                else -> KeyboardProximity.FAR
            }
        }
        val rowDiff = abs(x.row - y.row)
        val colDiff = abs(x.col - y.col)
        // Orthogonal neighbor cell: center-to-center tap slip OR identical swipe started 1 key off
        if (rowDiff + colDiff == 1) {
            if (x.ringSlot == -1 && y.ringSlot == -1) {
                return KeyboardProximity.ADJACENT_CELL
            }
            if (x.ringSlot >= 0 && x.ringSlot == y.ringSlot) {
                return KeyboardProximity.OTHER_NEAR
            }
            return KeyboardProximity.FAR
        }
        // Diagonal neighbor cell: only center-to-center tap slip counts as near
        if (rowDiff == 1 && colDiff == 1 && x.ringSlot == -1 && y.ringSlot == -1) {
            return KeyboardProximity.OTHER_NEAR
        }
        return KeyboardProximity.FAR
    }

    companion object {
        fun fromDefinition(definition: KeyboardDefinition): ThumbKeyProximity {
            val map = HashMap<Char, MutableList<Placement>>(48)
            val rows = definition.modes.main.arr
            for (r in rows.indices) {
                val row = rows[r]
                for (c in row.indices) {
                    val item = row[c]
                    recordKey(map, item.center, r, c, -1)
                    recordKey(map, item.left, r, c, 0)
                    recordKey(map, item.topLeft, r, c, 1)
                    recordKey(map, item.top, r, c, 2)
                    recordKey(map, item.topRight, r, c, 3)
                    recordKey(map, item.right, r, c, 4)
                    recordKey(map, item.bottomRight, r, c, 5)
                    recordKey(map, item.bottom, r, c, 6)
                    recordKey(map, item.bottomLeft, r, c, 7)
                }
            }
            return ThumbKeyProximity(map)
        }

        private fun recordKey(
            map: MutableMap<Char, MutableList<Placement>>,
            keyC: KeyC?,
            row: Int,
            col: Int,
            ringSlot: Int,
        ) {
            val action = keyC?.action as? KeyAction.CommitText ?: return
            val text = action.text.trim()
            if (text.length != 1) return
            val ch = text[0].lowercaseChar()
            if (!ch.isLetter()) return
            map.getOrPut(ch) { ArrayList(2) }.add(Placement(row, col, ringSlot))
        }
    }
}
