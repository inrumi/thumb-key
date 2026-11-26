package com.dessalines.thumbkey.ui.components.keyboard
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.utils.BackspaceHoldAction
import com.dessalines.thumbkey.utils.CircularDirection
import com.dessalines.thumbkey.utils.CircularDragAction
import com.dessalines.thumbkey.utils.ColorVariant
import com.dessalines.thumbkey.utils.FontSizeVariant
import com.dessalines.thumbkey.utils.HoldKeyAction
import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyAnimationType
import com.dessalines.thumbkey.utils.KeyC
import com.dessalines.thumbkey.utils.KeyDisplay
import com.dessalines.thumbkey.utils.KeyItemC
import com.dessalines.thumbkey.utils.KeyboardDefinitionSettings
import com.dessalines.thumbkey.utils.KeyboardPosition
import com.dessalines.thumbkey.utils.ReleasedKeyFeedback
import com.dessalines.thumbkey.utils.Selection
import com.dessalines.thumbkey.utils.SlideType
import com.dessalines.thumbkey.utils.SwipeDirection
import com.dessalines.thumbkey.utils.SwipeNWay
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.buildTapActions
import com.dessalines.thumbkey.utils.circularDirection
import com.dessalines.thumbkey.utils.colorVariantToColor
import com.dessalines.thumbkey.utils.deleteWordBeforeCursor
import com.dessalines.thumbkey.utils.doneKeyAction
import com.dessalines.thumbkey.utils.fontSizeVariantToFontSize
import com.dessalines.thumbkey.utils.isBackspaceRepeatHold
import com.dessalines.thumbkey.utils.isPasswordInputType
import com.dessalines.thumbkey.utils.isVerticalSlideDominant
import com.dessalines.thumbkey.utils.moveCursorVertically
import com.dessalines.thumbkey.utils.nextWordAfterCursor
import com.dessalines.thumbkey.utils.performKeyAction
import com.dessalines.thumbkey.utils.previousWordBeforeCursor
import com.dessalines.thumbkey.utils.pxToSp
import com.dessalines.thumbkey.utils.resolveHoldKeyAction
import com.dessalines.thumbkey.utils.sendDownUpKeyEvent
import com.dessalines.thumbkey.utils.slideCursorDistance
import com.dessalines.thumbkey.utils.startSelection
import com.dessalines.thumbkey.utils.swipeDirection
import com.dessalines.thumbkey.utils.toPx
import com.dessalines.thumbkey.utils.verticalSlideStepDp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

// magic number found from trial and error
private const val DRAG_RETURN_THRESHOLD_FACTOR = 0.71f

private const val SLIDE_HOLD_SLOW_TICK_THRESHOLD = 4 // number of ticks at the slow rate
private const val SLIDE_HOLD_SLOW_TICK_DELAY_MS = 300L // ms between ticks during slow phase
private const val SLIDE_HOLD_FAST_TICK_DELAY_MS = 100L // ms between ticks during fast phase

