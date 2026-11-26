package com.dessalines.thumbkey.ui.components.keyboard

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.autocorrect.AutocorrectSession
import com.dessalines.thumbkey.autocorrect.LayoutLanguageResolver
import com.dessalines.thumbkey.db.AppSettings
import com.dessalines.thumbkey.db.ClipboardRepository
import com.dessalines.thumbkey.db.DEFAULT_ANIMATION_HELPER_SPEED
import com.dessalines.thumbkey.db.DEFAULT_ANIMATION_SPEED
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ADDITIONAL_LANGUAGES
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_AUTO_CAPITALIZE
import com.dessalines.thumbkey.db.DEFAULT_AUTO_SIZE_KEYS
import com.dessalines.thumbkey.db.DEFAULT_BACKDROP_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_BACKSPACE_HOLD_ACTION
import com.dessalines.thumbkey.db.DEFAULT_CIRCULAR_DRAG_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CLIPBOARD_HISTORY_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_DRAG_RETURN_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_HANDLE_POSITION
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_X_FRACTION
import com.dessalines.thumbkey.db.DEFAULT_FLOATING_Y_FRACTION
import com.dessalines.thumbkey.db.DEFAULT_GHOST_KEYS_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_HIDE_LETTERS
import com.dessalines.thumbkey.db.DEFAULT_HIDE_SYMBOLS
import com.dessalines.thumbkey.db.DEFAULT_HOLD_DURATION_MS
import com.dessalines.thumbkey.db.DEFAULT_HOLD_KEY_ACTION
import com.dessalines.thumbkey.db.DEFAULT_IGNORE_BOTTOM_PADDING
import com.dessalines.thumbkey.db.DEFAULT_KEYBOARD_LAYOUT
import com.dessalines.thumbkey.db.DEFAULT_KEYBOARD_OPACITY
import com.dessalines.thumbkey.db.DEFAULT_KEY_ANIMATION_TYPE
import com.dessalines.thumbkey.db.DEFAULT_KEY_BORDER_WIDTH
import com.dessalines.thumbkey.db.DEFAULT_KEY_HEIGHT
import com.dessalines.thumbkey.db.DEFAULT_KEY_PADDING
import com.dessalines.thumbkey.db.DEFAULT_KEY_RADIUS
import com.dessalines.thumbkey.db.DEFAULT_KEY_WIDTH
import com.dessalines.thumbkey.db.DEFAULT_MIN_SWIPE_LENGTH
import com.dessalines.thumbkey.db.DEFAULT_NON_SQUARE_KEYS
import com.dessalines.thumbkey.db.DEFAULT_NUMERIC_LAYOUT_ORDER
import com.dessalines.thumbkey.db.DEFAULT_POSITION
import com.dessalines.thumbkey.db.DEFAULT_POSITION_PADDING
import com.dessalines.thumbkey.db.DEFAULT_PUSHUP_SIZE
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_HOLD_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SENSITIVITY
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SOUND_ON_TAP
import com.dessalines.thumbkey.db.DEFAULT_SPACEBAR_MULTITAPS
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTIONS_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTION_BAR_FLOATING_PLACEMENT
import com.dessalines.thumbkey.db.DEFAULT_SWIPE_TRAIL_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SWITCH_TO_LETTERS_AFTER_SPACE
import com.dessalines.thumbkey.db.DEFAULT_VIBRATE_ON_SLIDE
import com.dessalines.thumbkey.db.DEFAULT_VIBRATE_ON_TAP
import com.dessalines.thumbkey.db.MAX_HOLD_DURATION_MS
import com.dessalines.thumbkey.db.MAX_KEYBOARD_OPACITY
import com.dessalines.thumbkey.db.MIN_HOLD_DURATION_MS
import com.dessalines.thumbkey.db.MIN_KEYBOARD_OPACITY
import com.dessalines.thumbkey.keyboards.BACKSPACE_KEY_ITEM
import com.dessalines.thumbkey.keyboards.EMOJI_BACK_KEY_ITEM
import com.dessalines.thumbkey.keyboards.KB_EN_THUMBKEY_MAIN
import com.dessalines.thumbkey.keyboards.NUMERIC_KEY_ITEM
import com.dessalines.thumbkey.keyboards.RETURN_KEY_ITEM
import com.dessalines.thumbkey.utils.BackspaceHoldAction
import com.dessalines.thumbkey.utils.CircularDragAction
import com.dessalines.thumbkey.utils.ColorVariant
import com.dessalines.thumbkey.utils.FloatingHandlePosition
import com.dessalines.thumbkey.utils.HoldKeyAction
import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyAnimationType
import com.dessalines.thumbkey.utils.KeyDisplay
import com.dessalines.thumbkey.utils.KeyItemC
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.KeyboardMode
import com.dessalines.thumbkey.utils.KeyboardPosition
import com.dessalines.thumbkey.utils.NumericLayoutOrder
import com.dessalines.thumbkey.utils.SlideType
import com.dessalines.thumbkey.utils.SuggestionBarFloatingPlacement
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.applyNumericLayoutOrder
import com.dessalines.thumbkey.utils.findNumericKeyForCell
import com.dessalines.thumbkey.utils.getAutoKeyWidth
import com.dessalines.thumbkey.utils.getCurrentLayoutGridColumns
import com.dessalines.thumbkey.utils.getImeActionCode
import com.dessalines.thumbkey.utils.getKeyboardMode
import com.dessalines.thumbkey.utils.getModifiedKeyboardDefinition
import com.dessalines.thumbkey.utils.imeActionDisplayIcon
import com.dessalines.thumbkey.utils.isLeftHandedLayout
import com.dessalines.thumbkey.utils.isPasswordInputType
import com.dessalines.thumbkey.utils.isUriOrEmailOrPasswordInputType
import com.dessalines.thumbkey.utils.keyboardPositionToAlignment
import com.dessalines.thumbkey.utils.resolveKeyboardDefinition
import com.dessalines.thumbkey.utils.toBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.collections.sortedBy
import kotlin.math.roundToInt
import kotlin.time.TimeMark

