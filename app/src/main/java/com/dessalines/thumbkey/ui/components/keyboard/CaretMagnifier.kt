package com.dessalines.thumbkey.ui.components.keyboard

import android.os.Build
import android.text.InputType
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Immutable
data class MagnifierLine(
    val text: String,
    val caret: Int,
    val selStart: Int,
    val selEnd: Int,
    val selectedBeyond: SelectionBeyond = SelectionBeyond.NONE,
)

enum class SelectionBeyond { NONE, BEFORE, AFTER }

@Immutable
data class CaretAnchor(
    val x: Float,
    val top: Float,
    val bottom: Float,
)

@Immutable
data class CaretMagnifierState(
    val line: MagnifierLine,
    val anchor: CaretAnchor?,
)

internal const val MAGNIFIER_CONTEXT_CHARS = 64
internal const val MAGNIFIER_SELECTION_READ_CAP = 1024
private const val MASK_CHAR = '\u2022'

internal fun magnifierLine(
    before: CharSequence,
    selected: CharSequence,
    after: CharSequence,
    focusAtEnd: Boolean,
    context: Int = MAGNIFIER_CONTEXT_CHARS,
): MagnifierLine {
    val left: String
    val right: String
    val selStart: Int
    val selEnd: Int
    if (focusAtEnd) {
        val sel = selected.takeLastSafe(context)
        val head = if (sel.length < selected.length) "" else before.takeLastSafe(context)
        left = head + sel
        right = after.takeSafe(context)
        selStart = head.length
        selEnd = left.length
    } else {
        val sel = selected.takeSafe(context)
        val tail = if (sel.length < selected.length) "" else after.takeSafe(context)
        left = before.takeLastSafe(context)
        right = sel + tail
        selStart = left.length
        selEnd = left.length + sel.length
    }
    val lineStart = left.lastIndexOf('\n') + 1
    val newline = right.indexOf('\n')
    val lineEnd = left.length + if (newline < 0) right.length else newline
    val text = (left + right).substring(lineStart, lineEnd)

    fun clip(offset: Int) = (offset - lineStart).coerceIn(0, text.length)
    return MagnifierLine(
        text = text,
        caret = left.length - lineStart,
        selStart = clip(selStart),
        selEnd = clip(selEnd),
    )
}

private fun CharSequence.takeLastSafe(n: Int): String {
    if (length <= n) return toString()
    var start = length - n
    if (Character.isLowSurrogate(this[start])) start++
    return subSequence(start, length).toString()
}

private fun CharSequence.takeSafe(n: Int): String {
    if (length <= n) return toString()
    var end = n
    if (Character.isHighSurrogate(this[end - 1])) end--
    return subSequence(0, end).toString()
}

internal fun magnifierLineFromSurrounding(
    text: CharSequence,
    selStart: Int,
    selEnd: Int,
    focusAtEnd: Boolean,
): MagnifierLine? {
    val lo = minOf(selStart, selEnd)
    val hi = maxOf(selStart, selEnd)
    if (lo < 0 || hi > text.length) return null
    return magnifierLine(
        before = text.subSequence(0, lo),
        selected = text.subSequence(lo, hi),
        after = text.subSequence(hi, text.length),
        focusAtEnd = focusAtEnd,
    )
}

internal fun magnifierLineBeyondSelection(
    side: CharSequence,
    focusAtEnd: Boolean,
): MagnifierLine {
    val line =
        if (focusAtEnd) {
            magnifierLine("", "", side, focusAtEnd = true)
        } else {
            magnifierLine(side, "", "", focusAtEnd = true)
        }
    return line.copy(selectedBeyond = if (focusAtEnd) SelectionBeyond.BEFORE else SelectionBeyond.AFTER)
}

internal fun MagnifierLine.masked(): MagnifierLine {
    val src = text

    fun points(end: Int) = src.codePointCount(0, end.coerceIn(0, src.length))
    return copy(
        text = MASK_CHAR.toString().repeat(points(src.length)),
        caret = points(caret),
        selStart = points(selStart),
        selEnd = points(selEnd),
    )
}

internal fun hidesTypedText(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> {
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }

        InputType.TYPE_CLASS_NUMBER -> {
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        else -> {
            false
        }
    }
}