private const val HOLD_REPEAT_SLOW_TICK_THRESHOLD = 3
private const val HOLD_REPEAT_SLOW_TICK_DELAY_MS = 110L
private const val HOLD_REPEAT_FAST_TICK_DELAY_MS = 50L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KeyboardKey(
    key: KeyItemC,
    // Hidden background key to detect swipes for. When a swipe isn't captured by the key, the ghost
    // key will attempt to capture it instead. This is derived automatically from the keyboard
    // layout, and should not be set directly in the keyboard definition.
    ghostKey: KeyItemC? = null,
    lastAction: MutableState<Pair<KeyAction, TimeMark>?>,
    animationHelperSpeed: Int,
    animationSpeed: Int,
    keyAnimationType: KeyAnimationType = KeyAnimationType.SlideFromTop,
    autoCapitalize: Boolean,
    keyboardSettings: KeyboardDefinitionSettings,
    spacebarMultiTaps: Boolean,
    vibrateOnTap: Boolean,
    vibrateOnSlide: Boolean,
    soundOnTap: Boolean,
    hideLetters: Boolean,
    hideSymbols: Boolean,
    capsLock: Boolean,
    legendHeight: Int,
    legendWidth: Int,
    keyPadding: Int,
    keyHeight: Float,
    keyWidth: Float,
    keyBorderWidth: Float,
    keyRadius: Float,
    minSwipeLength: Int,
    slideSensitivity: Int,
    slideEnabled: Boolean,
    slideCursorMovementMode: Int,
    slideSpacebarDeadzoneEnabled: Boolean,
    slideBackspaceDeadzoneEnabled: Boolean,
    onToggleShiftMode: (enable: Boolean) -> Unit,
    onToggleCtrlMode: (enable: Boolean) -> Unit,
    onToggleAltMode: (enable: Boolean) -> Unit,
    onToggleNumericMode: (enable: Boolean) -> Unit,
    onToggleEmojiMode: (enable: Boolean) -> Unit,
    onToggleClipboardMode: (enable: Boolean) -> Unit,
    onToggleSketchMode: (enable: Boolean) -> Unit,
    onToggleCapsLock: () -> Unit,
    onToggleHideLetters: () -> Unit,
    onAutoCapitalize: (enable: Boolean) -> Unit,
    onSwitchLanguage: () -> Unit,
    onCycleAutocorrectLanguage: () -> Unit = {},
    onChangePosition: ((old: KeyboardPosition) -> KeyboardPosition) -> Unit,
    onKeyEvent: (action: KeyAction) -> Unit,
    oppositeCaseKey: KeyItemC? = null,
    numericKey: KeyItemC? = null,
    dragReturnEnabled: Boolean,
    circularDragEnabled: Boolean,
    clockwiseDragAction: CircularDragAction,
    counterclockwiseDragAction: CircularDragAction,
    slideHoldEnabled: Boolean,
    holdKeyAction: HoldKeyAction = HoldKeyAction.None,
    backspaceHoldAction: BackspaceHoldAction = BackspaceHoldAction.DeleteWord,
    spacebarLabel: String? = null,
    spacebarShortLabel: String? = null,
    onSketchAction: (action: KeyAction.Sketch) -> Unit = {},
) {
    val ctx = LocalContext.current
    val ime = ctx as IMEService
    val scope = rememberCoroutineScope()

    val pointerInputKey =
        remember(key, ghostKey, oppositeCaseKey, numericKey, keyboardSettings) {
            KeyPointerInputKey(key, ghostKey, oppositeCaseKey, numericKey, keyboardSettings)
        }

    // Don't show animations for password fields
    val isPasswordField = isPasswordInputType(ime.editorInputType)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isDragged = remember { mutableStateOf(false) }
    val releasedFeedback = remember { mutableStateOf<ReleasedKeyFeedback?>(null) }
    LaunchedEffect(isPasswordField) {
        if (isPasswordField) releasedFeedback.value = null
    }
    var activeDragDirection by remember { mutableStateOf<SwipeDirection?>(null) }
    var activeDragReturn by remember { mutableStateOf(false) }

    val currentMinSwipeLength by rememberUpdatedState(minSwipeLength)
    val currentSlideSensitivity by rememberUpdatedState(slideSensitivity)
    val currentSlideEnabled by rememberUpdatedState(slideEnabled)
    val currentSlideCursorMovementMode by rememberUpdatedState(slideCursorMovementMode)
    val currentSlideSpacebarDeadzoneEnabled by rememberUpdatedState(slideSpacebarDeadzoneEnabled)
    val currentSlideBackspaceDeadzoneEnabled by rememberUpdatedState(slideBackspaceDeadzoneEnabled)
    val currentDragReturnEnabled by rememberUpdatedState(dragReturnEnabled)
    val currentCircularDragEnabled by rememberUpdatedState(circularDragEnabled)
    val currentClockwiseDragAction by rememberUpdatedState(clockwiseDragAction)
    val currentCounterclockwiseDragAction by rememberUpdatedState(counterclockwiseDragAction)
    val currentSlideHoldEnabled by rememberUpdatedState(slideHoldEnabled)
    val currentVibrateOnSlide by rememberUpdatedState(vibrateOnSlide)
    val currentVibrateOnTap by rememberUpdatedState(vibrateOnTap)

    var tapCount by remember { mutableIntStateOf(0) }
    val tapActions =
        if (spacebarMultiTaps) {
            buildTapActions(key)
        } else {
            listOf(key.center.action)
        }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var verticalAccumY by remember { mutableFloatStateOf(0f) }
    var hasSlideMoveCursorTriggered by remember { mutableStateOf(false) }
    var spacebarMagnifierActive by remember { mutableStateOf(false) }
    var timeOfLastAccelerationInput by remember { mutableLongStateOf(0L) }
    var positions by remember { mutableStateOf(listOf<Offset>()) }
    var maxOffset by remember { mutableStateOf(Offset(0f, 0f)) }

    var selection by remember { mutableStateOf(Selection()) }

    // Slide-hold states
    var slideHoldDirection by remember { mutableStateOf<SwipeDirection?>(null) }
    var slideHoldReturnDetected by remember { mutableStateOf(false) }
    var slideHoldTickCount by remember { mutableIntStateOf(0) }
    var slideHoldRepeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var holdRepeatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun setSpacebarMagnifier(active: Boolean) {
        if (spacebarMagnifierActive == active) return
        spacebarMagnifierActive = active
        ime.onSpacebarSlideChanged(active)
    }

    DisposableEffect(Unit) {
        onDispose {
            holdRepeatJob?.cancel()
            slideHoldRepeatJob?.cancel()
            setSpacebarMagnifier(false)
        }
    }

    val backgroundColor =
        if (!(isDragged.value || isPressed)) {
            colorVariantToColor(colorVariant = key.backgroundColor)
        } else {
            MaterialTheme.colorScheme.inversePrimary
        }

    val keyBorderColour = MaterialTheme.colorScheme.outline
    val keySize = (keyHeight + keyWidth) / 2.0
    val view = LocalView.current
    val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * The type of haptic feedback to use when moving the cursor with slide
     * gestures, based on the device's supported API.
     */
    val slideHapticConstant =
        if (Build.VERSION.SDK_INT >= 27) {
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        } else {
            // Compatible with API 24, but vibration will not distinguish between tap and slide
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    LaunchedEffect(key1 = isPressed) {
        if (isPressed) {
            if (vibrateOnTap) {
                // This is a workaround for only having LongPress
                // https://stackoverflow.com/questions/68333741/how-to-perform-a-haptic-feedback-in-jetpack-compose
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (soundOnTap) {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, .1f)
            }
        } else {
            holdRepeatJob?.cancel()
            holdRepeatJob = null
        }
    }

    suspend fun repeatCursorMovement(dir: SwipeDirection) {
        var tickCount = 0
        while (currentCoroutineContext().isActive) {
            val delayMs =
                if (tickCount < SLIDE_HOLD_SLOW_TICK_THRESHOLD) {
                    SLIDE_HOLD_SLOW_TICK_DELAY_MS
                } else {
                    SLIDE_HOLD_FAST_TICK_DELAY_MS
                }
            delay(delayMs)
            when (dir) {
                SwipeDirection.LEFT -> {
                    if (slideHoldReturnDetected) {
                        previousWordBeforeCursor(ime)
                    } else {
                        sendDownUpKeyEvent(ime.currentInputConnection, KeyEvent.KEYCODE_DPAD_LEFT)
                    }
                }

                SwipeDirection.RIGHT -> {
                    if (slideHoldReturnDetected) {
                        nextWordAfterCursor(ime)
                    } else {
                        sendDownUpKeyEvent(ime.currentInputConnection, KeyEvent.KEYCODE_DPAD_RIGHT)
                    }
                }

                SwipeDirection.TOP -> {
                    moveCursorVertically(ime, -1)
                }

                SwipeDirection.BOTTOM -> {
                    moveCursorVertically(ime, 1)
                }

                else -> {
                    Log.d(TAG, "ERROR: Invalid swipe direction: $dir")
                }
            }
            tickCount++
            slideHoldTickCount = tickCount
            ime.onSpacebarSlideStep()
            if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
        }
    }

    suspend fun repeatDeleteAction() {
        var tickCount = 0
        while (currentCoroutineContext().isActive) {
            val delayMs =
                if (tickCount < SLIDE_HOLD_SLOW_TICK_THRESHOLD) {
                    SLIDE_HOLD_SLOW_TICK_DELAY_MS
                } else {
                    SLIDE_HOLD_FAST_TICK_DELAY_MS
                }
            delay(delayMs)
            if (slideHoldReturnDetected) {
                deleteWordBeforeCursor(ime)
            } else {
                sendDownUpKeyEvent(ime.currentInputConnection, KeyEvent.KEYCODE_DEL)
            }
            tickCount++
            slideHoldTickCount = tickCount
            if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
        }
    }

    /**
     * Resets all state variables associated with the slide-hold gesture.
     *
     * @return The number of ticks that occurred during the slide-hold before it was reset.
     */
    fun resetSlideHoldVariables(): Int {
        // Always clean up slide-hold at drag end. Capture ticks before clearing
        // so the branch below can decide whether the hold actually fired.
        val slideHoldTicks = slideHoldTickCount
        slideHoldRepeatJob?.cancel()
        slideHoldRepeatJob = null
        slideHoldDirection = null
        slideHoldReturnDetected = false
        slideHoldTickCount = 0
        return slideHoldTicks
    }

    fun handleSlideHold() {
        // Slide-hold: detect swipe direction, then
        // repeat cursor movement (spacebar) or deletion (backspace) while held
        val returnThreshold = currentMinSwipeLength * DRAG_RETURN_THRESHOLD_FACTOR

        if (slideHoldDirection != null) {
            // Direction already detected; check for return gesture.
            if (!slideHoldReturnDetected && maxOffset.getDistance() >= currentMinSwipeLength) {
                // Mirrors the drag-return detection in onDragEnd: the gesture qualifies as "slide-and-return" if either
                // (a) it's close enough to the origin, or
                // (b) it moved into a different cardinal direction from the max-offset direction.
                val currentOffset = positions.lastOrNull() ?: Offset(offsetX, offsetY)
                val closeEnough = currentOffset.getDistance() <= returnThreshold
                val currentSwipeDirection =
                    swipeDirection(
                        currentOffset.x,
                        currentOffset.y,
                        currentMinSwipeLength,
                        SwipeNWay.FOUR_WAY_CROSS,
                    )
                val maxSwipeDirection =
                    swipeDirection(
                        maxOffset.x,
                        maxOffset.y,
                        currentMinSwipeLength,
                        SwipeNWay.FOUR_WAY_CROSS,
                    )
                val oppositeDirection = currentSwipeDirection != maxSwipeDirection
                if (closeEnough || oppositeDirection) {
                    slideHoldReturnDetected = true
                }
            }
        } else {
            // Try to detect cardinal swipe direction
            val dir =
                swipeDirection(
                    offsetX,
                    offsetY,
                    currentMinSwipeLength,
                    SwipeNWay.FOUR_WAY_CROSS,
                )
            if (dir == null) {
                return
            }

            slideHoldDirection = dir
            when (key.slideType) {
                SlideType.MOVE_CURSOR -> {
                    if (
                        dir == SwipeDirection.LEFT ||
                        dir == SwipeDirection.RIGHT ||
                        dir == SwipeDirection.TOP ||
                        dir == SwipeDirection.BOTTOM
                    ) {
                        setSpacebarMagnifier(true)
                        slideHoldRepeatJob = scope.launch { repeatCursorMovement(dir) }
                    }
                }

                SlideType.DELETE -> {
                    // Backspace: only left-swipe is supported
                    if (dir == SwipeDirection.LEFT) {
                        slideHoldRepeatJob = scope.launch { repeatDeleteAction() }
                    }
                }

                SlideType.NONE -> {}
            }
        }
    }

    fun dispatchKeyAction(action: KeyAction) {
        performKeyAction(
            action = action,
            ime = ime,
            autoCapitalize = autoCapitalize,
            keyboardSettings = keyboardSettings,
            onToggleShiftMode = onToggleShiftMode,
            onToggleCtrlMode = onToggleCtrlMode,
            onToggleAltMode = onToggleAltMode,
            onToggleNumericMode = onToggleNumericMode,
            onToggleEmojiMode = onToggleEmojiMode,
            onToggleClipboardMode = onToggleClipboardMode,
            onToggleSketchMode = onToggleSketchMode,
            onToggleCapsLock = onToggleCapsLock,
            onToggleHideLetters = onToggleHideLetters,
            onAutoCapitalize = onAutoCapitalize,
            onSwitchLanguage = onSwitchLanguage,
            onCycleAutocorrectLanguage = onCycleAutocorrectLanguage,
            onChangePosition = onChangePosition,
            onKeyEvent = onKeyEvent,
            onSketchAction = onSketchAction,
        )
    }

    val currentDispatchKeyAction by rememberUpdatedState(::dispatchKeyAction)
    val effectiveLongPressAction =
        resolveHoldKeyAction(
            key = key,
            holdKeyAction = holdKeyAction,
            oppositeCaseKey = oppositeCaseKey,
            numericKey = numericKey,
            backspaceHoldAction = backspaceHoldAction,
        )

    val keyboardKeyModifier =
        Modifier
            .height(keyHeight.dp)
            .width(keyWidth.dp * key.widthMultiplier)
            // Note: pointerInput has a delay when switching keyboards, so you must use this
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                hapticFeedbackEnabled = false, // We manually trigger haptics instead
                onClick = {
                    // Set the last key info, and the tap count
                    val cAction = key.center.action
                    lastAction.value?.let { (lastAction, time) ->
                        if (time.elapsedNow() < 1.seconds && lastAction == cAction && !ime.didCursorMove()) {
                            tapCount += 1
                        } else {
                            tapCount = 0
                        }
                    }
                    lastAction.value = Pair(cAction, TimeSource.Monotonic.markNow())

                    // Set the correct action
                    val action = tapActions[tapCount % tapActions.size]
                    currentDispatchKeyAction(action)
                    doneKeyAction(action, isDragged, releasedFeedback)
                },
                onLongClick = {
                    effectiveLongPressAction?.let { action ->
                        currentDispatchKeyAction(action)
                        doneKeyAction(action, isDragged, releasedFeedback)
                        if (vibrateOnTap) {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        if (soundOnTap) {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, .1f)
                        }
                        if (key.isBackspaceRepeatHold(backspaceHoldAction)) {
                            holdRepeatJob?.cancel()
                            holdRepeatJob =
                                scope.launch {
                                    var tick = 0
                                    while (currentCoroutineContext().isActive) {
                                        val delayMs =
                                            if (tick < HOLD_REPEAT_SLOW_TICK_THRESHOLD) {
                                                HOLD_REPEAT_SLOW_TICK_DELAY_MS
                                            } else {
                                                HOLD_REPEAT_FAST_TICK_DELAY_MS
                                            }
                                        delay(delayMs)
                                        ime.autocorrectSession?.clearUndoState()
                                        currentDispatchKeyAction(action)
                                        tick++
                                        if (currentVibrateOnTap) {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        }
                                    }
                                }
                        }
                    }
                },
            )
            // Keep pointerInput stable across transient state changes while tracking key layout updates
            .pointerInput(key1 = pointerInputKey) {
                detectDragGestures(
                    onDragStart = {
                        holdRepeatJob?.cancel()
                        holdRepeatJob = null
                        isDragged.value = true
                        activeDragDirection = null
                        activeDragReturn = false
                        verticalAccumY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val (x, y) = dragAmount
                        offsetX += x
                        offsetY += y
                        val offset = Offset(offsetX, offsetY)
                        positions += offset
                        if (offset.getDistanceSquared() > maxOffset.getDistanceSquared()) maxOffset = offset

                        // First detection is large enough to preserve swipe actions.
                        val slideOffsetTrigger = (keySize.dp.toPx() * 0.75) + currentMinSwipeLength

                        if ((key.slideType == SlideType.MOVE_CURSOR || key.slideType == SlideType.DELETE) && currentSlideHoldEnabled) {
                            handleSlideHold()
                        } else if (key.slideType == SlideType.MOVE_CURSOR && currentSlideEnabled) {
                            val crossedDeadzone =
                                !currentSlideSpacebarDeadzoneEnabled ||
                                    abs(offsetX) > slideOffsetTrigger ||
                                    abs(offsetY) > slideOffsetTrigger
                            if (hasSlideMoveCursorTriggered || crossedDeadzone) {
                                val justEntered = !hasSlideMoveCursorTriggered
                                if (justEntered) {
                                    val initialDx = offsetX
                                    val initialDy = offsetY
                                    offsetX = 0f
                                    offsetY = 0f
                                    verticalAccumY = 0f
                                    hasSlideMoveCursorTriggered = true
                                    setSpacebarMagnifier(true)
                                    if (
                                        currentSlideSpacebarDeadzoneEnabled &&
                                        abs(initialDy) > slideOffsetTrigger &&
                                        isVerticalSlideDominant(initialDx, initialDy)
                                    ) {
                                        moveCursorVertically(ime, if (initialDy < 0f) -1 else 1)
                                        ime.onSpacebarSlideStep()
                                        if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
                                    }
                                }

                                if (!justEntered) {
                                    if (isVerticalSlideDominant(x, y)) {
                                        verticalAccumY += y
                                        if (abs(y) > abs(x) * 1.6f) {
                                            offsetX *= 0.5f
                                        }
                                    } else {
                                        verticalAccumY *= 0.5f
                                    }
                                    val verticalStepPx = verticalSlideStepDp(currentSlideSensitivity).dp.toPx()
                                    var verticalSteps = 0
                                    while (verticalAccumY >= verticalStepPx && verticalSteps < 4) {
                                        moveCursorVertically(ime, 1)
                                        verticalAccumY -= verticalStepPx
                                        verticalSteps++
                                    }
                                    while (verticalAccumY <= -verticalStepPx && verticalSteps < 4) {
                                        moveCursorVertically(ime, -1)
                                        verticalAccumY += verticalStepPx
                                        verticalSteps++
                                    }
                                    if (verticalSteps > 0) {
                                        ime.onSpacebarSlideStep()
                                        if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
                                    }
                                }

                                var lengthOfSelectedText = 0
                                val selectedText = ime.currentInputConnection.getSelectedText(0)
                                if (!selectedText.isNullOrEmpty()) {
                                    lengthOfSelectedText = selectedText.length
                                }

                                var cursorMovement =
                                    slideCursorDistance(
                                        offsetX,
                                        timeOfLastAccelerationInput,
                                        currentSlideCursorMovementMode,
                                        currentSlideSensitivity,
                                    )
                                timeOfLastAccelerationInput = System.currentTimeMillis()
                                if (cursorMovement > 0) {
                                    // Increment distance by one, because a value of 2 moves the cursor by 1 character.
                                    cursorMovement += 1
                                }

                                // Move the cursor horizontally.
                                // '2' moves the cursor right by 1 character; '-1' moves it left by 1 character.
                                if (cursorMovement >= 2 || cursorMovement <= -1) {
                                    if (lengthOfSelectedText > 0) {
                                        selection = startSelection(ime)
                                        if (selection.active) {
                                            ime.currentInputConnection.setSelection(
                                                selection.selectionStart,
                                                selection.selectionEnd,
                                            )
                                        }
                                        selection = Selection()
                                        if (cursorMovement >= 2) {
                                            cursorMovement += lengthOfSelectedText
                                        }
                                    }
                                    ime.currentInputConnection.commitText("", cursorMovement)
                                    ime.onSpacebarSlideStep()
                                    offsetX = 0f
                                    if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
                                }
                            }
                        } else if (key.slideType == SlideType.DELETE && currentSlideEnabled) {
                            if (!selection.active) {
                                timeOfLastAccelerationInput = System.currentTimeMillis()
                                // Activate selection, first detection is large enough to preserve swipe actions.
                                if ((
                                        currentSlideBackspaceDeadzoneEnabled &&
                                            (abs(offsetX) > slideOffsetTrigger)
                                    ) ||
                                    !currentSlideBackspaceDeadzoneEnabled
                                ) {
                                    // reset offsetX, do not reset offsetY when sliding, it will break selecting
                                    offsetX = 0f
                                    selection = startSelection(ime)
                                }
                            } else {
                                val cursorMovement =
                                    slideCursorDistance(
                                        offsetX,
                                        timeOfLastAccelerationInput,
                                        currentSlideCursorMovementMode,
                                        currentSlideSensitivity,
                                    )
                                timeOfLastAccelerationInput = System.currentTimeMillis()
                                if (cursorMovement >= 1 || cursorMovement <= -1) {
                                    if (cursorMovement < 0.00) {
                                        selection.left(abs(cursorMovement))
                                    } else {
                                        selection.right(abs(cursorMovement))
                                    }
                                    ime.currentInputConnection.setSelection(
                                        selection.selectionStart,
                                        selection.selectionEnd,
                                    )
                                    // reset offsetX, do not reset offsetY when sliding, it will break selecting
                                    offsetX = 0f
                                    // selection has changed; give feedback
                                    if (currentVibrateOnSlide) view.performHapticFeedback(slideHapticConstant)
                                }
                            }
                        }

                        val isSliding =
                            (key.slideType == SlideType.MOVE_CURSOR && hasSlideMoveCursorTriggered) ||
                                (key.slideType == SlideType.DELETE && selection.active) ||
                                (
                                    (key.slideType == SlideType.MOVE_CURSOR || key.slideType == SlideType.DELETE) &&
                                        currentSlideHoldEnabled &&
                                        slideHoldTickCount > 0
                                )
                        if (isSliding) {
                            activeDragDirection = null
                            activeDragReturn = false
                        } else {
                            val swipeNWay = if (ghostKey == null) key.swipeType else SwipeNWay.EIGHT_WAY
                            val liveDir = swipeDirection(offsetX, offsetY, currentMinSwipeLength, swipeNWay)
                            val maxDir = swipeDirection(maxOffset.x, maxOffset.y, currentMinSwipeLength, swipeNWay)
                            val returnThreshold = currentMinSwipeLength * DRAG_RETURN_THRESHOLD_FACTOR
                            val returned =
                                currentDragReturnEnabled &&
                                    maxDir != null &&
                                    maxOffset.getDistance() >= currentMinSwipeLength &&
                                    (offset.getDistance() <= returnThreshold || liveDir != maxDir)
                            val hasReturnAction =
                                maxDir != null &&
                                    (
                                        key.getSwipe(maxDir)?.swipeReturnAction != null ||
                                            oppositeCaseKey?.takeIf { it != key }?.getSwipe(maxDir)?.action != null ||
                                            ghostKey?.getSwipe(maxDir)?.swipeReturnAction != null
                                    )
                            activeDragDirection = if (returned && hasReturnAction) maxDir else liveDir
                            activeDragReturn = returned && hasReturnAction
                        }
                    },
                    onDragEnd = {
                        lateinit var action: KeyAction

                        val slideHoldTicks = resetSlideHoldVariables()
                        activeDragDirection = null
                        activeDragReturn = false
                        setSpacebarMagnifier(false)

                        if ((key.slideType == SlideType.MOVE_CURSOR || key.slideType == SlideType.DELETE) &&
                            currentSlideHoldEnabled && slideHoldTicks > 0
                        ) {
                            // The repeat coroutine already handled all movement/deletion; nothing more to do.
                            action = KeyAction.Noop
                            doneKeyAction(action, isDragged, releasedFeedback)
                        } else if (key.slideType == SlideType.NONE ||
                            !currentSlideEnabled ||
                            ((key.slideType == SlideType.DELETE) && !selection.active) ||
                            ((key.slideType == SlideType.MOVE_CURSOR) && !hasSlideMoveCursorTriggered)
                        ) {
                            hasSlideMoveCursorTriggered = false

                            val finalOffsetThreshold = currentMinSwipeLength * DRAG_RETURN_THRESHOLD_FACTOR
                            val maxOffsetThreshold = currentMinSwipeLength

                            val finalOffset = positions.lastOrNull() ?: Offset(offsetX, offsetY)
                            val finalOffsetSmallEnough =
                                finalOffset.getDistance() <= finalOffsetThreshold ||
                                    (
                                        swipeDirection(finalOffset.x, finalOffset.y, currentMinSwipeLength, key.swipeType)?.equals(
                                            swipeDirection(maxOffset.x, maxOffset.y, currentMinSwipeLength, key.swipeType),
                                        ) != true
                                    )

                            val maxOffsetBigEnough = maxOffset.getDistance().toDouble() >= maxOffsetThreshold
                            val (resolvedAction, originDirection) =
                                (
                                    if (maxOffsetBigEnough && finalOffsetSmallEnough) {
                                        (
                                            if (currentCircularDragEnabled) {
                                                val circularDragActions =
                                                    mapOf(
                                                        CircularDragAction.OppositeCase to oppositeCaseKey?.center?.action,
                                                        CircularDragAction.Numeric to numericKey?.center?.action,
                                                    )
                                                circularDirection(positions, finalOffsetThreshold, currentMinSwipeLength)
                                                    ?.let {
                                                        when (it) {
                                                            CircularDirection.Clockwise -> {
                                                                circularDragActions[currentClockwiseDragAction]
                                                            }

                                                            CircularDirection.Counterclockwise -> {
                                                                circularDragActions[currentCounterclockwiseDragAction]
                                                            }
                                                        }
                                                    }?.let { it to null }
                                            } else {
                                                null
                                            }
                                        ) ?: (
                                            if (currentDragReturnEnabled) {
                                                val swipeDir =
                                                    swipeDirection(
                                                        maxOffset.x,
                                                        maxOffset.y,
                                                        currentMinSwipeLength,
                                                        if (ghostKey == null) key.swipeType else SwipeNWay.EIGHT_WAY,
                                                    )
                                                (
                                                    key.getSwipe(swipeDir)?.swipeReturnAction
                                                        ?: oppositeCaseKey?.getSwipe(swipeDir)?.action
                                                        ?: ghostKey?.getSwipe(swipeDir)?.swipeReturnAction
                                                )?.let { it to swipeDir }
                                            } else {
                                                null
                                            }
                                        )
                                    } else {
                                        val swipeDir =
                                            swipeDirection(
                                                offsetX,
                                                offsetY,
                                                currentMinSwipeLength,
                                                if (ghostKey == null) key.swipeType else SwipeNWay.EIGHT_WAY,
                                            )
                                        (
                                            key.getSwipe(swipeDir)?.action
                                                ?: ghostKey?.getSwipe(swipeDir)?.action
                                        )?.let { it to swipeDir }
                                    }
                                ) ?: (key.center.action to null)
                            action = resolvedAction

                            currentDispatchKeyAction(action)
                            doneKeyAction(
                                action = action,
                                pressed = isDragged,
                                releasedFeedback = releasedFeedback,
                                originDirection = originDirection,
                            )
                        } else if (key.slideType == SlideType.DELETE) {
                            action =
                                KeyAction.SendEvent(
                                    KeyEvent(
                                        KeyEvent.ACTION_DOWN,
                                        KeyEvent
                                            .KEYCODE_DEL,
                                    ),
                                )
                            // only delete if valid selection
                            val sel = ime.currentInputConnection.getSelectedText(0)
                            sel?.let {
                                if (it.isNotEmpty()) {
                                    currentDispatchKeyAction(action)
                                }
                                // Play an extra haptic effect on supported devices when slide deleting text
                                if (currentVibrateOnSlide && Build.VERSION.SDK_INT >= 30) {
                                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                                }
                            }
                            doneKeyAction(action, isDragged, releasedFeedback)
                        } else {
                            hasSlideMoveCursorTriggered = false
                            action =
                                KeyAction.SendEvent(
                                    KeyEvent(
                                        KeyEvent.ACTION_UP,
                                        KeyEvent.KEYCODE_DPAD_RIGHT,
                                    ),
                                )
                            doneKeyAction(action, isDragged, releasedFeedback)
                        }

                        // Set tapCount and lastAction to avoid issues with multitap after slide
                        tapCount = 0
                        lastAction.value = Pair(action, TimeSource.Monotonic.markNow())

                        // Reset the drags
                        offsetX = 0f
                        offsetY = 0f
                        verticalAccumY = 0f
                        maxOffset = Offset(0f, 0f)
                        positions = listOf()

                        // Reset selection
                        selection = Selection()
                    },
                    onDragCancel = {
                        holdRepeatJob?.cancel()
                        holdRepeatJob = null
                        resetSlideHoldVariables()
                        activeDragDirection = null
                        activeDragReturn = false
                        setSpacebarMagnifier(false)
                        hasSlideMoveCursorTriggered = false
                        isDragged.value = false
                        offsetX = 0f
                        offsetY = 0f
                        verticalAccumY = 0f
                        maxOffset = Offset(0f, 0f)
                        positions = listOf()
                        selection = Selection()
                    },
                )
            }.padding(keyPadding.dp)
            .clip(RoundedCornerShape(keyRadius.dp))
            .then(
                if (keyBorderWidth > 0.0) {
                    Modifier.border(
                        keyBorderWidth.dp,
                        keyBorderColour,
                        shape = RoundedCornerShape(keyRadius.dp),
                    )
                } else {
                    (Modifier)
                },
            ).background(color = backgroundColor)

    // a 3x3 grid
    // Use box so they can overlap
    // Some magic padding numbers so that large radii don't obscure the legends
    val radiusPercent = keyRadius / 100F
    val keyRadiusAdd = 20.dp
    val xPadding = (2 + keyBorderWidth).dp
    val yPadding = (0 + keyBorderWidth).dp

    val diagonalXPadding =
        lerp(
            xPadding,
            xPadding +
                keyRadiusAdd,
            radiusPercent,
        )
    val diagonalYPadding =
        lerp(
            yPadding,
            yPadding + keyRadiusAdd,
            radiusPercent,
        )

    val showDragHighlight = isDragged.value && !isPasswordField
    val swipeSlots =
        listOf(
            Triple(SwipeDirection.TOP_LEFT, Alignment.TopStart, PaddingValues(horizontal = diagonalXPadding, vertical = diagonalYPadding)),
            Triple(SwipeDirection.TOP, Alignment.TopCenter, PaddingValues(vertical = yPadding)),
            Triple(SwipeDirection.TOP_RIGHT, Alignment.TopEnd, PaddingValues(horizontal = diagonalXPadding, vertical = diagonalYPadding)),
            Triple(SwipeDirection.LEFT, Alignment.CenterStart, PaddingValues(horizontal = xPadding)),
            Triple(SwipeDirection.RIGHT, Alignment.CenterEnd, PaddingValues(horizontal = xPadding)),
            Triple(
                SwipeDirection.BOTTOM_LEFT,
                Alignment.BottomStart,
                PaddingValues(horizontal = diagonalXPadding, vertical = diagonalYPadding),
            ),
            Triple(SwipeDirection.BOTTOM, Alignment.BottomCenter, PaddingValues(vertical = yPadding)),
            Triple(
                SwipeDirection.BOTTOM_RIGHT,
                Alignment.BottomEnd,
                PaddingValues(horizontal = diagonalXPadding, vertical = diagonalYPadding),
            ),
        )

    Box(
        modifier = keyboardKeyModifier,
    ) {
        swipeSlots.forEach { (dir, alignment, slotPadding) ->
            val isHighlighted = showDragHighlight && activeDragDirection == dir
            val dirKey =
                if (isHighlighted && activeDragReturn) {
                    val primary = key.getSwipe(dir)
                    val returnPreview =
                        when (val ret = primary?.swipeReturnAction) {
                            is KeyAction.CommitText -> KeyC(ret.text, size = primary.size, color = ColorVariant.SECONDARY)
                            else -> oppositeCaseKey?.takeIf { it != key }?.getSwipe(dir) ?: primary
                        }
                    returnPreview ?: ghostKey?.getSwipe(dir)
                } else {
                    key.getSwipe(dir) ?: if (isHighlighted) ghostKey?.getSwipe(dir) else null
                }
            Box(
                contentAlignment = alignment,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(slotPadding),
            ) {
                dirKey?.let {
                    DirectionalSwipeKeyText(
                        key = it,
                        keySize = (keySize - keyBorderWidth).dp,
                        hideLetters = hideLetters,
                        hideSymbols = hideSymbols,
                        capsLock = capsLock,
                        highlighted = isHighlighted,
                    )
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize(),
        ) {
            val isBlankSpacebar =
                key.slideType == SlideType.MOVE_CURSOR &&
                    (
                        key.center.display == null ||
                            (key.center.display as? KeyDisplay.TextDisplay)?.text?.isBlank() == true
                    )
            val resolvedSpacebarText =
                if (key.widthMultiplier <= 1 && !spacebarShortLabel.isNullOrEmpty()) {
                    spacebarShortLabel
                } else {
                    spacebarLabel
                }
            if (isBlankSpacebar && !resolvedSpacebarText.isNullOrEmpty()) {
                val labelFontSize = (keyHeight * 0.21f).coerceIn(10f, 13f).sp
                Text(
                    text = resolvedSpacebarText,
                    fontSize = labelFontSize,
                    lineHeight = labelFontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorVariantToColor(ColorVariant.MUTED).copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            } else {
                KeyText(key.center, (keySize - keyBorderWidth).dp, hideLetters, hideSymbols, capsLock)
            }
        }

        if (!isPasswordField) {
            KeyPressFeedbackOverlay(
                feedback = releasedFeedback.value,
                keyAnimationType = keyAnimationType,
                animationSpeed = animationSpeed,
                animationHelperSpeed = animationHelperSpeed,
                keySize = keySize,
            )
        }
    }
}

private fun KeyAnimationType.initialTranslationFraction(originDirection: SwipeDirection?): Offset =
    when (this) {
        KeyAnimationType.SlideFromTop -> Offset(0f, -0.5f)
        KeyAnimationType.SlideFromBottom -> Offset(0f, 0.5f)
        KeyAnimationType.SlideFromLeft -> Offset(-0.5f, 0f)
        KeyAnimationType.SlideFromRight -> Offset(0.5f, 0f)
        KeyAnimationType.Fade -> Offset.Zero
        KeyAnimationType.FromOrigin -> originDirection?.originTranslationFraction() ?: Offset.Zero
    }

private fun SwipeDirection.originTranslationFraction(): Offset =
    when (this) {
        SwipeDirection.TOP -> Offset(0f, -0.45f)
        SwipeDirection.BOTTOM -> Offset(0f, 0.45f)
        SwipeDirection.LEFT -> Offset(-0.45f, 0f)
        SwipeDirection.RIGHT -> Offset(0.45f, 0f)
        SwipeDirection.TOP_LEFT -> Offset(-0.42f, -0.42f)
        SwipeDirection.TOP_RIGHT -> Offset(0.42f, -0.42f)
        SwipeDirection.BOTTOM_LEFT -> Offset(-0.42f, 0.42f)
        SwipeDirection.BOTTOM_RIGHT -> Offset(0.42f, 0.42f)
    }

@Composable
private fun KeyPressFeedbackOverlay(
    feedback: ReleasedKeyFeedback?,
    keyAnimationType: KeyAnimationType,
    animationSpeed: Int,
    animationHelperSpeed: Int,
    keySize: Double,
) {
    var activeFeedback by remember { mutableStateOf<ReleasedKeyFeedback?>(null) }
    val enterProgress = remember { Animatable(1f) }
    val exitAlpha = remember { Animatable(0f) }

    LaunchedEffect(feedback?.id) {
        val current = feedback ?: return@LaunchedEffect
        if (animationSpeed <= 0 && animationHelperSpeed <= 0) {
            activeFeedback = null
            return@LaunchedEffect
        }
        activeFeedback = current
        enterProgress.snapTo(if (animationSpeed <= 0) 1f else 0f)
        exitAlpha.snapTo(1f)
        val enterJob =
            if (animationSpeed > 0) {
                launch {
                    enterProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = animationSpeed, easing = FastOutSlowInEasing),
                    )
                }
            } else {
                null
            }
        delay(animationHelperSpeed.coerceAtLeast(0).toLong())
        if (animationSpeed > 0) {
            exitAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = animationSpeed),
            )
        }
        enterJob?.cancel()
        activeFeedback = null
    }

    val current = activeFeedback ?: return
    val useFadeIn = keyAnimationType == KeyAnimationType.Fade || keyAnimationType == KeyAnimationType.FromOrigin
    val originFraction = keyAnimationType.initialTranslationFraction(current.originDirection)
    val spSize =
        fontSizeVariantToFontSize(
            fontSizeVariant = FontSizeVariant.LARGE,
            keySize = keySize.dp,
            isUpperCase = false,
        ).toPx.pxToSp

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val fadeInFactor = if (useFadeIn) enterProgress.value else 1f
                    alpha = (fadeInFactor * exitAlpha.value).coerceIn(0f, 1f)
                    compositingStrategy = CompositingStrategy.Offscreen
                }.background(color = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val remaining = 1f - enterProgress.value
                        translationX = size.width * originFraction.x * remaining
                        translationY = size.height * originFraction.y * remaining
                    },
        ) {
            Text(
                text = current.text,
                fontWeight = FontWeight.Bold,
                fontSize = spSize,
                lineHeight = spSize,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
fun KeyText(
    key: KeyC,
    keySize: Dp,
    hideLetters: Boolean,
    hideSymbols: Boolean,
    capsLock: Boolean,
    overrideColor: Color? = null,
) {
    val color = overrideColor ?: colorVariantToColor(colorVariant = key.color)
    val isUpperCase =
        when (key.display) {
            is KeyDisplay.TextDisplay -> {
                key.display.text
                    .firstOrNull()
                    ?.isUpperCase() == true
            }

            else -> {
                false
            }
        }
    val fontSize =
        fontSizeVariantToFontSize(
            fontSizeVariant = key.size,
            keySize = keySize,
            isUpperCase = isUpperCase,
        )

    val display =
        if (capsLock) {
            key.capsModeDisplay ?: key.display
        } else {
            key.display
        }

    when (display) {
        is KeyDisplay.IconDisplay -> {
            Icon(
                imageVector = display.icon,
                contentDescription = display.icon.name,
                tint = color,
                modifier = Modifier.size(fontSize),
            )
        }

        is KeyDisplay.TextDisplay -> {
            // Only  hide the letters for text, not symbols
            val containsLetters = display.text.any { it.isLetter() }
            val containsNumbers = display.text.any { it.isDigit() }

            // If its a letter key, use the hide letter setting
            val hideKey =
                if (containsLetters) {
                    hideLetters
                } else {
                    if (containsNumbers) {
                        false
                    } else {
                        hideSymbols
                    }
                }
            if (!hideKey) {
                val spSize = fontSize.toPx.pxToSp
                Text(
                    text = display.text,
                    fontWeight = FontWeight.Bold,
                    fontFamily = display.fontFamily,
                    fontSize = spSize,
                    lineHeight = spSize,
                    color = color,
                )
            }
        }

        null -> {}
    }
}

private data class KeyPointerInputKey(
    val key: KeyItemC,
    val ghostKey: KeyItemC?,
    val oppositeCaseKey: KeyItemC?,
    val numericKey: KeyItemC?,
    val keyboardSettings: KeyboardDefinitionSettings,
)

@Composable
private fun DirectionalSwipeKeyText(
    key: KeyC,
    keySize: Dp,
    hideLetters: Boolean,
    hideSymbols: Boolean,
    capsLock: Boolean,
    highlighted: Boolean,
) {
    if (!highlighted) {
        KeyText(key, keySize, hideLetters, hideSymbols, capsLock)
        return
    }
    Box(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                }.clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.tertiary) {
            KeyText(
                key = key,
                keySize = keySize,
                hideLetters = hideLetters,
                hideSymbols = hideSymbols,
                capsLock = capsLock,
                overrideColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}
