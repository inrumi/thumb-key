package com.dessalines.thumbkey.ui.components.keyboard

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.autocorrect.AutocorrectEngine
import com.dessalines.thumbkey.autocorrect.AutocorrectSession

val SUGGESTION_BAR_HEIGHT = 44.dp

fun shouldShowSuggestionBar(
    inlineSuggestionViewsCount: Int = 0,
    suggestionsEnabled: Boolean,
    isRestrictedField: Boolean,
    isPanelOpen: Boolean,
    hasActiveLanguages: Boolean,
): Boolean =
    inlineSuggestionViewsCount > 0 ||
        (suggestionsEnabled && !isRestrictedField && !isPanelOpen && hasActiveLanguages)

@Composable
fun SuggestionBar(
    stripState: AutocorrectSession.SuggestionStripState,
    inlineSuggestionViews: List<View>,
    vibrateOnTap: Boolean,
    onUndoClick: () -> Unit,
    onSuggestionClick: (AutocorrectEngine.WordSuggestion) -> Unit,
    onEmojiClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    dividerAtBottom: Boolean = true,
) {
    val view = LocalView.current
    val hasAutofill = inlineSuggestionViews.isNotEmpty()
    val scrollState = rememberScrollState()

    LaunchedEffect(stripState.undoChip, stripState.words.firstOrNull()?.text) {
        scrollState.scrollTo(0)
    }

    Box(
        modifier =
            modifier
                .height(SUGGESTION_BAR_HEIGHT)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f)),
    ) {
        if (hasAutofill) {
            InlineSuggestionsStripContent(
                suggestions = inlineSuggestionViews,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                stripState.undoChip?.let { undo ->
                    val undoBg = MaterialTheme.colorScheme.primary
                    val undoFg = MaterialTheme.colorScheme.onPrimary
                    Row(
                        modifier =
                            Modifier
                                .padding(vertical = 7.dp)
                                .padding(end = 6.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .background(undoBg)
                                .clickable {
                                    if (vibrateOnTap) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onUndoClick()
                                }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Undo,
                            contentDescription = stringResource(R.string.suggestion_undo_description, undo.originalWord),
                            tint = undoFg,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = undo.originalWord,
                            color = undoFg,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }

                stripState.words.forEachIndexed { idx, word ->
                    if (idx > 0) StripDivider()
                    Box(
                        modifier =
                            Modifier
                                .fillMaxHeight()
                                .clickable {
                                    if (vibrateOnTap) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onSuggestionClick(word)
                                }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = word.text,
                            fontSize = 15.sp,
                            fontWeight = if (word.isPrimary) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (word.isPrimary) 1f else 0.88f),
                            maxLines = 1,
                        )
                    }
                }

                if (stripState.emojis.isNotEmpty()) {
                    if (stripState.words.isNotEmpty()) StripDivider()
                    stripState.emojis.forEach { emoji ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .clickable {
                                        if (vibrateOnTap) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onEmojiClick(emoji)
                                    }.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = emoji, fontSize = 19.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .align(if (dividerAtBottom) Alignment.BottomCenter else Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        )
    }
}

@Composable
private fun StripDivider() {
    Box(
        modifier =
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
    )
}