@Composable
fun KeyboardScreen(
    settings: AppSettings?,
    clipboardRepository: ClipboardRepository,
    onSwitchLanguage: () -> Unit,
    onCycleAutocorrectLanguage: () -> Unit = {},
    onChangePosition: ((old: KeyboardPosition) -> KeyboardPosition) -> Unit,
    onToggleHideLetters: () -> Unit,
    onFloatingMoved: (xFraction: Float, yFraction: Float) -> Unit,
    onFloatingBounds: (IntRect) -> Unit,
    onGoToClipboardSettings: () -> Unit,
) {
    val ctx = LocalContext.current as IMEService

    val layout =
        KeyboardLayout.entries.sortedBy { it.ordinal }[
            settings?.keyboardLayout
                ?: DEFAULT_KEYBOARD_LAYOUT,
        ]

    val keyMods = settings?.keyModifications
    val numericLayoutOrder =
        NumericLayoutOrder.entries.getOrElse(
            settings?.numericLayoutOrder ?: DEFAULT_NUMERIC_LAYOUT_ORDER,
        ) { NumericLayoutOrder.Default }
    val keyboardDefinition =
        remember(layout, keyMods, numericLayoutOrder) {
            applyNumericLayoutOrder(resolveKeyboardDefinition(layout, keyMods), numericLayoutOrder)
        }

    var mode by remember {
        val startMode =
            getKeyboardMode(
                ime = ctx,
                autoCapitalize = settings?.autoCapitalize?.toBool() == true && keyboardDefinition.settings.autoShift,
            )

        mutableStateOf(startMode)
    }

    var capsLock by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(ctx.inputSessionVersion) {
        mode =
            getKeyboardMode(
                ime = ctx,
                autoCapitalize = settings?.autoCapitalize?.toBool() == true && keyboardDefinition.settings.autoShift,
            )
        capsLock = false
    }

    // Sketch mode's drawing and candidates, kept while its search uses the letter keys, and
    // while the keyboard is closed
    val sketch = ctx.sketchState
    LaunchedEffect(mode) {
        val typingSearch = sketch.searching && mode != KeyboardMode.EMOJI && mode != KeyboardMode.CLIPBOARD
        // Coming back to sketch mode shows the pad, unless the search is being typed
        if (mode != KeyboardMode.SKETCH && !typingSearch) {
            sketch.searching = false
            sketch.list = null
        }
    }

    // TODO get rid of this crap
    val lastAction = remember { mutableStateOf<Pair<KeyAction, TimeMark>?>(null) }

    val clipboardItems by clipboardRepository.allClipboardItems.observeAsState(initial = emptyList())

    val keyboard =
        when (mode) {
            KeyboardMode.MAIN -> {
                keyboardDefinition.modes.main
            }

            KeyboardMode.SHIFTED -> {
                keyboardDefinition.modes.shifted
            }

            KeyboardMode.NUMERIC -> {
                keyboardDefinition.modes.numeric
            }

            KeyboardMode.CTRLED -> {
                keyboardDefinition.modes.ctrled ?: run {
                    val text = stringResource(R.string.warning_invalid_mode, mode, keyboardDefinition.title)
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, text)

                    mode = KeyboardMode.MAIN
                    keyboardDefinition.modes.main
                }
            }

            KeyboardMode.ALTED -> {
                keyboardDefinition.modes.alted ?: run {
                    val text = stringResource(R.string.warning_invalid_mode, mode, keyboardDefinition.title)
                    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, text)

                    mode = KeyboardMode.MAIN
                    keyboardDefinition.modes.main
                }
            }

            else -> {
                // Emoji and Clipboard modes use their own rendering, which does not depend on this value
                KB_EN_THUMBKEY_MAIN
            }
        }

    val position =
        KeyboardPosition.entries.getOrElse(
            settings?.position ?: DEFAULT_POSITION,
        ) { KeyboardPosition.Center }
    val floatingMode = position == KeyboardPosition.Floating

    val positionPadding = settings?.positionPadding ?: DEFAULT_POSITION_PADDING

    val pushupSizeDp = (settings?.pushupSize ?: DEFAULT_PUSHUP_SIZE).dp
    val ignoreBottomPadding = (settings?.ignoreBottomPadding ?: DEFAULT_IGNORE_BOTTOM_PADDING).toBool()

    val autoCapitalize = (settings?.autoCapitalize ?: DEFAULT_AUTO_CAPITALIZE).toBool()
    val spacebarMultiTaps = (settings?.spacebarMultiTaps ?: DEFAULT_SPACEBAR_MULTITAPS).toBool()
    val switchToLettersAfterSpace =
        (settings?.switchToLettersAfterSpace ?: DEFAULT_SWITCH_TO_LETTERS_AFTER_SPACE).toBool()
    val slideEnabled = (settings?.slideEnabled ?: DEFAULT_SLIDE_ENABLED).toBool()
    val slideCursorMovementMode = (settings?.slideCursorMovementMode ?: DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE)
    val slideSpacebarDeadzoneEnabled = (settings?.slideSpacebarDeadzoneEnabled ?: DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED).toBool()
    val slideBackspaceDeadzoneEnabled = (settings?.slideBackspaceDeadzoneEnabled ?: DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED).toBool()
    val keyBorderWidth = (settings?.keyBorderWidth ?: DEFAULT_KEY_BORDER_WIDTH)
    val vibrateOnTap = (settings?.vibrateOnTap ?: DEFAULT_VIBRATE_ON_TAP).toBool()
    val vibrateOnSlide = (settings?.vibrateOnSlide ?: DEFAULT_VIBRATE_ON_SLIDE).toBool()
    val soundOnTap = (settings?.soundOnTap ?: DEFAULT_SOUND_ON_TAP).toBool()
    val hideLetters = (settings?.hideLetters ?: DEFAULT_HIDE_LETTERS).toBool()
    val hideSymbols = (settings?.hideSymbols ?: DEFAULT_HIDE_SYMBOLS).toBool()
    val backdropEnabled = (settings?.backdropEnabled ?: DEFAULT_BACKDROP_ENABLED).toBool() && !floatingMode
    val backdropColor = MaterialTheme.colorScheme.background
    val backdropPadding = 6.dp
    val keyPadding = settings?.keyPadding ?: DEFAULT_KEY_PADDING
    val autoSizeKeys = (settings?.autoSizeKeys ?: DEFAULT_AUTO_SIZE_KEYS).toBool()
    val effectiveAutoSizeKeys = autoSizeKeys && !floatingMode
    val nonSquareKeys = (settings?.nonSquareKeys ?: DEFAULT_NON_SQUARE_KEYS).toBool()
    val keyboardLayoutIndex = settings?.keyboardLayout ?: DEFAULT_KEYBOARD_LAYOUT
    val legendWidth =
        if (effectiveAutoSizeKeys) {
            getAutoKeyWidth(keyboardLayoutIndex, keyPadding, position, ctx)
        } else {
            settings?.keyWidth ?: DEFAULT_KEY_WIDTH
        }
    val legendHeight =
        if (!nonSquareKeys) {
            legendWidth
        } else {
            settings?.keyHeight ?: DEFAULT_KEY_HEIGHT
        }
    val keyRadius = settings?.keyRadius ?: DEFAULT_KEY_RADIUS
    val dragReturnEnabled = (settings?.dragReturnEnabled ?: DEFAULT_DRAG_RETURN_ENABLED).toBool()
    val circularDragEnabled = (settings?.circularDragEnabled ?: DEFAULT_CIRCULAR_DRAG_ENABLED).toBool()
    val clockwiseDragAction = CircularDragAction.entries[settings?.clockwiseDragAction ?: DEFAULT_CLOCKWISE_DRAG_ACTION]
    val counterclockwiseDragAction =
        CircularDragAction.entries[settings?.counterclockwiseDragAction ?: DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION]
    val ghostKeysEnabled = (settings?.ghostKeysEnabled ?: DEFAULT_GHOST_KEYS_ENABLED).toBool()
    val slideHoldEnabled = (settings?.slideHoldEnabled ?: DEFAULT_SLIDE_HOLD_ENABLED).toBool()
    val holdKeyAction =
        HoldKeyAction.entries.getOrElse(settings?.holdKeyAction ?: DEFAULT_HOLD_KEY_ACTION) { HoldKeyAction.None }
    val backspaceHoldAction =
        BackspaceHoldAction.entries.getOrElse(
            settings?.backspaceHoldAction ?: DEFAULT_BACKSPACE_HOLD_ACTION,
        ) { BackspaceHoldAction.DeleteWord }
    val holdDurationMs =
        (settings?.holdDurationMs ?: DEFAULT_HOLD_DURATION_MS).coerceIn(MIN_HOLD_DURATION_MS, MAX_HOLD_DURATION_MS)
    val baseViewConfiguration = LocalViewConfiguration.current
    val keyboardViewConfiguration =
        remember(baseViewConfiguration, holdDurationMs) {
            CustomLongPressViewConfiguration(baseViewConfiguration, holdDurationMs.toLong())
        }

    val keyboardOpacity = (settings?.keyboardOpacity ?: DEFAULT_KEYBOARD_OPACITY).coerceIn(MIN_KEYBOARD_OPACITY, MAX_KEYBOARD_OPACITY)
    val floatingHandlePosition =
        FloatingHandlePosition.entries.getOrElse(
            settings?.floatingHandlePosition ?: DEFAULT_FLOATING_HANDLE_POSITION,
        ) { FloatingHandlePosition.Bottom }
    val floatingXFraction = settings?.floatingXFraction ?: DEFAULT_FLOATING_X_FRACTION
    val floatingYFraction = settings?.floatingYFraction ?: DEFAULT_FLOATING_Y_FRACTION
    val keyAnimationType =
        KeyAnimationType.entries.getOrElse(
            settings?.keyAnimationType ?: DEFAULT_KEY_ANIMATION_TYPE,
        ) { KeyAnimationType.SlideFromTop }
    val swipeTrailEnabled = (settings?.swipeTrailEnabled ?: DEFAULT_SWIPE_TRAIL_ENABLED).toBool()
    val isPassword = isPasswordInputType(ctx.editorInputType)
    val imeActionCode =
        remember(ctx.inputSessionVersion, ctx.editorImeOptions) {
            if (ctx.currentInputEditorInfo != null) getImeActionCode(ctx) else EditorInfo.IME_ACTION_NONE
        }
    val contextualReturnIcon = remember(imeActionCode) { imeActionDisplayIcon(imeActionCode) }
    val effectiveSwipeTrailEnabled = swipeTrailEnabled && !isPassword
    val swipeTrailState = rememberSwipeTrailState()

    val autocorrectEnabled = (settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool()
    val suggestionsEnabled = (settings?.suggestionsEnabled ?: DEFAULT_SUGGESTIONS_ENABLED).toBool()
    val floatingPlacement =
        SuggestionBarFloatingPlacement.entries.getOrElse(
            settings?.suggestionBarFloatingPlacement ?: DEFAULT_SUGGESTION_BAR_FLOATING_PLACEMENT,
        ) { SuggestionBarFloatingPlacement.AboveKeyboard }
    val isRestrictedField =
        remember(ctx.inputSessionVersion, ctx.editorInputType) {
            ctx.currentInputEditorInfo != null && isUriOrEmailOrPasswordInputType(ctx.editorInputType)
        }
    val activeAutocorrectLang =
        remember(layout, settings?.autocorrectAdditionalLanguages, settings?.autocorrectActiveLanguage) {
            LayoutLanguageResolver.resolveActiveLanguage(
                layout = layout,
                additionalLanguagesCsv =
                    settings?.autocorrectAdditionalLanguages ?: DEFAULT_AUTOCORRECT_ADDITIONAL_LANGUAGES,
                activeLanguage = settings?.autocorrectActiveLanguage ?: DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE,
            )
        }
    val spacebarLabel =
        remember(activeAutocorrectLang, autocorrectEnabled, suggestionsEnabled) {
            if (activeAutocorrectLang != null && (autocorrectEnabled || suggestionsEnabled)) {
                LayoutLanguageResolver.displayNameForSpacebar(activeAutocorrectLang)
            } else {
                null
            }
        }
    val spacebarShortLabel =
        remember(activeAutocorrectLang, spacebarLabel) {
            if (spacebarLabel != null && activeAutocorrectLang != null) {
                activeAutocorrectLang.uppercase(Locale.ROOT)
            } else {
                null
            }
        }

    LaunchedEffect(effectiveSwipeTrailEnabled, mode) {
        if (
            !effectiveSwipeTrailEnabled ||
            mode == KeyboardMode.EMOJI ||
            mode == KeyboardMode.CLIPBOARD ||
            mode == KeyboardMode.SKETCH
        ) {
            swipeTrailState.clear()
        }
    }

    val keyBorderWidthFloat = keyBorderWidth / 10.0f
    val keyBorderColour = MaterialTheme.colorScheme.outline
    val keyHeight = legendHeight.toFloat()
    val keyWidth = legendWidth.toFloat()

    val renderKeyboardContent = @Composable { effectiveKeyWidth: Float, effectiveKeyHeight: Float, isFloating: Boolean ->
        val effectiveLegendWidth = effectiveKeyWidth.toInt()
        val effectiveLegendHeight = effectiveKeyHeight.toInt()
        val cornerRadius = (keyRadius / 100.0f) * ((effectiveKeyWidth + effectiveKeyHeight) / 4.0f)
        val safeInsetsAndPushupModifier =
            if (isFloating) {
                Modifier
            } else {
                Modifier
                    .then(if (!ignoreBottomPadding) Modifier.safeDrawingPadding() else Modifier)
                    .padding(bottom = pushupSizeDp)
            }

        if (mode == KeyboardMode.EMOJI) {
            // Dynamically determine number of rows based on keyboard structure
            val rowCount = keyboardDefinition.modes.main.arr.size
            val controllerKeys =
                if (rowCount <= 3) {
                    listOf(EMOJI_BACK_KEY_ITEM, BACKSPACE_KEY_ITEM, RETURN_KEY_ITEM)
                } else {
                    listOf(EMOJI_BACK_KEY_ITEM, NUMERIC_KEY_ITEM, BACKSPACE_KEY_ITEM, RETURN_KEY_ITEM)
                }
            val keyboardHeight = Dp((effectiveKeyHeight * controllerKeys.size) - (keyPadding * 2))

            ctx.currentInputConnection.requestCursorUpdates(0)

            Box(
                modifier =
                    Modifier
                        .then(
                            if (backdropEnabled) {
                                Modifier.background(backdropColor)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                // adds a pretty line if you're using the backdrop
                if (backdropEnabled) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(color = MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                val emojiModeLayout = keyboardDefinition.modes.emoji
                if (emojiModeLayout != null) {
                    // Custom Emoji layout provides its own functional row at the bottom
                    Column(
                        modifier =
                            Modifier
                                .then(safeInsetsAndPushupModifier)
                                .fillMaxWidth()
                                .then(
                                    if (backdropEnabled) {
                                        Modifier.padding(top = backdropPadding)
                                    } else {
                                        Modifier
                                    },
                                ),
                    ) {
                        val emojiPickerHeight = Dp((effectiveKeyHeight * (emojiModeLayout.arr.size - 1)) - (keyPadding * 2))
                        Box(
                            modifier =
                                Modifier
                                    .padding(keyPadding.dp)
                                    .clip(RoundedCornerShape(cornerRadius.dp))
                                    .then(
                                        if (keyBorderWidthFloat > 0.0) {
                                            Modifier.border(
                                                keyBorderWidthFloat.dp,
                                                keyBorderColour,
                                                shape = RoundedCornerShape(cornerRadius.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ).background(MaterialTheme.colorScheme.surface),
                        ) {
                            val view = LocalView.current
                            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            AndroidView(
                                factory = { context ->
                                    val emojiPicker = EmojiPickerView(context)
                                    emojiPicker.setOnEmojiPickedListener {
                                        if (vibrateOnTap) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        }
                                        if (soundOnTap) {
                                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, .1f)
                                        }
                                        ctx.currentInputConnection.commitText(it.emoji, 1)
                                    }
                                    emojiPicker
                                },
                                modifier = Modifier.fillMaxWidth().height(emojiPickerHeight),
                            )
                        }
                        Row {
                            val bottomRow = emojiModeLayout.arr.last()
                            bottomRow.forEach { key ->
                                Column {
                                    KeyboardKey(
                                        key = withContextualReturnIcon(key, contextualReturnIcon),
                                        lastAction = lastAction,
                                        legendHeight = effectiveLegendHeight,
                                        legendWidth = effectiveLegendWidth,
                                        keyHeight = effectiveKeyHeight,
                                        keyWidth = effectiveKeyWidth,
                                        keyPadding = keyPadding,
                                        keyBorderWidth = keyBorderWidthFloat,
                                        keyRadius = cornerRadius,
                                        autoCapitalize = autoCapitalize,
                                        keyboardSettings = keyboardDefinition.settings,
                                        spacebarMultiTaps = spacebarMultiTaps,
                                        vibrateOnTap = vibrateOnTap,
                                        vibrateOnSlide = vibrateOnSlide,
                                        soundOnTap = soundOnTap,
                                        hideLetters = hideLetters,
                                        hideSymbols = hideSymbols,
                                        capsLock = capsLock,
                                        animationSpeed = settings?.animationSpeed ?: DEFAULT_ANIMATION_SPEED,
                                        animationHelperSpeed = settings?.animationHelperSpeed ?: DEFAULT_ANIMATION_HELPER_SPEED,
                                        keyAnimationType = keyAnimationType,
                                        minSwipeLength = settings?.minSwipeLength ?: DEFAULT_MIN_SWIPE_LENGTH,
                                        slideSensitivity = settings?.slideSensitivity ?: DEFAULT_SLIDE_SENSITIVITY,
                                        slideEnabled = slideEnabled,
                                        slideCursorMovementMode = slideCursorMovementMode,
                                        slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                                        slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                                        onToggleShiftMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.SHIFTED
                                                } else {
                                                    capsLock = false
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleCtrlMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.CTRLED
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleAltMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.ALTED
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleNumericMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.NUMERIC
                                                } else {
                                                    capsLock = false
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleEmojiMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.EMOJI
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleClipboardMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.CLIPBOARD
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleSketchMode = { enable ->
                                            // Not while typing sketch mode's own search
                                            if (!sketch.searching) {
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.SKETCH
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            }
                                        },
                                        onToggleCapsLock = {
                                            capsLock = !capsLock
                                            if (capsLock) {
                                                mode = KeyboardMode.SHIFTED
                                            }
                                        },
                                        onToggleHideLetters = onToggleHideLetters,
                                        onAutoCapitalize = { enable ->
                                            if (mode !== KeyboardMode.NUMERIC) {
                                                if (enable) {
                                                    mode = KeyboardMode.SHIFTED
                                                } else if (!capsLock) {
                                                    mode = KeyboardMode.MAIN
                                                }
                                            }
                                        },
                                        onSwitchLanguage = {
                                            onSwitchLanguage()
                                            mode = KeyboardMode.MAIN
                                        },
                                        onChangePosition = onChangePosition,
                                        onKeyEvent = { action ->
                                            when (mode) {
                                                KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                    if (action is KeyAction.SendEvent) {
                                                        mode = KeyboardMode.MAIN
                                                    }
                                                }

                                                KeyboardMode.NUMERIC -> {
                                                    if (
                                                        switchToLettersAfterSpace &&
                                                        action is KeyAction.CommitText &&
                                                        action.text == " "
                                                    ) {
                                                        capsLock = false
                                                        mode = KeyboardMode.MAIN
                                                    }
                                                }

                                                else -> {}
                                            }
                                        },
                                        dragReturnEnabled = dragReturnEnabled,
                                        circularDragEnabled = circularDragEnabled,
                                        clockwiseDragAction = clockwiseDragAction,
                                        counterclockwiseDragAction = counterclockwiseDragAction,
                                        slideHoldEnabled = slideHoldEnabled,
                                        backspaceHoldAction = backspaceHoldAction,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier =
                            Modifier
                                .then(safeInsetsAndPushupModifier)
                                .fillMaxWidth()
                                .then(
                                    if (backdropEnabled) {
                                        Modifier.padding(top = backdropPadding)
                                    } else {
                                        Modifier
                                    },
                                ),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f) // Take up available space equally
                                    .padding(keyPadding.dp)
                                    .clip(RoundedCornerShape(cornerRadius.dp))
                                    .then(
                                        if (keyBorderWidthFloat > 0.0) {
                                            Modifier.border(
                                                keyBorderWidthFloat.dp,
                                                keyBorderColour,
                                                shape = RoundedCornerShape(cornerRadius.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ).background(MaterialTheme.colorScheme.surface),
                        ) {
                            val view = LocalView.current
                            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            AndroidView(
                                // Write the emoji to our text box when we tap one.
                                factory = { context ->
                                    val emojiPicker = EmojiPickerView(context)
                                    emojiPicker.setOnEmojiPickedListener {
                                        if (vibrateOnTap) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        }
                                        if (soundOnTap) {
                                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, .1f)
                                        }
                                        ctx.currentInputConnection.commitText(
                                            it.emoji,
                                            1,
                                        )
                                    }
                                    emojiPicker
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(keyboardHeight),
                            )
                        }
                        Column {
                            controllerKeys.forEach { key ->
                                Column {
                                    KeyboardKey(
                                        key = withContextualReturnIcon(key, contextualReturnIcon),
                                        lastAction = lastAction,
                                        legendHeight = effectiveLegendHeight,
                                        legendWidth = effectiveLegendWidth,
                                        keyHeight = effectiveKeyHeight,
                                        keyWidth = effectiveKeyWidth,
                                        keyPadding = keyPadding,
                                        keyBorderWidth = keyBorderWidthFloat,
                                        keyRadius = cornerRadius,
                                        autoCapitalize = autoCapitalize,
                                        keyboardSettings = keyboardDefinition.settings,
                                        spacebarMultiTaps = spacebarMultiTaps,
                                        vibrateOnTap = vibrateOnTap,
                                        vibrateOnSlide = vibrateOnSlide,
                                        soundOnTap = soundOnTap,
                                        hideLetters = hideLetters,
                                        hideSymbols = hideSymbols,
                                        capsLock = capsLock,
                                        animationSpeed =
                                            settings?.animationSpeed
                                                ?: DEFAULT_ANIMATION_SPEED,
                                        animationHelperSpeed =
                                            settings?.animationHelperSpeed
                                                ?: DEFAULT_ANIMATION_HELPER_SPEED,
                                        keyAnimationType = keyAnimationType,
                                        minSwipeLength = settings?.minSwipeLength ?: DEFAULT_MIN_SWIPE_LENGTH,
                                        slideSensitivity = settings?.slideSensitivity ?: DEFAULT_SLIDE_SENSITIVITY,
                                        slideEnabled = slideEnabled,
                                        slideCursorMovementMode = slideCursorMovementMode,
                                        slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                                        slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                                        onToggleShiftMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.SHIFTED
                                                } else {
                                                    capsLock = false
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleCtrlMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.CTRLED
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleAltMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.ALTED
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleNumericMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.NUMERIC
                                                } else {
                                                    capsLock = false
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleEmojiMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.EMOJI
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleClipboardMode = { enable ->
                                            mode =
                                                if (enable) {
                                                    KeyboardMode.CLIPBOARD
                                                } else {
                                                    KeyboardMode.MAIN
                                                }
                                        },
                                        onToggleSketchMode = { enable ->
                                            // Not while typing sketch mode's own search
                                            if (!sketch.searching) {
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.SKETCH
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            }
                                        },
                                        onToggleCapsLock = {
                                            capsLock = !capsLock

                                            // Change to shifted mode in case it isn't already shifted
                                            if (capsLock) {
                                                mode = KeyboardMode.SHIFTED
                                            }
                                        },
                                        onToggleHideLetters = onToggleHideLetters,
                                        onAutoCapitalize = { enable ->
                                            if (mode !== KeyboardMode.NUMERIC) {
                                                if (enable) {
                                                    mode = KeyboardMode.SHIFTED
                                                } else if (!capsLock) {
                                                    mode = KeyboardMode.MAIN
                                                }
                                            }
                                        },
                                        onSwitchLanguage = {
                                            onSwitchLanguage()
                                            mode = KeyboardMode.MAIN
                                        },
                                        onChangePosition = onChangePosition,
                                        onKeyEvent = { action ->
                                            when (mode) {
                                                KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                    if (action is KeyAction.SendEvent) {
                                                        mode = KeyboardMode.MAIN
                                                    }
                                                }

                                                KeyboardMode.NUMERIC -> {
                                                    if (
                                                        switchToLettersAfterSpace &&
                                                        action is KeyAction.CommitText &&
                                                        action.text == " "
                                                    ) {
                                                        capsLock = false
                                                        mode = KeyboardMode.MAIN
                                                    }
                                                }

                                                else -> {}
                                            }
                                        },
                                        dragReturnEnabled = dragReturnEnabled,
                                        circularDragEnabled = circularDragEnabled,
                                        clockwiseDragAction = clockwiseDragAction,
                                        counterclockwiseDragAction = counterclockwiseDragAction,
                                        slideHoldEnabled = slideHoldEnabled,
                                        backspaceHoldAction = backspaceHoldAction,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else if (mode == KeyboardMode.CLIPBOARD) {
            // Clipboard history view
            val scope = CoroutineScope(Dispatchers.IO)
            val clipboardHistoryEnabled =
                (settings?.clipboardHistoryEnabled ?: DEFAULT_CLIPBOARD_HISTORY_ENABLED).toBool()

            // Calculate keyboard height based on number of rows
            val rowCount = keyboardDefinition.modes.main.arr.size
            val keyboardHeight = Dp(effectiveKeyHeight * rowCount)

            // Perform auto-cleanup when entering clipboard view, and clear all if disabled
            LaunchedEffect(Unit) {
                if (clipboardHistoryEnabled) {
                    clipboardRepository.clearExpired()
                } else {
                    clipboardRepository.clearAll()
                }
            }

            Box(
                modifier =
                    Modifier
                        .then(
                            if (backdropEnabled) {
                                Modifier.background(backdropColor)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                // adds a pretty line if you're using the backdrop
                if (backdropEnabled) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(color = MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .then(safeInsetsAndPushupModifier)
                            .fillMaxWidth()
                            .height(keyboardHeight)
                            .then(
                                if (backdropEnabled) {
                                    Modifier.padding(top = backdropPadding)
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    ClipboardHistoryScreen(
                        clipboardItems = clipboardItems,
                        isEnabled = clipboardHistoryEnabled,
                        onItemClick = { item ->
                            // Paste and return to keyboard
                            ctx.currentInputConnection.commitText(item.text, 1)
                            mode = KeyboardMode.MAIN
                        },
                        onItemPaste = { item ->
                            // Paste WITHOUT returning to keyboard
                            ctx.currentInputConnection.commitText(item.text, 1)
                        },
                        onItemDelete = { item ->
                            scope.launch {
                                clipboardRepository.deleteItem(item)
                            }
                        },
                        onItemTogglePin = { item ->
                            scope.launch {
                                clipboardRepository.togglePin(item)
                            }
                        },
                        onBack = {
                            mode = KeyboardMode.MAIN
                        },
                        onClearAll = {
                            scope.launch {
                                clipboardRepository.clearUnpinned()
                            }
                        },
                        onGoToClipboardSettings = onGoToClipboardSettings,
                        keyHeight = effectiveKeyHeight,
                        keyPadding = keyPadding,
                        cornerRadius = cornerRadius,
                        vibrateOnTap = vibrateOnTap,
                    )
                }
            }
        } else if (mode == KeyboardMode.SKETCH) {
            val rowCount = keyboardDefinition.modes.main.arr.size

            Box(
                modifier =
                    Modifier
                        .then(
                            if (backdropEnabled) {
                                Modifier.background(backdropColor)
                            } else {
                                Modifier
                            },
                        ),
            ) {
                // adds a pretty line if you're using the backdrop
                if (backdropEnabled) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(color = MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                Box(
                    contentAlignment = if (isFloating) Alignment.BottomStart else keyboardPositionToAlignment(position),
                    modifier =
                        Modifier
                            .then(safeInsetsAndPushupModifier)
                            .fillMaxWidth()
                            .then(
                                if (backdropEnabled) {
                                    Modifier.padding(top = backdropPadding)
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    SketchScreen(
                        state = sketch,
                        rowCount = rowCount,
                        leftHanded = isLeftHandedLayout(layout.name),
                        keyWidth = effectiveKeyWidth,
                        keyHeight = effectiveKeyHeight,
                        keyPadding = keyPadding,
                        keyBorderWidth = keyBorderWidthFloat,
                        cornerRadius = cornerRadius,
                        vibrateOnTap = vibrateOnTap,
                        soundOnTap = soundOnTap,
                        onCommit = { text ->
                            // Straight to the text field: the layout's text processor and
                            // auto-capitalization don't apply to a drawn character.
                            ctx.ignoreNextCursorMove()
                            ctx.appInputConnection.commitText(text, 1)
                        },
                        onSearch = {
                            sketch.searching = true
                            mode = KeyboardMode.MAIN
                        },
                    ) { key, onSketchAction ->
                        KeyboardKey(
                            key = withContextualReturnIcon(key, contextualReturnIcon),
                            lastAction = lastAction,
                            legendHeight = effectiveLegendHeight,
                            legendWidth = effectiveLegendWidth,
                            keyHeight = effectiveKeyHeight,
                            keyWidth = effectiveKeyWidth,
                            keyPadding = keyPadding,
                            keyBorderWidth = keyBorderWidthFloat,
                            keyRadius = cornerRadius,
                            autoCapitalize = autoCapitalize,
                            keyboardSettings = keyboardDefinition.settings,
                            spacebarMultiTaps = spacebarMultiTaps,
                            vibrateOnTap = vibrateOnTap,
                            vibrateOnSlide = vibrateOnSlide,
                            soundOnTap = soundOnTap,
                            // Candidates are always shown
                            hideLetters = false,
                            hideSymbols = false,
                            capsLock = false,
                            animationSpeed = settings?.animationSpeed ?: DEFAULT_ANIMATION_SPEED,
                            animationHelperSpeed = settings?.animationHelperSpeed ?: DEFAULT_ANIMATION_HELPER_SPEED,
                            keyAnimationType = keyAnimationType,
                            minSwipeLength = settings?.minSwipeLength ?: DEFAULT_MIN_SWIPE_LENGTH,
                            slideSensitivity = settings?.slideSensitivity ?: DEFAULT_SLIDE_SENSITIVITY,
                            slideEnabled = slideEnabled,
                            slideCursorMovementMode = slideCursorMovementMode,
                            slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                            slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                            onToggleShiftMode = {},
                            onToggleCtrlMode = {},
                            onToggleAltMode = {},
                            onToggleNumericMode = {},
                            onToggleEmojiMode = {},
                            onToggleClipboardMode = {},
                            onToggleSketchMode = { enable ->
                                if (!enable) mode = KeyboardMode.MAIN
                            },
                            onToggleCapsLock = {},
                            onToggleHideLetters = onToggleHideLetters,
                            // Stay in sketch mode after typing a space
                            onAutoCapitalize = {},
                            onSwitchLanguage = {
                                onSwitchLanguage()
                                mode = KeyboardMode.MAIN
                            },
                            onChangePosition = onChangePosition,
                            onKeyEvent = {},
                            dragReturnEnabled = dragReturnEnabled,
                            circularDragEnabled = circularDragEnabled,
                            clockwiseDragAction = clockwiseDragAction,
                            counterclockwiseDragAction = counterclockwiseDragAction,
                            slideHoldEnabled = slideHoldEnabled,
                            backspaceHoldAction = backspaceHoldAction,
                            onSketchAction = onSketchAction,
                        )
                    }
                }
            }
        } else {
            // NOTE, this should use or CURSOR_UPDATE_FILTER_INSERTION_MARKER , but it doesn't work on
            // non-compose textfields.
            // This also requires jetpack compose >= 1.6
            // See https://github.com/dessalines/thumb-key/issues/242
            if (ctx.currentInputConnection.requestCursorUpdates(CURSOR_UPDATE_MONITOR)) {
                Log.d(TAG, "request for cursor updates succeeded, cursor updates will be provided")
            } else {
                Log.d(TAG, "request for cursor updates failed, cursor updates will not be provided")
            }

            val drawKeyboard = @Composable { alignment: Alignment, drawBackdrop: Boolean, posPadding: Int ->
                val modifierPositionPadding =
                    if (posPadding > 0) {
                        Modifier.padding(start = posPadding.dp)
                    } else {
                        Modifier.padding(end = -posPadding.dp)
                    }
                Box(
                    contentAlignment = alignment,
                    modifier =
                        Modifier
                            .then(if (!isFloating) Modifier.fillMaxWidth() else Modifier)
                            .then(if (drawBackdrop) Modifier.background(backdropColor) else (Modifier))
                            .then(safeInsetsAndPushupModifier)
                            .then(
                                if (backdropEnabled) {
                                    Modifier.padding(top = backdropPadding)
                                } else {
                                    Modifier
                                },
                            ),
                ) {
                    // adds a pretty line if you're using the backdrop
                    if (drawBackdrop) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(color = MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    Column(
                        modifier = modifierPositionPadding.captureSwipeTrail(swipeTrailState, effectiveSwipeTrailEnabled),
                    ) {
                        keyboard.arr.forEachIndexed { i, row ->
                            Row {
                                row.forEachIndexed { j, key ->
                                    Column {
                                        val ghostKey =
                                            if (ghostKeysEnabled) {
                                                when (mode) {
                                                    KeyboardMode.MAIN, KeyboardMode.SHIFTED, KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                        keyboardDefinition.modes.numeric
                                                    }

                                                    else -> {
                                                        null
                                                    }
                                                }?.arr?.getOrNull(i)?.getOrNull(j)
                                            } else {
                                                null
                                            }
                                        KeyboardKey(
                                            key = withContextualReturnIcon(key, contextualReturnIcon),
                                            ghostKey = ghostKey,
                                            lastAction = lastAction,
                                            legendHeight = effectiveLegendHeight,
                                            legendWidth = effectiveLegendWidth,
                                            keyHeight = effectiveKeyHeight,
                                            keyWidth = effectiveKeyWidth,
                                            keyPadding = keyPadding,
                                            keyBorderWidth = keyBorderWidthFloat,
                                            keyRadius = cornerRadius,
                                            autoCapitalize = autoCapitalize,
                                            keyboardSettings = keyboardDefinition.settings,
                                            spacebarMultiTaps = spacebarMultiTaps,
                                            vibrateOnTap = vibrateOnTap,
                                            vibrateOnSlide = vibrateOnSlide,
                                            soundOnTap = soundOnTap,
                                            hideLetters = hideLetters,
                                            hideSymbols = hideSymbols,
                                            capsLock = capsLock,
                                            animationSpeed =
                                                settings?.animationSpeed
                                                    ?: DEFAULT_ANIMATION_SPEED,
                                            animationHelperSpeed =
                                                settings?.animationHelperSpeed
                                                    ?: DEFAULT_ANIMATION_HELPER_SPEED,
                                            keyAnimationType = keyAnimationType,
                                            minSwipeLength =
                                                settings?.minSwipeLength
                                                    ?: DEFAULT_MIN_SWIPE_LENGTH,
                                            slideSensitivity =
                                                settings?.slideSensitivity
                                                    ?: DEFAULT_SLIDE_SENSITIVITY,
                                            slideEnabled = slideEnabled,
                                            slideCursorMovementMode = slideCursorMovementMode,
                                            slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabled,
                                            slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabled,
                                            onToggleShiftMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.SHIFTED
                                                    } else {
                                                        capsLock = false
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleCtrlMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.CTRLED
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleAltMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.ALTED
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleNumericMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.NUMERIC
                                                    } else {
                                                        capsLock = false
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleEmojiMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.EMOJI
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleClipboardMode = { enable ->
                                                mode =
                                                    if (enable) {
                                                        KeyboardMode.CLIPBOARD
                                                    } else {
                                                        KeyboardMode.MAIN
                                                    }
                                            },
                                            onToggleSketchMode = { enable ->
                                                // Not while typing sketch mode's own search
                                                if (!sketch.searching) {
                                                    mode =
                                                        if (enable) {
                                                            KeyboardMode.SKETCH
                                                        } else {
                                                            KeyboardMode.MAIN
                                                        }
                                                }
                                            },
                                            onToggleCapsLock = {
                                                capsLock = !capsLock

                                                // Change to shifted mode in case it isn't already shifted
                                                if (capsLock) {
                                                    mode = KeyboardMode.SHIFTED
                                                }
                                            },
                                            onToggleHideLetters = onToggleHideLetters,
                                            onKeyEvent = { action ->
                                                when (mode) {
                                                    KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                        if (action is KeyAction.SendEvent) {
                                                            mode = KeyboardMode.MAIN
                                                        }
                                                    }

                                                    KeyboardMode.NUMERIC -> {
                                                        if (
                                                            switchToLettersAfterSpace &&
                                                            action is KeyAction.CommitText &&
                                                            action.text == " "
                                                        ) {
                                                            capsLock = false
                                                            mode = KeyboardMode.MAIN
                                                        }
                                                    }

                                                    else -> {}
                                                }
                                            },
                                            onAutoCapitalize = { enable ->
                                                if (mode !== KeyboardMode.NUMERIC) {
                                                    if (enable) {
                                                        mode = KeyboardMode.SHIFTED
                                                    } else if (!capsLock) {
                                                        mode = KeyboardMode.MAIN
                                                    }
                                                }
                                            },
                                            onSwitchLanguage = {
                                                onSwitchLanguage()
                                                mode = KeyboardMode.MAIN
                                            },
                                            onCycleAutocorrectLanguage = onCycleAutocorrectLanguage,
                                            onChangePosition = onChangePosition,
                                            oppositeCaseKey =
                                                when (mode) {
                                                    KeyboardMode.MAIN -> keyboardDefinition.modes.shifted
                                                    KeyboardMode.SHIFTED -> keyboardDefinition.modes.main
                                                    else -> null
                                                }?.arr?.getOrNull(i)?.getOrNull(j),
                                            numericKey =
                                                when (mode) {
                                                    KeyboardMode.MAIN, KeyboardMode.SHIFTED, KeyboardMode.CTRLED, KeyboardMode.ALTED -> {
                                                        findNumericKeyForCell(
                                                            row,
                                                            j,
                                                            keyboardDefinition.modes.numeric.arr
                                                                .getOrNull(i),
                                                        )
                                                    }

                                                    else -> {
                                                        null
                                                    }
                                                },
                                            dragReturnEnabled = dragReturnEnabled,
                                            circularDragEnabled = circularDragEnabled,
                                            clockwiseDragAction = clockwiseDragAction,
                                            counterclockwiseDragAction = counterclockwiseDragAction,
                                            slideHoldEnabled = slideHoldEnabled,
                                            holdKeyAction = holdKeyAction,
                                            backspaceHoldAction = backspaceHoldAction,
                                            spacebarLabel = spacebarLabel,
                                            spacebarShortLabel = spacebarShortLabel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val drawKeyboards = @Composable {
                if (isFloating) {
                    drawKeyboard(Alignment.BottomStart, false, 0)
                } else {
                    drawKeyboard(keyboardPositionToAlignment(position), backdropEnabled, positionPadding)
                    if (position == KeyboardPosition.Dual) {
                        drawKeyboard(keyboardPositionToAlignment(KeyboardPosition.Right), false, positionPadding)
                    }
                }
            }

            if (sketch.searching) {
                // Sketch mode's search, typed with the layout's keys below it
                Column {
                    SketchSearch(
                        state = sketch,
                        height = Dp(effectiveKeyHeight * 2),
                        vibrateOnTap = vibrateOnTap,
                        soundOnTap = soundOnTap,
                        onPick = { text ->
                            // Stop typing into the search first, so the character goes to the app
                            sketch.searching = false
                            sketch.list = null
                            ctx.setInputRedirect(null)
                            ctx.ignoreNextCursorMove()
                            ctx.appInputConnection.commitText(text, 1)
                            mode = KeyboardMode.SKETCH
                        },
                        onClose = {
                            sketch.searching = false
                            mode = KeyboardMode.SKETCH
                        },
                    )
                    Box { drawKeyboards() }
                }
            } else {
                drawKeyboards()
            }
        }
    }

    val showSuggestionBar =
        shouldShowSuggestionBar(
            inlineSuggestionViewsCount = ctx.inlineSuggestionViews.size,
            suggestionsEnabled = suggestionsEnabled,
            isRestrictedField = isRestrictedField,
            isPanelOpen = mode == KeyboardMode.EMOJI || mode == KeyboardMode.CLIPBOARD || sketch.searching,
            hasActiveLanguages = activeAutocorrectLang != null,
        )
    val suggestionStripState = ctx.autocorrectSession?.stripState ?: AutocorrectSession.SuggestionStripState()
    val suggestionBarContent = @Composable { barModifier: Modifier, dividerAtBottom: Boolean ->
        SuggestionBar(
            stripState = suggestionStripState,
            inlineSuggestionViews = ctx.inlineSuggestionViews,
            vibrateOnTap = vibrateOnTap,
            onUndoClick = { ctx.autocorrectSession?.applyUndoChip(ctx) },
            onSuggestionClick = { suggestion ->
                ctx.autocorrectSession?.applySuggestion(ctx, suggestion) {
                    if (!capsLock && mode == KeyboardMode.SHIFTED) {
                        mode = KeyboardMode.MAIN
                    }
                }
            },
            onEmojiClick = { emoji -> ctx.autocorrectSession?.applyEmojiSuggestion(ctx, emoji) },
            modifier = barModifier,
            dividerAtBottom = dividerAtBottom,
        )
    }

    CompositionLocalProvider(LocalViewConfiguration provides keyboardViewConfiguration) {
        if (floatingMode) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
            ) {
                FloatingKeyboardFrame(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    applySafeDrawingPadding = false,
                    keyWidth = keyWidth,
                    keyHeight = keyHeight,
                    columns = getCurrentLayoutGridColumns(keyboardLayoutIndex),
                    keyboardOpacity = keyboardOpacity,
                    handlePosition = floatingHandlePosition,
                    floatingXFraction = floatingXFraction,
                    floatingYFraction = floatingYFraction,
                    vibrateOnTap = vibrateOnTap,
                    onMoved = onFloatingMoved,
                    onBounds = onFloatingBounds,
                    topBar =
                        if (showSuggestionBar && floatingPlacement == SuggestionBarFloatingPlacement.AboveKeyboard) {
                            { suggestionBarContent(Modifier.fillMaxWidth(), true) }
                        } else {
                            null
                        },
                    bottomBar =
                        if (showSuggestionBar && floatingPlacement == SuggestionBarFloatingPlacement.BelowKeyboard) {
                            { suggestionBarContent(Modifier.fillMaxWidth(), false) }
                        } else {
                            null
                        },
                    overlay = {
                        if (effectiveSwipeTrailEnabled) {
                            SwipeTrailOverlay(
                                state = swipeTrailState,
                                modifier = Modifier.matchParentSize(),
                            )
                        }
                        CaretMagnifierHost(
                            state = ctx.caretMagnifier.state,
                            isFloating = true,
                        )
                    },
                ) { floatingKeyWidth, floatingKeyHeight ->
                    renderKeyboardContent(floatingKeyWidth, floatingKeyHeight, true)
                }
                if (showSuggestionBar && floatingPlacement == SuggestionBarFloatingPlacement.DockedBottom) {
                    DisposableEffect(Unit) {
                        onDispose { ctx.onDockedBarBounds(null) }
                    }
                    suggestionBarContent(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                ctx.onDockedBarBounds(
                                    IntRect(
                                        offset = IntOffset(pos.x.roundToInt(), pos.y.roundToInt()),
                                        size = coords.size,
                                    ),
                                )
                            },
                        false,
                    )
                }
            }
        } else {
            val dockedOpacityModifier =
                if (keyboardOpacity < MAX_KEYBOARD_OPACITY) {
                    Modifier.graphicsLayer {
                        alpha = (keyboardOpacity / 100f).coerceIn(MIN_KEYBOARD_OPACITY / 100f, 1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                } else {
                    Modifier
                }
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showSuggestionBar) {
                    suggestionBarContent(Modifier.fillMaxWidth(), true)
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .systemGestureExclusion(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(dockedOpacityModifier),
                    ) {
                        renderKeyboardContent(keyWidth, keyHeight, false)
                    }
                    if (effectiveSwipeTrailEnabled) {
                        SwipeTrailOverlay(
                            state = swipeTrailState,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    CaretMagnifierHost(
                        state = ctx.caretMagnifier.state,
                        isFloating = false,
                    )
                }
            }
        }
    }
}

private fun withContextualReturnIcon(
    key: KeyItemC,
    icon: ImageVector?,
): KeyItemC =
    if (icon != null && key.center.action == KeyAction.IMECompleteAction) {
        key.copy(center = key.center.copy(display = KeyDisplay.IconDisplay(icon)))
    } else {
        key
    }

private class CustomLongPressViewConfiguration(
    private val base: ViewConfiguration,
    override val longPressTimeoutMillis: Long,
) : ViewConfiguration by base {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CustomLongPressViewConfiguration &&
                    base == other.base &&
                    longPressTimeoutMillis == other.longPressTimeoutMillis
            )

    override fun hashCode(): Int = 31 * base.hashCode() + longPressTimeoutMillis.hashCode()
}
