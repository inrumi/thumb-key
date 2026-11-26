package com.dessalines.thumbkey

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.ColorInt
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.dessalines.thumbkey.db.DEFAULT_CLIPBOARD_HISTORY_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_DISABLE_FULLSCREEN_EDITOR
import com.dessalines.thumbkey.db.DEFAULT_POSITION
import com.dessalines.thumbkey.db.DEFAULT_SHOW_ON_SCREEN_KEYBOARD
import com.dessalines.thumbkey.db.DEFAULT_SWITCH_IME_MODE
import com.dessalines.thumbkey.db.DEFAULT_USE_PRIVATE_CLIPBOARD
import com.dessalines.thumbkey.ui.components.keyboard.SketchState
import com.dessalines.thumbkey.utils.ImeSwitchMode
import com.dessalines.thumbkey.utils.KeyboardDefinition
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.KeyboardPosition
import com.dessalines.thumbkey.utils.TAG
import com.dessalines.thumbkey.utils.ThumbKeyClipboardManager
import com.dessalines.thumbkey.utils.resolveKeyboardDefinition
import com.dessalines.thumbkey.utils.toBool

class IMEService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private fun syncKeyboardDefinition() {
        val app = application as ThumbkeyApplication
        val appSettings = app.appSettingsRepository.appSettings.value
        val layoutIndex = appSettings?.keyboardLayout
        if (currentKeyboardDefinition == null && layoutIndex != null) {
            currentKeyboardDefinition =
                resolveKeyboardDefinition(KeyboardLayout.entries[layoutIndex], appSettings.keyModifications)
        }
    }

    private fun syncFloatingMode() {
        val app = application as ThumbkeyApplication
        isFloatingMode =
            isFloatingPosition(
                app.appSettingsRepository.appSettings.value
                    ?.position,
            )
        if (!isFloatingMode) {
            floatingPanelBounds = null
            inlineSuggestionsBarBounds = null
        }
    }

    private fun setupView(): ComposeKeyboardView {
        val app = application as ThumbkeyApplication
        syncKeyboardDefinition()

        val view = ComposeKeyboardView(this, app.appSettingsRepository, app.clipboardRepository)
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        view.let {
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
        }
        return view
    }

    var currentKeyboardDefinition: KeyboardDefinition? = null
    var inputSessionVersion by mutableIntStateOf(0)
        private set
    var editorImeOptions by mutableIntStateOf(EditorInfo.IME_ACTION_NONE)
        private set
    val inlineSuggestionViews = mutableStateListOf<View>()
    private var inlineSuggestionRequestToken: Long = 0L

    @ColorInt
    var inlineSuggestionTextColor: Int = Color.WHITE

    @ColorInt
    var inlineSuggestionSubtextColor: Int = Color.LTGRAY
    var inlineSuggestionDarkTheme: Boolean = true

    fun clearInlineSuggestions() {
        inlineSuggestionRequestToken++
        inlineSuggestionViews.clear()
        if (inlineSuggestionsBarBounds != null) {
            inlineSuggestionsBarBounds = null
            if (isFloatingMode) window?.window?.decorView?.requestLayout()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // While set, the keys type into this field of the keyboard's own, like sketch mode's
    // search, instead of the app's
    private var inputRedirect: InputConnection? = null

    fun setInputRedirect(connection: InputConnection?) {
        inputRedirect = connection
    }

    // The framework's connection is null while no text field is bound, and the framework itself
    // calls this. Kotlin would check a platform value returned as non-null and throw, so pass it
    // through unchecked, as the Java method does. Nullable would break every caller.
    override fun getCurrentInputConnection(): InputConnection = inputRedirect ?: unchecked(super.getCurrentInputConnection())

    /** The app's text field, even while the keys type into the keyboard's own field. */
    val appInputConnection: InputConnection get() = super.getCurrentInputConnection()

    // Sketch mode's drawing, kept while the keyboard is closed and opened again
    val sketchState = SketchState()
    private var clipboardManager: ThumbKeyClipboardManager? = null
    private var isFloatingMode: Boolean = false
    private var floatingPanelBounds: Rect? = null
    private var inlineSuggestionsBarBounds: Rect? = null
    private var imeNavBarBottomPx: Int = 0

    private fun isFloatingPosition(position: Int?): Boolean = (position ?: DEFAULT_POSITION) == KeyboardPosition.Floating.ordinal

    fun onFloatingBounds(bounds: IntRect) {
        val rect = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        if (rect != floatingPanelBounds) {
            floatingPanelBounds = rect
            window?.window?.decorView?.requestLayout()
        }
    }

    fun onInlineSuggestionsBarBounds(bounds: IntRect?) {
        val rect = bounds?.let { Rect(it.left, it.top, it.right, it.bottom) }
        if (rect != inlineSuggestionsBarBounds) {
            inlineSuggestionsBarBounds = rect
            if (isFloatingMode) window?.window?.decorView?.requestLayout()
        }
    }

    fun onNavBarBottomInsetChanged(bottomPx: Int) {
        if (bottomPx != imeNavBarBottomPx) {
            imeNavBarBottomPx = bottomPx
            if (isFloatingMode) {
                window?.window?.decorView?.requestLayout()
            }
        }
    }

    private fun applyFloatingWindowLayout(
        win: Window,
        isFullscreen: Boolean = isFullscreenMode,
        isCandidatesOnly: Boolean = false,
    ) {
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        try {
            val inputArea = win.decorView.findViewById<View>(android.R.id.inputArea)
            if (isFloatingMode && !isFullscreen && !isCandidatesOnly) {
                if (inputArea != null) {
                    val lp = inputArea.layoutParams
                    if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                        inputArea.layoutParams = lp
                    }
                    val panel = inputArea.parent as? LinearLayout
                    if (panel != null &&
                        panel.orientation == LinearLayout.VERTICAL &&
                        (panel.gravity and Gravity.VERTICAL_GRAVITY_MASK) != Gravity.BOTTOM
                    ) {
                        panel.gravity = Gravity.BOTTOM
                    }
                }
                if (win.attributes.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    win.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            } else {
                if (inputArea != null) {
                    val lp = inputArea.layoutParams
                    if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        inputArea.layoutParams = lp
                    }
                }
                val targetHeight =
                    if (isFullscreen) {
                        ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                if (win.attributes.height != targetHeight) {
                    win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)
                }
            }
        } catch (detached: IllegalArgumentException) {
            Log.d(TAG, "IME window not attached while sizing it: ${detached.message}")
        }
    }

    override fun onCreateInputView(): View {
        val view = setupView()
        window?.window?.let { applyFloatingWindowLayout(it) }
        return view
    }

    /**
     * Called every time the keyboard is brought up or focus moves to an editor.
     */
    override fun onStartInput(
        attribute: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInput(attribute, restarting)
        setInputRedirect(null)
        syncKeyboardDefinition()
        syncFloatingMode()
        editorImeOptions = attribute?.imeOptions ?: EditorInfo.IME_ACTION_NONE
        if (!restarting) {
            // A new keyboard opens on the letters: close sketch mode's search and lists, but keep
            // its drawing
            sketchState.searching = false
            sketchState.list = null
            clearInlineSuggestions()
            inputSessionVersion++
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        syncFloatingMode()
        window?.window?.let { applyFloatingWindowLayout(it) }
    }

    override fun onFinishInput() {
        clearInlineSuggestions()
        super.onFinishInput()
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!UiVersions.getVersions(uiExtras).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val maxChipWidthPx = (resources.displayMetrics.widthPixels - dpToPx(12)).coerceAtLeast(dpToPx(96))
        val chipBgRes =
            if (inlineSuggestionDarkTheme) {
                R.drawable.autofill_chip_background_dark
            } else {
                R.drawable.autofill_chip_background_light
            }
        val styleBundle =
            UiVersions
                .newStylesBuilder()
                .addStyle(
                    InlineSuggestionUi
                        .newStyleBuilder()
                        .setTitleStyle(
                            TextViewStyle
                                .Builder()
                                .setTextColor(inlineSuggestionTextColor)
                                .setTextSize(13f)
                                .build(),
                        ).setSubtitleStyle(
                            TextViewStyle
                                .Builder()
                                .setTextColor(inlineSuggestionSubtextColor)
                                .setTextSize(11f)
                                .build(),
                        ).setChipStyle(
                            ViewStyle
                                .Builder()
                                .setBackground(Icon.createWithResource(this, chipBgRes))
                                .setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                                .build(),
                        ).build(),
                ).build()
        val spec =
            InlinePresentationSpec
                .Builder(Size(dpToPx(48), dpToPx(36)), Size(maxChipWidthPx, dpToPx(44)))
                .setStyle(styleBundle)
                .build()
        return InlineSuggestionsRequest
            .Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val token = ++inlineSuggestionRequestToken
        inlineSuggestionViews.clear()
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) return true
        val slots = MutableList<View?>(suggestions.size) { null }
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(this, Size(ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(44)), mainExecutor) { view ->
                if (inlineSuggestionRequestToken != token) return@inflate
                slots[index] = view
                inlineSuggestionViews.clear()
                inlineSuggestionViews.addAll(slots.filterNotNull())
            }
        }
        return true
    }

    // Lifecycle Methods
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    private fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)

    override val lifecycle = lifecycleRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val app = application as ThumbkeyApplication
        isFloatingMode =
            isFloatingPosition(
                app.appSettingsRepository.appSettings.value
                    ?.position,
            )
        app.appSettingsRepository.appSettings.observe(this) { settings ->
            val nextFloating = isFloatingPosition(settings?.position)
            if (nextFloating != isFloatingMode) {
                isFloatingMode = nextFloating
                if (!nextFloating) {
                    floatingPanelBounds = null
                    inlineSuggestionsBarBounds = null
                }
                window?.window?.let { applyFloatingWindowLayout(it) }
                updateFullscreenMode()
                window?.window?.decorView?.let { decorView ->
                    decorView.requestApplyInsets()
                    decorView.requestLayout()
                }
            }
        }

        // Initialize clipboard manager
        clipboardManager = ThumbKeyClipboardManager(this, app.clipboardRepository)
        clipboardManager?.startListening()
        clipboardManager?.clearExpired()
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        applyFloatingWindowLayout(win, isFullscreen, isCandidatesOnly)
    }

    override fun onEvaluateFullscreenMode(): Boolean =
        if (isFloatingMode) {
            false
        } else {
            super.onEvaluateFullscreenMode()
        }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isFloatingMode) return
        val decorView = window?.window?.decorView ?: return
        val decorHeight = decorView.height
        if (decorHeight <= 0) return
        val band = imeNavBarBottomPx.coerceIn(0, decorHeight)
        val top = decorHeight - band
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        val bounds = floatingPanelBounds
        if (bounds != null) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
            outInsets.touchableRegion.set(bounds)
            inlineSuggestionsBarBounds?.let { barBounds ->
                if (!barBounds.isEmpty) {
                    outInsets.touchableRegion.op(barBounds, Region.Op.UNION)
                }
            }
            if (band > 0) {
                outInsets.touchableRegion.op(0, top, decorView.width, decorHeight, Region.Op.UNION)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floatingPanelBounds = null
        inlineSuggestionsBarBounds = null
        window?.window?.let { applyFloatingWindowLayout(it) }
        window?.window?.decorView?.let { decorView ->
            decorView.requestApplyInsets()
            decorView.requestLayout()
        }
    }

    override fun onDestroy() {
        clipboardManager?.stopListening()
        clipboardManager = null
        super.onDestroy()
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // Cursor update Methods
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)

        cursorMoved =
            if (ignoreCursorMove) {
                ignoreCursorMove = false
                false
            } else {
                Log.d(TAG, "cursor moved")
                cursorAnchorInfo.selectionStart != selectionStart ||
                    cursorAnchorInfo.selectionEnd != selectionEnd
            }

        currentKeyboardDefinition?.settings?.textProcessor?.handleCursorUpdate(
            this,
            selectionStart,
            selectionEnd,
            cursorAnchorInfo.selectionStart,
            cursorAnchorInfo.selectionEnd,
        )

        selectionStart = cursorAnchorInfo.selectionStart
        selectionEnd = cursorAnchorInfo.selectionEnd
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val settingsRepo = (application as ThumbkeyApplication).appSettingsRepository
        val settings = settingsRepo.appSettings.getValue()
        val showOnScreenKeyboard =
            (settings?.showOnScreenKeyboard ?: DEFAULT_SHOW_ON_SCREEN_KEYBOARD).toBool()

        // Always call super implementation because Android docs says:
        // "If you override this method you must call through to the superclass implementation."
        // https://developer.android.com/reference/android/inputmethodservice/InputMethodService#onEvaluateInputViewShown()
        return super.onEvaluateInputViewShown() ||
            showOnScreenKeyboard
    }

    // Disable the fullscreen text editor if set by the user
    override fun onUpdateExtractingVisibility(ei: EditorInfo) {
        val settingsRepo = (application as ThumbkeyApplication).appSettingsRepository
        val settings = settingsRepo.appSettings.getValue()
        if ((settings?.disableFullscreenEditor ?: DEFAULT_DISABLE_FULLSCREEN_EDITOR).toBool()) {
            ei.imeOptions =
                ei.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        super.onUpdateExtractingVisibility(ei)
    }

    fun didCursorMove(): Boolean = cursorMoved

    fun ignoreNextCursorMove() {
        // This gets reset on the next call to `onUpdateCursorAnchorInfo`
        ignoreCursorMove = true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Must run before super, which commits any composing text as it stands.
        // Running after it would let the processor write its pending text a second time.
        currentKeyboardDefinition?.settings?.textProcessor?.handleFinishInput(this)
        super.onFinishInputView(finishingInput)
    }

    private var ignoreCursorMove: Boolean = false
    private var cursorMoved: Boolean = false
    private var selectionStart: Int = 0
    private var selectionEnd: Int = 0

    // ViewModelStore Methods
    override val viewModelStore = ViewModelStore()

    // SaveStateRegistry Methods
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry

    fun clipboardUsePrivate(): Boolean {
        val settingsRepo = (application as ThumbkeyApplication).appSettingsRepository
        val settings = settingsRepo.appSettings.getValue()
        val clipboardHistoryEnabled = (settings?.clipboardHistoryEnabled ?: DEFAULT_CLIPBOARD_HISTORY_ENABLED).toBool()
        val usePrivateClipboard = (settings?.usePrivateClipboard ?: DEFAULT_USE_PRIVATE_CLIPBOARD).toBool()
        return clipboardHistoryEnabled && usePrivateClipboard
    }

    fun switchImeMode(): ImeSwitchMode {
        val settingsRepo = (application as ThumbkeyApplication).appSettingsRepository
        val settings = settingsRepo.appSettings.getValue()
        return ImeSwitchMode.entries[settings?.switchImeMode ?: DEFAULT_SWITCH_IME_MODE]
    }

    fun clipboardAddPrivateClip(text: String): Unit? = clipboardManager?.addPrivateClip(text)

    fun clipboardWasLastCopyDoneViaSystem(): Boolean = clipboardManager?.wasLastCopyOperationDoneViaSystem() ?: true

    fun clipboardGetLastClip(): String? = clipboardManager?.getLastClip()
}

// Returns `value` without Kotlin's null check, for a platform value that may be null
@Suppress("UNCHECKED_CAST")
private fun <T> unchecked(value: T?): T = value as T
