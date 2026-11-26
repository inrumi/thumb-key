package com.dessalines.thumbkey.ui.components.keyboard

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private class InlineSuggestionsStripView(
    context: Context,
) : HorizontalScrollView(context) {
    private val container = LinearLayout(context)
    private var attachedViews: List<View> = emptyList()
    private val spacingPx = dpToPx(4)

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = true
        clipToPadding = true
        container.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
        }
        addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setSuggestions(views: List<View>) {
        if (attachedViews.size == views.size && attachedViews.indices.all { attachedViews[it] === views[it] }) {
            updateChildClipBounds()
            return
        }
        if (attachedViews.none { old -> views.any { it === old } }) {
            scrollTo(0, 0)
        }
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (views.none { it === child }) {
                cleanupDetachedView(child)
                container.removeViewAt(i)
            }
        }
        views.forEachIndexed { index, view ->
            val desiredMarginEnd = if (index < views.lastIndex) spacingPx else 0
            if (index < container.childCount && container.getChildAt(index) === view) {
                val lp = view.layoutParams as? LinearLayout.LayoutParams
                if (lp != null && lp.marginEnd != desiredMarginEnd) {
                    lp.marginEnd = desiredMarginEnd
                    view.layoutParams = lp
                }
            } else {
                val existingIndex = (0 until container.childCount).firstOrNull { container.getChildAt(it) === view }
                if (existingIndex != null) {
                    container.removeViewAt(existingIndex)
                } else {
                    (view.parent as? ViewGroup)?.removeView(view)
                    attachViewCallbacks(view)
                }
                val lp =
                    LinearLayout
                        .LayoutParams(
                            view.layoutParams?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                            view.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = desiredMarginEnd }
                container.addView(view, index, lp)
            }
        }
        attachedViews = views
        post { updateChildClipBounds(force = true) }
    }

    fun clearSuggestions() {
        attachedViews.forEach(::cleanupDetachedView)
        container.removeAllViews()
        attachedViews = emptyList()
    }

    // InlineContentView renders on its own SurfaceControl; clip explicitly so chips don't bleed outside the strip.
    private fun attachViewCallbacks(view: View) {
        view.clipBounds = null
        view.visibility = VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view is InlineContentView) {
            view.setSurfaceControlCallback(
                object : InlineContentView.SurfaceControlCallback {
                    override fun onCreated(surfaceControl: SurfaceControl) {
                        post { updateChildClipBounds(force = true) }
                    }

                    override fun onDestroyed(surfaceControl: SurfaceControl) {}
                },
            )
        }
    }

    private fun cleanupDetachedView(view: View) {
        view.clipBounds = null
        view.visibility = VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && view is InlineContentView) {
            view.setSurfaceControlCallback(null)
        }
    }

    override fun onScrollChanged(
        l: Int,
        t: Int,
        oldl: Int,
        oldt: Int,
    ) {
        super.onScrollChanged(l, t, oldl, oldt)
        updateChildClipBounds()
    }

    override fun onLayout(
        changed: Boolean,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
    ) {
        super.onLayout(changed, l, t, r, b)
        updateChildClipBounds()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateChildClipBounds()
    }

    override fun onDetachedFromWindow() {
        clearSuggestions()
        super.onDetachedFromWindow()
    }

    private fun updateChildClipBounds(force: Boolean = false) {
        if (width <= 0 || height <= 0) return
        val viewportLeft = scrollX
        val viewportRight = scrollX + width
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.width <= 0 || child.height <= 0) continue
            val childLeft = container.left + child.left
            val childRight = container.left + child.right
            val visibleLeft = maxOf(childLeft, viewportLeft)
            val visibleRight = minOf(childRight, viewportRight)
            if (visibleRight <= visibleLeft) {
                val empty = Rect(0, 0, 0, 0)
                if (force || child.clipBounds != empty) child.clipBounds = empty
                if (child.visibility != INVISIBLE) child.visibility = INVISIBLE
            } else {
                if (child.visibility != VISIBLE) child.visibility = VISIBLE
                val clipLeft = (visibleLeft - childLeft).coerceIn(0, child.width)
                val clipRight = (visibleRight - childLeft).coerceIn(0, child.width)
                val nextRect = if (clipLeft == 0 && clipRight == child.width) null else Rect(clipLeft, 0, clipRight, child.height)
                if (force || child.clipBounds != nextRect) child.clipBounds = nextRect
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}

@Composable
fun InlineSuggestionsBar(
    suggestions: List<View>,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    val snapshot = suggestions.toList()
    Box(
        modifier =
            modifier
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = ::InlineSuggestionsStripView,
            update = { it.setSuggestions(snapshot) },
            onRelease = { it.clearSuggestions() },
        )
    }
}
