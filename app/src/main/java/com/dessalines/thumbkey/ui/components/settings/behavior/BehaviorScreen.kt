package com.dessalines.thumbkey.ui.components.settings.behavior

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.BorderInner
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.UTurnRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation.NavController
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.db.AppSettingsViewModel
import com.dessalines.thumbkey.db.BehaviorUpdate
import com.dessalines.thumbkey.db.DEFAULT_AUTO_CAPITALIZE
import com.dessalines.thumbkey.db.DEFAULT_BACKSPACE_HOLD_ACTION
import com.dessalines.thumbkey.db.DEFAULT_CIRCULAR_DRAG_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_CLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION
import com.dessalines.thumbkey.db.DEFAULT_DRAG_RETURN_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_GHOST_KEYS_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_HOLD_DURATION_MS
import com.dessalines.thumbkey.db.DEFAULT_HOLD_KEY_ACTION
import com.dessalines.thumbkey.db.DEFAULT_MIN_SWIPE_LENGTH
import com.dessalines.thumbkey.db.DEFAULT_NUMERIC_LAYOUT_ORDER
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_HOLD_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SENSITIVITY
import com.dessalines.thumbkey.db.DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SPACEBAR_MULTITAPS
import com.dessalines.thumbkey.db.DEFAULT_SWITCH_IME_MODE
import com.dessalines.thumbkey.db.DEFAULT_SWITCH_TO_LETTERS_AFTER_SPACE
import com.dessalines.thumbkey.db.HOLD_DURATION_STEP_MS
import com.dessalines.thumbkey.db.MAX_HOLD_DURATION_MS
import com.dessalines.thumbkey.db.MIN_HOLD_DURATION_MS
import com.dessalines.thumbkey.ui.components.common.TestOutTextField
import com.dessalines.thumbkey.ui.components.settings.about.SettingsDivider
import com.dessalines.thumbkey.utils.BackspaceHoldAction
import com.dessalines.thumbkey.utils.CircularDragAction
import com.dessalines.thumbkey.utils.CursorAccelerationMode
import com.dessalines.thumbkey.utils.HoldKeyAction
import com.dessalines.thumbkey.utils.ImeSwitchMode
import com.dessalines.thumbkey.utils.NumericLayoutOrder
import com.dessalines.thumbkey.utils.SimpleTopAppBar
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.toBool
import com.dessalines.thumbkey.utils.toInt
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BehaviorScreen(
    navController: NavController,
    appSettingsViewModel: AppSettingsViewModel,
) {
    Log.d(TAG, "Got to behavior activity")

    val settings by appSettingsViewModel.appSettings.observeAsState()

    var minSwipeLengthState = (settings?.minSwipeLength ?: DEFAULT_MIN_SWIPE_LENGTH).toFloat()
    var minSwipeLengthSliderState by remember { mutableFloatStateOf(minSwipeLengthState) }

    var slideSensitivityState = (settings?.slideSensitivity ?: DEFAULT_SLIDE_SENSITIVITY).toFloat()
    var slideSensitivitySliderState by remember { mutableFloatStateOf(slideSensitivityState) }

    var slideCursorMovementModeState =
        CursorAccelerationMode.entries[settings?.slideCursorMovementMode ?: DEFAULT_SLIDE_CURSOR_MOVEMENT_MODE]

    var slideEnabledState = (settings?.slideEnabled ?: DEFAULT_SLIDE_ENABLED).toBool()
    var slideSpacebarDeadzoneEnabledState = (settings?.slideSpacebarDeadzoneEnabled ?: DEFAULT_SLIDE_SPACEBAR_DEADZONE_ENABLED).toBool()
    var slideBackspaceDeadzoneEnabledState = (settings?.slideBackspaceDeadzoneEnabled ?: DEFAULT_SLIDE_BACKSPACE_DEADZONE_ENABLED).toBool()
    var autoCapitalizeState = (settings?.autoCapitalize ?: DEFAULT_AUTO_CAPITALIZE).toBool()
    var spacebarMultiTapsState = (settings?.spacebarMultiTaps ?: DEFAULT_SPACEBAR_MULTITAPS).toBool()
    var switchToLettersAfterSpaceState =
        (settings?.switchToLettersAfterSpace ?: DEFAULT_SWITCH_TO_LETTERS_AFTER_SPACE).toBool()

    var dragReturnEnabledState = (settings?.dragReturnEnabled ?: DEFAULT_DRAG_RETURN_ENABLED).toBool()
    var circularDragEnabledState = (settings?.circularDragEnabled ?: DEFAULT_CIRCULAR_DRAG_ENABLED).toBool()
    var clockwiseDragActionState = CircularDragAction.entries[settings?.clockwiseDragAction ?: DEFAULT_CLOCKWISE_DRAG_ACTION]
    var counterclockwiseDragActionState =
        CircularDragAction.entries[settings?.counterclockwiseDragAction ?: DEFAULT_COUNTERCLOCKWISE_DRAG_ACTION]
    var ghostKeysEnabledState = (settings?.ghostKeysEnabled ?: DEFAULT_GHOST_KEYS_ENABLED).toBool()
    var slideHoldEnabledState = (settings?.slideHoldEnabled ?: DEFAULT_SLIDE_HOLD_ENABLED).toBool()
    var switchImeModeState = ImeSwitchMode.entries[settings?.switchImeMode ?: DEFAULT_SWITCH_IME_MODE]
    var holdKeyActionState =
        HoldKeyAction.entries.getOrElse(settings?.holdKeyAction ?: DEFAULT_HOLD_KEY_ACTION) { HoldKeyAction.None }
    var backspaceHoldActionState =
        BackspaceHoldAction.entries.getOrElse(
            settings?.backspaceHoldAction ?: DEFAULT_BACKSPACE_HOLD_ACTION,
        ) { BackspaceHoldAction.DeleteWord }
    var holdDurationMsState =
        (settings?.holdDurationMs ?: DEFAULT_HOLD_DURATION_MS)
            .coerceIn(MIN_HOLD_DURATION_MS, MAX_HOLD_DURATION_MS)
            .toFloat()
    var holdDurationMsSliderState by remember(holdDurationMsState) { mutableFloatStateOf(holdDurationMsState) }
    var numericLayoutOrderState =
        NumericLayoutOrder.entries.getOrElse(
            settings?.numericLayoutOrder ?: DEFAULT_NUMERIC_LAYOUT_ORDER,
        ) { NumericLayoutOrder.Default }

    val snackbarHostState = remember { SnackbarHostState() }

    val scrollState = rememberScrollState()

    val resources = LocalResources.current

    fun updateBehavior() {
        appSettingsViewModel.updateBehavior(
            BehaviorUpdate(
                id = 1,
                minSwipeLength = minSwipeLengthState.toInt(),
                slideSensitivity = slideSensitivityState.toInt(),
                slideEnabled = slideEnabledState.toInt(),
                slideCursorMovementMode = slideCursorMovementModeState.ordinal,
                slideSpacebarDeadzoneEnabled = slideSpacebarDeadzoneEnabledState.toInt(),
                slideBackspaceDeadzoneEnabled = slideBackspaceDeadzoneEnabledState.toInt(),
                autoCapitalize = autoCapitalizeState.toInt(),
                spacebarMultiTaps = spacebarMultiTapsState.toInt(),
                switchToLettersAfterSpace = switchToLettersAfterSpaceState.toInt(),
                dragReturnEnabled = dragReturnEnabledState.toInt(),
                circularDragEnabled = circularDragEnabledState.toInt(),
                clockwiseDragAction = clockwiseDragActionState.ordinal,
                counterclockwiseDragAction = counterclockwiseDragActionState.ordinal,
                ghostKeysEnabled = ghostKeysEnabledState.toInt(),
                slideHoldEnabled = slideHoldEnabledState.toInt(),
                switchImeMode = switchImeModeState.ordinal,
                holdKeyAction = holdKeyActionState.ordinal,
                holdDurationMs = holdDurationMsState.roundToInt(),
                numericLayoutOrder = numericLayoutOrderState.ordinal,
                backspaceHoldAction = backspaceHoldActionState.ordinal,
            ),
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            SimpleTopAppBar(
                text = stringResource(R.string.behavior),
                navController = navController,
            )
        },
        content = { padding ->
            Column(
                modifier =
                    Modifier
                        .padding(padding)
                        .verticalScroll(scrollState)
                        .background(color = MaterialTheme.colorScheme.surface)
                        .imePadding(),
            ) {
                ProvidePreferenceTheme {
                    SwitchPreference(
                        value = autoCapitalizeState,
                        onValueChange = {
                            autoCapitalizeState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.auto_capitalize))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Abc,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = spacebarMultiTapsState,
                        onValueChange = {
                            spacebarMultiTapsState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.spacebar_multitaps))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SpaceBar,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = switchToLettersAfterSpaceState,
                        onValueChange = {
                            switchToLettersAfterSpaceState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.switch_to_letters_after_space))
                        },
                        summary = {
                            Text(stringResource(R.string.switch_to_letters_after_space_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Abc,
                                contentDescription = null,
                            )
                        },
                    )
                    SliderPreference(
                        value = minSwipeLengthState,
                        sliderValue = minSwipeLengthSliderState,
                        onValueChange = {
                            minSwipeLengthState = it
                            updateBehavior()
                        },
                        onSliderValueChange = { minSwipeLengthSliderState = it },
                        valueRange = 0f..200f,
                        title = {
                            val minSwipeLengthStr =
                                stringResource(
                                    R.string.min_swipe_length,
                                    minSwipeLengthSliderState.toInt().toString(),
                                )
                            Text(minSwipeLengthStr)
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Swipe,
                                contentDescription = null,
                            )
                        },
                    )
                    SettingsDivider()
                    SwitchPreference(
                        value = slideEnabledState,
                        onValueChange = {
                            slideEnabledState = it
                            if (slideHoldEnabledState) {
                                slideHoldEnabledState = false
                            }
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.slide_enable))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SpaceBar,
                                contentDescription = stringResource(R.string.slide_enable),
                            )
                        },
                    )
                    ListPreference(
                        enabled = slideEnabledState,
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = slideCursorMovementModeState,
                        onValueChange = {
                            slideCursorMovementModeState = it
                            updateBehavior()
                        },
                        values = CursorAccelerationMode.entries,
                        valueToText = {
                            AnnotatedString(resources.getString(it.resId))
                        },
                        title = {
                            Text(stringResource(R.string.slide_cursor_movement_mode))
                        },
                        summary = {
                            Text(stringResource(slideCursorMovementModeState.resId))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = null,
                            )
                        },
                    )
                    SliderPreference(
                        valueRange = 1f..50f,
                        value = slideSensitivityState,
                        sliderValue = slideSensitivitySliderState,
                        onSliderValueChange = { slideSensitivitySliderState = it },
                        onValueChange = {
                            slideSensitivityState = it
                            updateBehavior()
                        },
                        enabled = slideEnabledState,
                        title = {
                            val slideSensitivityStr =
                                stringResource(
                                    R.string.slide_sensitivity,
                                    slideSensitivitySliderState
                                        .toInt()
                                        .toString(),
                                )
                            Text(slideSensitivityStr)
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = slideSpacebarDeadzoneEnabledState,
                        onValueChange = {
                            slideSpacebarDeadzoneEnabledState = it
                            updateBehavior()
                        },
                        enabled = slideEnabledState,
                        title = {
                            Text(stringResource(R.string.slide_spacebar_deadzone_enable))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SpaceBar,
                                contentDescription = stringResource(R.string.slide_spacebar_deadzone_enable),
                            )
                        },
                    )
                    SwitchPreference(
                        value = slideBackspaceDeadzoneEnabledState,
                        onValueChange = {
                            slideBackspaceDeadzoneEnabledState = it
                            updateBehavior()
                        },
                        enabled = slideEnabledState,
                        title = {
                            Text(stringResource(R.string.slide_backspace_deadzone_enable))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = stringResource(R.string.slide_backspace_deadzone_enable),
                            )
                        },
                    )
                    SwitchPreference(
                        value = slideHoldEnabledState,
                        onValueChange = {
                            slideHoldEnabledState = it
                            if (slideEnabledState) {
                                slideEnabledState = false
                            }

                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.slide_hold_enable))
                        },
                        summary = {
                            Text(stringResource(R.string.slide_hold_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.SwipeRight,
                                contentDescription = stringResource(R.string.slide_hold_enable),
                            )
                        },
                    )
                    SettingsDivider()
                    SwitchPreference(
                        value = dragReturnEnabledState,
                        onValueChange = {
                            dragReturnEnabledState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.drag_return_enable))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.UTurnRight,
                                contentDescription = stringResource(R.string.drag_return_enable),
                            )
                        },
                    )
                    SwitchPreference(
                        value = circularDragEnabledState,
                        onValueChange = {
                            circularDragEnabledState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.circular_drag_enable))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Circle,
                                contentDescription = stringResource(R.string.circular_drag_enable),
                            )
                        },
                    )
                    ListPreference(
                        enabled = circularDragEnabledState,
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = clockwiseDragActionState,
                        onValueChange = {
                            clockwiseDragActionState = it
                            updateBehavior()
                        },
                        values = CircularDragAction.entries,
                        valueToText = {
                            AnnotatedString(resources.getString(it.resId))
                        },
                        title = {
                            Text(stringResource(R.string.clockwise_drag_action))
                        },
                        summary = {
                            Text(stringResource(clockwiseDragActionState.resId))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.RotateRight,
                                contentDescription = stringResource(R.string.clockwise_drag_action),
                            )
                        },
                    )
                    ListPreference(
                        enabled = circularDragEnabledState,
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = counterclockwiseDragActionState,
                        onValueChange = {
                            counterclockwiseDragActionState = it
                            updateBehavior()
                        },
                        values = CircularDragAction.entries,
                        valueToText = {
                            AnnotatedString(resources.getString(it.resId))
                        },
                        title = {
                            Text(stringResource(R.string.counterclockwise_drag_action))
                        },
                        summary = {
                            Text(stringResource(counterclockwiseDragActionState.resId))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.RotateLeft,
                                contentDescription = stringResource(R.string.counterclockwise_drag_action),
                            )
                        },
                    )
                    SwitchPreference(
                        value = ghostKeysEnabledState,
                        onValueChange = {
                            ghostKeysEnabledState = it
                            updateBehavior()
                        },
                        title = {
                            Text(stringResource(R.string.ghost_keys_enable))
                        },
                        summary = {
                            Text(stringResource(R.string.ghost_keys_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.BorderInner,
                                contentDescription = stringResource(R.string.ghost_keys_enable),
                            )
                        },
                    )
                    SettingsDivider()
                    ListPreference(
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = holdKeyActionState,
                        onValueChange = {
                            holdKeyActionState = it
                            updateBehavior()
                        },
                        values = HoldKeyAction.entries,
                        valueToText = { AnnotatedString(resources.getString(it.resId)) },
                        title = { Text(stringResource(R.string.hold_key_action)) },
                        summary = { Text(stringResource(holdKeyActionState.resId)) },
                        icon = { Icon(imageVector = Icons.Outlined.TouchApp, contentDescription = null) },
                    )
                    ListPreference(
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = backspaceHoldActionState,
                        onValueChange = {
                            backspaceHoldActionState = it
                            updateBehavior()
                        },
                        values = BackspaceHoldAction.entries,
                        valueToText = { AnnotatedString(resources.getString(it.resId)) },
                        title = { Text(stringResource(R.string.backspace_hold_action)) },
                        summary = { Text(stringResource(backspaceHoldActionState.resId)) },
                        icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.Backspace, contentDescription = null) },
                    )
                    SliderPreference(
                        value = holdDurationMsState,
                        sliderValue = holdDurationMsSliderState,
                        onSliderValueChange = { holdDurationMsSliderState = snapHoldDurationMs(it) },
                        onValueChange = {
                            val snapped = snapHoldDurationMs(it)
                            holdDurationMsState = snapped
                            holdDurationMsSliderState = snapped
                            updateBehavior()
                        },
                        valueRange = MIN_HOLD_DURATION_MS.toFloat()..MAX_HOLD_DURATION_MS.toFloat(),
                        title = {
                            Text(stringResource(R.string.hold_duration, holdDurationMsSliderState.roundToInt().toString()))
                        },
                        summary = { Text(stringResource(R.string.hold_duration_description)) },
                        icon = { Icon(imageVector = Icons.Outlined.Timer, contentDescription = null) },
                    )
                    ListPreference(
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = numericLayoutOrderState,
                        onValueChange = {
                            numericLayoutOrderState = it
                            updateBehavior()
                        },
                        values = NumericLayoutOrder.entries,
                        valueToText = { AnnotatedString(resources.getString(it.resId)) },
                        title = { Text(stringResource(R.string.numeric_layout_order)) },
                        summary = { Text(stringResource(numericLayoutOrderState.resId)) },
                        icon = { Icon(imageVector = Icons.Outlined.Dialpad, contentDescription = null) },
                    )
                    SettingsDivider()
                    ListPreference(
                        type = ListPreferenceType.DROPDOWN_MENU,
                        value = switchImeModeState,
                        onValueChange = {
                            switchImeModeState = it
                            updateBehavior()
                        },
                        values = ImeSwitchMode.entries,
                        valueToText = {
                            AnnotatedString(resources.getString(it.resId))
                        },
                        title = {
                            Text(stringResource(R.string.ime_switch_mode))
                        },
                        summary = {
                            Text(stringResource(switchImeModeState.resId))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Keyboard,
                                contentDescription = null,
                            )
                        },
                    )
                    SettingsDivider()
                    TestOutTextField()
                }
            }
        },
    )
}

private fun snapHoldDurationMs(rawValue: Float): Float =
    ((rawValue / HOLD_DURATION_STEP_MS).roundToInt() * HOLD_DURATION_STEP_MS)
        .coerceIn(MIN_HOLD_DURATION_MS, MAX_HOLD_DURATION_MS)
        .toFloat()
