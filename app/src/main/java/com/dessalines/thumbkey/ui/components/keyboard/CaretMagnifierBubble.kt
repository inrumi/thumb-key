package com.dessalines.thumbkey.ui.components.keyboard

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private val MagnifierWidth = 176.dp
private val MagnifierHeight = 48.dp
private val MagnifierGap = 12.dp
private val MagnifierFontSize = 22.sp

private const val MAGNIFIER_GROW_FROM = 0.8f
private const val MAGNIFIER_OUT_MS = 110
private const val MAGNIFIER_FADE_FRACTION = 0.18f

private val MagnifierInSpring =
    spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

private val MagnifierFollowSpring =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 2500f,
        visibilityThreshold = Offset.VisibilityThreshold,
    )

private val MagnifierPopupProperties =
    PopupProperties(
        flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    )

@Composable
fun CaretMagnifierHost(
    state: StateFlow<CaretMagnifierState?>,
    isFloating: Boolean = false,
) {
    val current by state.collectAsState()
    val held = remember { arrayOfNulls<CaretMagnifierState>(1) }
    val live = current
    if (live != null) held[0] = live
    val up = live != null
    val appear = remember { Animatable(0f) }
    LaunchedEffect(up) {
        if (up) {
            appear.animateTo(1f, MagnifierInSpring)
        } else {
            appear.animateTo(0f, tween(MAGNIFIER_OUT_MS, easing = FastOutLinearInEasing))
        }
    }
    val gone by remember { derivedStateOf { appear.value == 0f } }
    val follow = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val seeded = remember { BooleanArray(1) }
    if (!up && gone) {
        held[0] = null
        seeded[0] = false
        return
    }
    val magnifier = held[0] ?: return
    val anchor = magnifier.anchor
    LaunchedEffect(anchor) {
        if (anchor == null) {
            seeded[0] = false
            return@LaunchedEffect
        }
        val to = Offset(anchor.x, anchor.top)
        if (!seeded[0]) {
            follow.snapTo(to)
            seeded[0] = true
        } else {
            follow.animateTo(to, MagnifierFollowSpring)
        }
    }
    val placed =
        if (anchor != null && seeded[0]) {
            val at = follow.value
            CaretAnchor(x = at.x, top = at.y, bottom = at.y + (anchor.bottom - anchor.top))
        } else {
            anchor
        }
    val view = LocalView.current
    val density = LocalDensity.current
    val origin =
        remember(view) {
            IntArray(2).also { view.rootView.getLocationOnScreen(it) }.let { IntOffset(it[0], it[1]) }
        }
    val gapPx = with(density) { MagnifierGap.roundToPx() }
    val provider =
        remember(placed, origin, gapPx, isFloating) {
            MagnifierPositionProvider(placed, origin, gapPx, isFloating)
        }
    Popup(popupPositionProvider = provider, properties = MagnifierPopupProperties) {
        MagnifierBubble(magnifier.line) { appear.value }
    }
}

private class MagnifierPositionProvider(
    private val anchor: CaretAnchor?,
    private val windowOrigin: IntOffset,
    private val gapPx: Int,
    private val isFloating: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val maxX = (windowSize.width - width).coerceAtLeast(0)
        if (anchor == null) {
            val x = (anchorBounds.left + (anchorBounds.width - width) / 2).coerceIn(0, maxX)
            val aboveAnchor = anchorBounds.top - height - gapPx
            val y = if (isFloating && aboveAnchor + windowOrigin.y < gapPx) anchorBounds.bottom + gapPx else aboveAnchor
            return IntOffset(x, y)
        }
        val x = (anchor.x - windowOrigin.x - width / 2f).roundToInt().coerceIn(0, maxX)
        val above = (anchor.top - windowOrigin.y).roundToInt() - gapPx - height
        val y =
            if (above + windowOrigin.y >= gapPx) {
                above
            } else {
                (anchor.bottom - windowOrigin.y).roundToInt() + gapPx
            }
        return if (isFloating) {
            val minY = gapPx - windowOrigin.y
            val maxY = (windowSize.height - height - gapPx).coerceAtLeast(minY)
            IntOffset(x, y.coerceIn(minY, maxY))
        } else {
            IntOffset(x, minOf(y, anchorBounds.top - height - gapPx))
        }
    }
}

@Composable
private fun MagnifierBubble(
    line: MagnifierLine,
    appear: () -> Float,
) {
    val measurer = rememberTextMeasurer()
    val pillColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val textColor = MaterialTheme.colorScheme.onSurface
    val style = LocalTextStyle.current.merge(TextStyle(color = textColor, fontSize = MagnifierFontSize))
    val shape = RoundedCornerShape(50)
    val selectionColor = textColor.copy(alpha = 0.28f)
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    val p = appear()
                    val scale = MAGNIFIER_GROW_FROM + (1f - MAGNIFIER_GROW_FROM) * p
                    alpha = p.coerceIn(0f, 1f)
                    scaleX = scale
                    scaleY = scale
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }.shadow(6.dp, shape, clip = false)
                .background(pillColor, shape)
                .clip(shape)
                .size(MagnifierWidth, MagnifierHeight)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val layout =
                        measurer.measure(
                            AnnotatedString(line.text),
                            style = style,
                            overflow = TextOverflow.Clip,
                            softWrap = false,
                            maxLines = 1,
                        )
                    val caretX = layout.getHorizontalPosition(line.caret, usePrimaryDirection = true)
                    val shiftX = size.width / 2 - caretX
                    val shiftY = (size.height - layout.size.height) / 2
                    val selection =
                        if (line.selEnd > line.selStart) {
                            layout.getPathForRange(line.selStart, line.selEnd)
                        } else {
                            null
                        }
                    val beyond =
                        when (line.selectedBeyond) {
                            SelectionBeyond.NONE -> null
                            SelectionBeyond.BEFORE -> 0f to size.width / 2
                            SelectionBeyond.AFTER -> size.width / 2 to size.width
                        }
                    val caretWidth = 2.dp.toPx()
                    val caretTop = shiftY + 2.dp.toPx()
                    val caretBottom = shiftY + layout.size.height - 2.dp.toPx()
                    val fade =
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            MAGNIFIER_FADE_FRACTION to Color.Black,
                            1f - MAGNIFIER_FADE_FRACTION to Color.Black,
                            1f to Color.Transparent,
                        )
                    onDrawBehind {
                        beyond?.let { (from, to) ->
                            drawRect(
                                selectionColor,
                                topLeft = Offset(from, shiftY),
                                size = Size(to - from, layout.size.height.toFloat()),
                            )
                        }
                        translate(shiftX, shiftY) {
                            selection?.let { drawPath(it, selectionColor) }
                            drawText(layout)
                        }
                        drawLine(
                            textColor,
                            Offset(size.width / 2, caretTop),
                            Offset(size.width / 2, caretBottom),
                            strokeWidth = caretWidth,
                        )
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                },
    )
}
