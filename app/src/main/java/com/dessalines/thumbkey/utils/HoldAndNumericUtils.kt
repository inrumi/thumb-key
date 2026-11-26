package com.dessalines.thumbkey.utils

import kotlin.math.min

private val PHONE_DIGIT_PATTERNS =
    listOf(
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)),
        listOf(listOf(3, 2, 1), listOf(6, 5, 4), listOf(9, 8, 7)),
    )

private val CALCULATOR_DIGIT_PATTERNS =
    listOf(
        listOf(listOf(7, 8, 9), listOf(4, 5, 6), listOf(1, 2, 3)),
        listOf(listOf(9, 8, 7), listOf(6, 5, 4), listOf(3, 2, 1)),
    )

fun KeyAction.decimalDigitValue(): Int? {
    val commit = this as? KeyAction.CommitText ?: return null
    if (commit.text.length != 1) return null
    val ch = commit.text[0]
    if (!Character.isDigit(ch)) return null
    return Character.getNumericValue(ch).takeIf { it in 0..9 }
}

private inline fun reorderDigitBlocks(
    keyboard: KeyboardC,
    order: NumericLayoutOrder,
    extractDigit: (KeyItemC) -> Int?,
    swapItems: (KeyItemC, KeyItemC) -> Pair<KeyItemC, KeyItemC>,
): KeyboardC {
    if (order == NumericLayoutOrder.Default || keyboard.arr.size < 3) return keyboard

    val rows = keyboard.arr.map { it.toMutableList() }
    val visited = HashSet<Pair<Int, Int>>()
    var changed = false

    for (rowTop in 0..(rows.size - 3)) {
        val minRowSize = min(rows[rowTop].size, min(rows[rowTop + 1].size, rows[rowTop + 2].size))
        var colStart = 0
        while (colStart + 2 < minRowSize) {
            var skip = false
            val block =
                List(3) { dr ->
                    List(3) { dc ->
                        val coord = (rowTop + dr) to (colStart + dc)
                        val digit = extractDigit(rows[rowTop + dr][colStart + dc])
                        if (coord in visited || digit == null) {
                            skip = true
                            -1
                        } else {
                            digit
                        }
                    }
                }
            if (skip) {
                colStart++
                continue
            }

            val isPhone = block in PHONE_DIGIT_PATTERNS
            val isCalculator = block in CALCULATOR_DIGIT_PATTERNS
            if (isPhone || isCalculator) {
                for (dr in 0..2) {
                    for (dc in 0..2) visited.add((rowTop + dr) to (colStart + dc))
                }
                val needsFlip =
                    (isPhone && order == NumericLayoutOrder.Calculator) ||
                        (isCalculator && order == NumericLayoutOrder.Phone)
                if (needsFlip) {
                    for (dc in 0..2) {
                        val (newTop, newBottom) = swapItems(rows[rowTop][colStart + dc], rows[rowTop + 2][colStart + dc])
                        rows[rowTop][colStart + dc] = newTop
                        rows[rowTop + 2][colStart + dc] = newBottom
                    }
                    changed = true
                }
                colStart += 3
            } else {
                colStart++
            }
        }
    }

    return if (changed) KeyboardC(rows.map { it.toList() }) else keyboard
}

fun applyNumericLayoutOrder(
    definition: KeyboardDefinition,
    order: NumericLayoutOrder,
): KeyboardDefinition {
    if (order == NumericLayoutOrder.Default) return definition
    val modes = definition.modes
    val flipCenter = { kb: KeyboardC ->
        reorderDigitBlocks(kb, order, { it.center.action.decimalDigitValue() }) { top, bottom ->
            top.copy(center = bottom.center) to bottom.copy(center = top.center)
        }
    }
    val flipLongPress = { kb: KeyboardC ->
        reorderDigitBlocks(kb, order, { it.longPress?.decimalDigitValue() }) { top, bottom ->
            top.copy(longPress = bottom.longPress) to bottom.copy(longPress = top.longPress)
        }
    }
    return definition.copy(
        modes =
            modes.copy(
                main = flipLongPress(modes.main),
                shifted = flipLongPress(modes.shifted),
                numeric = flipCenter(modes.numeric),
                alted = modes.alted?.let(flipCenter),
            ),
    )
}

fun findNumericKeyForCell(
    mainRow: List<KeyItemC>,
    colIndex: Int,
    numericRow: List<KeyItemC>?,
): KeyItemC? {
    if (numericRow.isNullOrEmpty() || colIndex !in mainRow.indices) return null
    val targetStart = (0 until colIndex).sumOf { mainRow[it].widthMultiplier }
    val targetEnd = targetStart + mainRow[colIndex].widthMultiplier

    val overlapping = mutableListOf<KeyItemC>()
    var colStart = 0
    for (item in numericRow) {
        val colEnd = colStart + item.widthMultiplier
        if (colStart < targetEnd && colEnd > targetStart) overlapping.add(item)
        colStart = colEnd
    }

    return overlapping.firstOrNull { it.center.action.decimalDigitValue() != null }
        ?: overlapping.firstOrNull()
}

fun KeyItemC.isBackspaceRepeatHold(backspaceHoldAction: BackspaceHoldAction): Boolean =
    backspaceHoldAction == BackspaceHoldAction.RepeatDelete &&
        longPress == KeyAction.DeleteWordBeforeCursor

fun resolveHoldKeyAction(
    key: KeyItemC,
    holdKeyAction: HoldKeyAction,
    oppositeCaseKey: KeyItemC?,
    numericKey: KeyItemC?,
    backspaceHoldAction: BackspaceHoldAction = BackspaceHoldAction.DeleteWord,
): KeyAction? {
    if (key.longPress == KeyAction.DeleteWordBeforeCursor) {
        return when (backspaceHoldAction) {
            BackspaceHoldAction.DeleteWord -> {
                KeyAction.DeleteWordBeforeCursor
            }

            BackspaceHoldAction.RepeatDelete -> {
                key.center.action.takeIf {
                    it is KeyAction.DeleteKeyAction || it is KeyAction.DeleteViaTextManipulation
                } ?: KeyAction.DeleteKeyAction
            }

            BackspaceHoldAction.None -> {
                null
            }
        }
    }
    key.longPress?.let { return it }
    val centerCommit = key.center.action as? KeyAction.CommitText ?: return null
    return when (holdKeyAction) {
        HoldKeyAction.None -> {
            null
        }

        HoldKeyAction.Number -> {
            numericKey?.center?.action?.takeIf { it.decimalDigitValue() != null }
        }

        HoldKeyAction.OppositeCase -> {
            (oppositeCaseKey?.center?.action as? KeyAction.CommitText)
                ?.takeIf { it.text != centerCommit.text }
        }
    }
}