internal fun readMagnifierLine(
    ic: InputConnection,
    selStart: Int,
    selEnd: Int,
    focusAtEnd: Boolean,
    sdk: Int = Build.VERSION.SDK_INT,
): MagnifierLine? {
    val known = selStart >= 0 && selEnd >= 0
    val span = if (known) abs(selEnd - selStart) else 0
    if (span > MAGNIFIER_SELECTION_READ_CAP) {
        val side =
            if (focusAtEnd) {
                ic.getTextAfterCursor(MAGNIFIER_CONTEXT_CHARS, 0)
            } else {
                ic.getTextBeforeCursor(MAGNIFIER_CONTEXT_CHARS, 0)
            } ?: return null
        return magnifierLineBeyondSelection(side, focusAtEnd)
    }
    if (known && sdk >= Build.VERSION_CODES.S) {
        val surrounding = ic.getSurroundingText(MAGNIFIER_CONTEXT_CHARS, MAGNIFIER_CONTEXT_CHARS, 0)
        if (surrounding != null) {
            magnifierLineFromSurrounding(
                surrounding.text,
                surrounding.selectionStart,
                surrounding.selectionEnd,
                focusAtEnd,
            )?.let { return it }
        }
    }
    val before = ic.getTextBeforeCursor(MAGNIFIER_CONTEXT_CHARS, 0) ?: return null
    val selected = if (span > 0) ic.getSelectedText(0) ?: "" else ""
    val after = ic.getTextAfterCursor(MAGNIFIER_CONTEXT_CHARS, 0) ?: ""
    return magnifierLine(before, selected, after, focusAtEnd)
}

internal fun caretAnchorOf(info: CursorAnchorInfo): CaretAnchor? {
    val x = info.insertionMarkerHorizontal
    val top = info.insertionMarkerTop
    val bottom = info.insertionMarkerBottom
    if (x.isNaN() || top.isNaN() || bottom.isNaN()) return null
    if (info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0) return null
    val points = floatArrayOf(x, top, x, bottom)
    info.matrix.mapPoints(points)
    return CaretAnchor(points[0], points[1], points[3])
}

@OptIn(ExperimentalCoroutinesApi::class)
class CaretMagnifierController(
    private val scope: CoroutineScope,
    reader: CoroutineDispatcher = Dispatchers.IO,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    private val io = reader.limitedParallelism(1)
    private val _state = MutableStateFlow<CaretMagnifierState?>(null)
    val state: StateFlow<CaretMagnifierState?> = _state.asStateFlow()

    private var connection: InputConnection? = null
    private var mask = false
    private var anchor: CaretAnchor? = null
    private var selStart = -1
    private var selEnd = -1
    private var focus = -1
    private var reading = false
    private var readAgain = false
    private var session = 0

    var active: Boolean = false
        private set

    fun begin(
        ic: InputConnection,
        selStart: Int,
        selEnd: Int,
        mask: Boolean,
    ) {
        if (active && connection === ic) return
        active = true
        this.selStart = selStart
        this.selEnd = selEnd
        this.focus = selEnd
        session++
        connection = ic
        this.mask = mask
        anchor = null
        reading = false
        readAgain = false
        _state.value = null
        val flags = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
        scope.launch(io) { runCatching { ic.requestCursorUpdates(flags) } }
        read()
    }

    fun stop() {
        if (!active && connection == null && _state.value == null) return
        active = false
        session++
        reading = false
        readAgain = false
        connection = null
        anchor = null
        _state.value = null
    }

    fun onStep() {
        if (active) read()
    }

    fun onSelection(
        newSelStart: Int,
        newSelEnd: Int,
    ) {
        if (!active) return
        if (newSelStart == selStart && newSelEnd == selEnd && _state.value != null) return
        focus =
            when {
                newSelEnd != selEnd -> newSelEnd
                newSelStart != selStart -> newSelStart
                else -> focus
            }
        selStart = newSelStart
        selEnd = newSelEnd
        read()
    }

    fun onCursorAnchor(info: CursorAnchorInfo) {
        if (!active) return
        val next = caretAnchorOf(info)
        if (next == anchor) return
        anchor = next
        _state.value?.let { _state.value = it.copy(anchor = next) }
    }

    private fun read() {
        val ic = connection ?: return
        if (reading) {
            readAgain = true
            return
        }
        reading = true
        readAgain = false
        val start = selStart
        val end = selEnd
        val atEnd = start == end || focus == maxOf(start, end)
        val masked = mask
        val mine = session
        scope.launch {
            val line =
                withContext(io) {
                    runCatching { readMagnifierLine(ic, start, end, atEnd, sdk) }.getOrNull()
                }
            if (mine != session || !active) return@launch
            reading = false
            if (line != null) {
                _state.value = CaretMagnifierState(if (masked) line.masked() else line, anchor)
            }
            if (readAgain) read()
        }
    }
}
