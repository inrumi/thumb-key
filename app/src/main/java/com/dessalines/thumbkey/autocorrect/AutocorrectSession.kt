package com.dessalines.thumbkey.autocorrect

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dessalines.thumbkey.IMEService
import com.dessalines.thumbkey.db.AppSettings
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ADDITIONAL_LANGUAGES
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_BEFORE_ENTER
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_BEFORE_PUNCTUATION
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_CONFIDENCE
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_CONTEXT_MODEL_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_DICTIONARY_CAPITALS
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_FIX_APOSTROPHES
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_LEARN_WORDS
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_SKIP_ALL_CAPS
import com.dessalines.thumbkey.db.DEFAULT_AUTOCORRECT_UNDO_ON_BACKSPACE
import com.dessalines.thumbkey.db.DEFAULT_AUTO_SPACE_AFTER_PUNCTUATION
import com.dessalines.thumbkey.db.DEFAULT_REMOVE_SPACE_BEFORE_PUNCTUATION
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTIONS_EMOJI_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTIONS_ENABLED
import com.dessalines.thumbkey.utils.KeyboardDefinition
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.allowsAutocorrectForInputType
import com.dessalines.thumbkey.utils.isNoPersonalizedLearning
import com.dessalines.thumbkey.utils.isUriOrEmailOrPasswordInputType
import com.dessalines.thumbkey.utils.toBool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * Stateful per-field controller bridging Thumb-Key IME key actions with
 * [AutocorrectEngine], [SmartPunctuation], async `.wmlm` context scoring,
 * and the reactive [SuggestionStripState].
 */
class AutocorrectSession(
    private val dictionaryManager: DictionaryManager,
) {
    data class UndoChip(
        val originalWord: String,
        val replacedWord: String,
    )

    data class SuggestionStripState(
        val undoChip: UndoChip? = null,
        val words: List<AutocorrectEngine.WordSuggestion> = emptyList(),
        val emojis: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = undoChip == null && words.isEmpty() && emojis.isEmpty()
    }

    private data class PendingUndo(
        val originalWord: String,
        val replacedWord: String,
        val delimiter: String,
        val fieldGeneration: Long,
        val allowBackspaceUndo: Boolean = true,
    )

    private data class CommittedEntry(
        val word: String,
        val wasKnown: Boolean,
        val wasCorrected: Boolean,
        val atSentenceStart: Boolean,
        val alternatives: List<String>,
    )

    private data class PendingRevision(
        val wrongWord: String,
        val replacement: String,
        val followers: List<String>,
        val languageId: String,
        val generation: Long,
    )

    var stripState by mutableStateOf(SuggestionStripState())
        private set

    private var fieldGeneration: Long = 0L
    private var pendingUndo: PendingUndo? = null
    private var pendingAutoSpace: Boolean = false
    private var shiftToggledOnManually: Boolean = false
    private var wordHadManualShift: Boolean = false
    private var suppressNextCursorClear: Boolean = false
    private var lastSelectionStart: Int = -1
    private var lastSelectionEnd: Int = -1

    private var lastCommittedWord: String? = null
    private var lastCommittedAtSentenceStart: Boolean = false
    private var awaitingManualRepairFor: String? = null
    private var backspacesSinceCommit: Int = 0
    private val recentCommits = ArrayDeque<CommittedEntry>(RECENT_COMMIT_CAPACITY)
    private var commitSeq: Long = 0L
    private var lastRevisedCommitSeq: Long = -1L
    private var queuedFinishedRevision: Pair<List<String>, List<CommittedEntry>>? = null

    private val beamWorkspace = FuzzyBeamSearch.BeamWorkspace()
    private var cachedLayoutTitle: String? = null
    private var cachedProximity: KeyboardProximity = KeyboardProximity.NONE

    private val mainHandler = Handler(Looper.getMainLooper())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lmDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lmScope = CoroutineScope(SupervisorJob() + lmDispatcher)
    private var lmJob: Job? = null
    private var cachedLmModel: WmlmModel? = null
    private var cachedLmSession: WmlmSession? = null

    @Volatile
    private var currentLmSnapshot: WmlmSnapshot? = null

    fun close() {
        lmScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun onStartInput(
        editorInfo: EditorInfo?,
        ime: IMEService? = null,
    ) {
        fieldGeneration++
        pendingUndo = null
        pendingAutoSpace = false
        shiftToggledOnManually = false
        wordHadManualShift = false
        suppressNextCursorClear = false
        lastSelectionStart = -1
        lastSelectionEnd = -1
        lastCommittedWord = null
        awaitingManualRepairFor = null
        backspacesSinceCommit = 0
        recentCommits.clear()
        commitSeq = 0L
        lastRevisedCommitSeq = -1L
        queuedFinishedRevision = null
        currentLmSnapshot = null
        lmJob?.cancel()
        stripState = SuggestionStripState()
        dictionaryManager.correctionStats.endFieldSession()
        if (ime != null) {
            refreshSuggestions(ime)
        }
    }

    fun onCursorUpdated(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        ime: IMEService? = null,
    ) {
        if (newStart == lastSelectionStart && newEnd == lastSelectionEnd) {
            return
        }
        lastSelectionStart = newStart
        lastSelectionEnd = newEnd
        if (suppressNextCursorClear) {
            suppressNextCursorClear = false
            return
        }
        if (newStart != newEnd || newStart != oldStart || newEnd != oldEnd) {
            pendingUndo = null
            pendingAutoSpace = false
            if (newStart != newEnd) {
                recentCommits.clear()
            } else if (recentCommits.isNotEmpty() && ime != null) {
                val before =
                    runCatching { ime.appInputConnection.getTextBeforeCursor(REWRITE_LOOKBACK, 0)?.toString() }
                        .getOrNull()
                if (before != null && !recentCommitsMatchesBuffer(before)) {
                    recentCommits.clear()
                }
            }
            if (ime != null) {
                refreshSuggestions(ime)
            }
        }
    }

    fun onActiveLanguageChanged(
        ime: IMEService,
        layout: KeyboardLayout,
        keyboardDef: KeyboardDefinition,
        settings: AppSettings?,
    ) {
        pendingUndo = null
        pendingAutoSpace = false
        recentCommits.clear()
        commitSeq = 0L
        lastRevisedCommitSeq = -1L
        queuedFinishedRevision = null
        currentLmSnapshot = null
        lmJob?.cancel()
        refreshSuggestions(ime, layout, keyboardDef, settings)
    }

    fun onFinishInput(editorInfo: EditorInfo?) {
        if (!isNoPersonalizedLearning(editorInfo)) {
            dictionaryManager.saveLearningDataAsync()
        }
        pendingAutoSpace = false
        lmJob?.cancel()
    }

    fun onToggleShiftMode(enabled: Boolean) {
        shiftToggledOnManually = enabled
    }

    fun clearUndoState(ime: IMEService? = null) {
        pendingUndo = null
        pendingAutoSpace = false
        if (ime != null) {
            refreshSuggestions(ime)
        } else if (stripState.undoChip != null) {
            stripState = stripState.copy(undoChip = null)
        }
    }

    fun handleCommitText(
        ime: IMEService,
        text: String,
        layout: KeyboardLayout,
        keyboardDef: KeyboardDefinition,
        settings: AppSettings?,
    ) {
        val ic = ime.currentInputConnection
        val editorInfo = ime.currentInputEditorInfo
        val inputType = editorInfo?.inputType ?: ime.editorInputType
        val restrictedField = isUriOrEmailOrPasswordInputType(inputType)
        val allowAutocorrect = allowsAutocorrectForInputType(inputType)
        val allowLearning =
            !isNoPersonalizedLearning(editorInfo) &&
                (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()

        val removeSpaceBeforePunct =
            (settings?.removeSpaceBeforePunctuation ?: DEFAULT_REMOVE_SPACE_BEFORE_PUNCTUATION).toBool()
        val autoSpaceAfterPunct =
            (settings?.autoSpaceAfterPunctuation ?: DEFAULT_AUTO_SPACE_AFTER_PUNCTUATION).toBool()

        val languages = resolveActiveLanguages(layout, settings)
        val primaryLang = languages.firstOrNull() ?: ""
        val beforeCursor = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0) ?: ""

        when (SmartPunctuation.shouldCollapsePendingAutoSpaceBefore(text, beforeCursor, pendingAutoSpace)) {
            SmartPunctuation.CollapseAction.CONSUME_DUPLICATE_SPACE -> {
                pendingAutoSpace = false
                refreshSuggestions(ime, layout, keyboardDef, settings)
                return
            }

            SmartPunctuation.CollapseAction.DELETE_PENDING_SPACE -> {
                suppressNextCursorClear = true
                ic.deleteSurroundingText(1, 0)
                pendingAutoSpace = false
            }

            SmartPunctuation.CollapseAction.NONE -> {
                pendingAutoSpace = false
            }
        }

        val activeUndo = pendingUndo
        if (activeUndo != null && activeUndo.fieldGeneration == fieldGeneration && text.any { !it.isLetterOrDigit() }) {
            dictionaryManager.correctionStats.recordKept(activeUndo.originalWord, activeUndo.replacedWord)
            pendingUndo = null
        } else if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '\'' }) {
            pendingUndo = null
        }

        val freshBefore = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0) ?: ""

        val singlePunct = if (text.length == 1 && SmartPunctuation.isTargetPunctuation(text[0])) text[0] else null
        var huggedSpace = false
        if (singlePunct != null &&
            SmartPunctuation.shouldHugSpaceBefore(
                punct = singlePunct,
                textBeforeCursor = freshBefore,
                primaryLanguage = primaryLang,
                enabled = removeSpaceBeforePunct && !restrictedField,
            )
        ) {
            huggedSpace = true
        }

        val triggerAutocorrect =
            shouldTriggerAutocorrectForCommit(
                text = text,
                allowAutocorrect = allowAutocorrect,
                languages = languages,
                settings = settings,
            )

        val beforeForWord =
            if (huggedSpace && freshBefore.endsWith(' ')) {
                freshBefore.dropLast(1)
            } else {
                freshBefore
            }

        val correctedDelimiter =
            if (triggerAutocorrect) {
                evaluateAndApplyAtBoundary(
                    ic = ic,
                    ime = ime,
                    beforeCursor = beforeForWord,
                    delimiter = text,
                    deleteExtraSpaceBeforeWord = huggedSpace,
                    layout = layout,
                    keyboardDef = keyboardDef,
                    languages = languages,
                    settings = settings,
                    allowLearning = allowLearning,
                )
            } else {
                null
            }

        if (correctedDelimiter == null) {
            if (huggedSpace) {
                suppressNextCursorClear = true
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(text, 1)
                ic.endBatchEdit()
            } else {
                ic.commitText(text, 1)
            }
            if (triggerAutocorrect && allowLearning) {
                observeBoundaryWordIfAny(beforeForWord, languages)
            }
        }

        val isBoundaryDelimiter =
            text == " " ||
                text == "\t" ||
                text == "\n" ||
                text == "\r\n" ||
                singlePunct != null
        val isSentenceEnd =
            text == "\n" ||
                text == "\r\n" ||
                (singlePunct != null && singlePunct in SENTENCE_END_PUNCT_CHARS)

        val contextModelEnabled =
            (settings?.autocorrectContextModelEnabled ?: DEFAULT_AUTOCORRECT_CONTEXT_MODEL_ENABLED).toBool()

        if (isBoundaryDelimiter) {
            val boundaryExtracted = extractWordAtEnd(beforeForWord)
            if (correctedDelimiter != null) {
                for (tok in correctedDelimiter.split(' ')) {
                    if (tok.isNotEmpty()) {
                        appendRecentCommit(
                            CommittedEntry(
                                word = tok,
                                wasKnown = true,
                                wasCorrected = true,
                                atSentenceStart = false,
                                alternatives = emptyList(),
                            ),
                        )
                    }
                }
            } else if (boundaryExtracted != null) {
                val bPrev = extractPreviousContext(beforeForWord, boundaryExtracted.startIndex)
                val canTrackRevision =
                    !isSentenceEnd &&
                        allowAutocorrect &&
                        contextModelEnabled &&
                        languages.isNotEmpty() &&
                        (settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool() &&
                        dictionaryManager.isReady &&
                        !wordHadManualShift &&
                        (bPrev.atSentenceStart || !WordCase.startsUpperCase(boundaryExtracted.word))
                if (canTrackRevision) {
                    val revConfig = buildConfig(settings, editorInfo, contextModelEnabled = true)
                    val known = isWordKnown(boundaryExtracted.word, languages)
                    val alts =
                        AutocorrectEngine.revisionCandidates(
                            typed = boundaryExtracted.word,
                            atSentenceStart = bPrev.atSentenceStart,
                            languages = languages,
                            proximity = proximityFor(keyboardDef),
                            config = revConfig,
                            stats = dictionaryManager.correctionStats,
                            lexicon = dictionaryManager.userLexicon,
                            dictionaryManager = dictionaryManager,
                            workspace = beamWorkspace,
                            limit = REVISION_CANDIDATE_LIMIT,
                        )
                    appendRecentCommit(
                        CommittedEntry(
                            word = boundaryExtracted.word,
                            wasKnown = known,
                            wasCorrected = false,
                            atSentenceStart = bPrev.atSentenceStart,
                            alternatives = alts,
                        ),
                    )
                } else {
                    appendRecentCommit(
                        CommittedEntry(
                            word = boundaryExtracted.word,
                            wasKnown = true,
                            wasCorrected = true,
                            atSentenceStart = bPrev.atSentenceStart,
                            alternatives = emptyList(),
                        ),
                    )
                }
            }
            if (isSentenceEnd) {
                if (allowAutocorrect && contextModelEnabled && recentCommits.size >= 2) {
                    val wordStart = boundaryExtracted?.startIndex ?: beforeForWord.length
                    val prefixWords = extractSentenceWords(beforeForWord, wordStart)
                    val endWords =
                        correctedDelimiter?.split(' ')?.filter { it.isNotEmpty() }
                            ?: listOfNotNull(boundaryExtracted?.word)
                    val finishedWords = (prefixWords + endWords).takeLast(MAX_SENTENCE_CONTEXT_WORDS)
                    queuedFinishedRevision = finishedWords to recentCommits.toList()
                }
                recentCommits.clear()
                commitSeq++
            }
        }

        if (singlePunct != null) {
            val textAfterPunct = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0) ?: ""
            val afterCursor = ic.getTextAfterCursor(1, 0)
            if (SmartPunctuation.shouldInsertAutoSpaceAfter(
                    punct = singlePunct,
                    textBeforeCursorAfterPunct = textAfterPunct,
                    textAfterCursor = afterCursor,
                    enabled = autoSpaceAfterPunct,
                    isRestrictedField = restrictedField,
                )
            ) {
                suppressNextCursorClear = true
                ic.commitText(" ", 1)
                pendingAutoSpace = true
                val curUndo = pendingUndo
                if (correctedDelimiter != null && curUndo != null) {
                    pendingUndo = curUndo.copy(delimiter = curUndo.delimiter + " ")
                }
            }
        }

        if (text.any { it.isLetterOrDigit() }) {
            if (shiftToggledOnManually) {
                wordHadManualShift = true
                shiftToggledOnManually = false
            }
            backspacesSinceCommit = 0
        } else if (text.any { !it.isLetterOrDigit() && it != '\'' && it != '’' }) {
            wordHadManualShift = false
            shiftToggledOnManually = false
        }

        refreshSuggestions(ime, layout, keyboardDef, settings)
    }

    fun handleDeleteKey(
        ime: IMEService,
        isRepeatTick: Boolean = false,
    ): Boolean {
        pendingAutoSpace = false
        if (isRepeatTick) {
            pendingUndo = null
            awaitingManualRepairFor = null
            recentCommits.clear()
            if (stripState.undoChip != null) {
                stripState = stripState.copy(undoChip = null)
            }
            return false
        }

        val ic = ime.currentInputConnection
        val undo = pendingUndo
        val settings = ime.currentAppSettings
        val undoEnabled = (settings?.autocorrectUndoOnBackspace ?: DEFAULT_AUTOCORRECT_UNDO_ON_BACKSPACE).toBool()
        if (undo != null && undo.allowBackspaceUndo && undoEnabled && undo.fieldGeneration == fieldGeneration) {
            val expectedSuffix = undo.replacedWord + undo.delimiter
            val before = ic.getTextBeforeCursor(expectedSuffix.length + 4, 0) ?: ""
            if (before.endsWith(expectedSuffix)) {
                restoreUndoLocked(ic, ime, undo, settings)
                refreshSuggestions(ime)
                return true
            }
            pendingUndo = null
        } else if (undo != null && undo.allowBackspaceUndo && !undoEnabled) {
            // Backspace edits the buffer when Backspace-undo is turned off; dismiss undo pill.
            pendingUndo = null
        }

        if (recentCommits.isNotEmpty()) {
            val before = runCatching { ic.getTextBeforeCursor(4, 0)?.toString() }.getOrNull() ?: ""
            if (before.isEmpty() || !isTokenChar(before.last())) {
                recentCommits.clear()
            }
        }

        val prevWord = lastCommittedWord
        if (prevWord != null && awaitingManualRepairFor == null) {
            backspacesSinceCommit++
            if (backspacesSinceCommit >= prevWord.length) {
                awaitingManualRepairFor = prevWord
                lastCommittedWord = null
            }
        }

        return false
    }

    fun scheduleRefreshSuggestions(ime: IMEService) {
        val ref = WeakReference(ime)
        mainHandler.post {
            ref.get()?.let { refreshSuggestions(it) }
        }
    }

    fun handleDeleteViaManipulation(ime: IMEService? = null) {
        clearUndoState()
        lastCommittedWord = null
        awaitingManualRepairFor = null
        recentCommits.clear()
        if (ime != null) {
            refreshSuggestions(ime)
        }
    }

    fun handleReplaceLastText(ime: IMEService? = null) {
        clearUndoState()
        lastCommittedWord = null
        recentCommits.clear()
        if (ime != null) {
            refreshSuggestions(ime)
        }
    }

    fun handleImeCompleteAction(
        ime: IMEService,
        layout: KeyboardLayout,
        keyboardDef: KeyboardDefinition,
        settings: AppSettings?,
    ) {
        val ic = ime.currentInputConnection
        pendingAutoSpace = false
        val beforeEnter = (settings?.autocorrectBeforeEnter ?: DEFAULT_AUTOCORRECT_BEFORE_ENTER).toBool()
        if (!beforeEnter) {
            clearUndoState(ime)
            return
        }
        val inputType = ime.currentInputEditorInfo?.inputType ?: ime.editorInputType
        if (!allowsAutocorrectForInputType(inputType)) return
        val languages = resolveActiveLanguages(layout, settings)
        if (languages.isEmpty()) return

        val before = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0) ?: return
        val editorInfo = ime.currentInputEditorInfo
        val allowLearning =
            !isNoPersonalizedLearning(editorInfo) &&
                (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()

        evaluateAndApplyAtBoundary(
            ic = ic,
            ime = ime,
            beforeCursor = before,
            delimiter = "",
            deleteExtraSpaceBeforeWord = false,
            layout = layout,
            keyboardDef = keyboardDef,
            languages = languages,
            settings = settings,
            allowLearning = allowLearning,
        )
        refreshSuggestions(ime, layout, keyboardDef, settings)
    }

    fun refreshSuggestions(
        ime: IMEService,
        layout: KeyboardLayout = ime.currentKeyboardLayout,
        keyboardDef: KeyboardDefinition = ime.currentKeyboardDefinition ?: layout.keyboardDefinition,
        settings: AppSettings? = ime.currentAppSettings,
    ) {
        val editorInfo = ime.currentInputEditorInfo
        val inputType = editorInfo?.inputType ?: ime.editorInputType
        val restrictedField = isUriOrEmailOrPasswordInputType(inputType)

        if (restrictedField) {
            currentLmSnapshot = null
            if (!stripState.isEmpty) {
                stripState = SuggestionStripState()
            }
            return
        }

        val suggestionsEnabled = (settings?.suggestionsEnabled ?: DEFAULT_SUGGESTIONS_ENABLED).toBool()
        val autocorrectEnabled = (settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool()
        val contextModelEnabled =
            (settings?.autocorrectContextModelEnabled ?: DEFAULT_AUTOCORRECT_CONTEXT_MODEL_ENABLED).toBool()
        val finishedRevision = queuedFinishedRevision
        queuedFinishedRevision = null

        val ic = ime.appInputConnection
        val selectedText = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()
        val cursorWord = extractWordAroundCursor(ic, selectedText)
        val hasMultiWordSelection = !selectedText.isNullOrEmpty() && cursorWord == null

        if (!suggestionsEnabled) {
            if (!stripState.isEmpty) {
                stripState = SuggestionStripState()
            }
            if (autocorrectEnabled && contextModelEnabled) {
                val languages = resolveActiveLanguages(layout, settings)
                if (languages.isNotEmpty()) {
                    dictionaryManager.ensureInitializedAsync()
                    val beforeText =
                        cursorWord?.beforeWordText
                            ?: runCatching { ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString() }.getOrNull()
                            ?: ""
                    val endExtracted = if (cursorWord == null) extractWordAtEnd(beforeText) else null
                    val contextEndIdx =
                        if (cursorWord != null) {
                            beforeText.length
                        } else {
                            endExtracted?.startIndex ?: beforeText.length
                        }
                    val sentenceWords = extractSentenceWords(beforeText, contextEndIdx)
                    val activeLang = languages.first()
                    requestLmUpdate(
                        ime = ime,
                        sentenceWords = sentenceWords,
                        activeLang = activeLang,
                        autocorrectEnabled = true,
                        finishedRevision = finishedRevision,
                    )
                }
            } else if (!contextModelEnabled) {
                currentLmSnapshot = null
            }
            return
        }

        val languages = resolveActiveLanguages(layout, settings)
        if (languages.isEmpty()) {
            if (!stripState.isEmpty) {
                stripState = SuggestionStripState()
            }
            return
        }

        dictionaryManager.ensureInitializedAsync()
        val beforeCursor =
            cursorWord?.beforeWordText
                ?: runCatching { ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString() }.getOrNull()
                ?: ""
        val contextEndIdx = beforeCursor.length
        val sentenceWords = extractSentenceWords(beforeCursor, contextEndIdx)
        val prevCtx = extractPreviousContext(beforeCursor, contextEndIdx)
        val activeLang = languages.first()
        if (contextModelEnabled) {
            requestLmUpdate(
                ime = ime,
                sentenceWords = sentenceWords,
                activeLang = activeLang,
                autocorrectEnabled = autocorrectEnabled,
                finishedRevision = finishedRevision,
            )
        } else {
            currentLmSnapshot = null
        }

        val matchingSnapshot =
            if (contextModelEnabled) {
                currentLmSnapshot?.takeIf { snap ->
                    snap.languageId == activeLang && snap.matchesContext(sentenceWords)
                }
            } else {
                null
            }

        val activeUndoChip =
            pendingUndo
                ?.takeIf { it.fieldGeneration == fieldGeneration }
                ?.let { UndoChip(it.originalWord, it.replacedWord) }

        val config = buildConfig(settings, editorInfo, contextModelEnabled)
        val emojiEnabled = (settings?.suggestionsEmojiEnabled ?: DEFAULT_SUGGESTIONS_EMOJI_ENABLED).toBool()

        val words: List<AutocorrectEngine.WordSuggestion>
        val emojis: List<String>

        if (!dictionaryManager.isReady && dictionaryManager.userLexicon.wordCount() == 0) {
            words = emptyList()
            emojis = emptyList()
        } else if (cursorWord != null) {
            val word = cursorWord.word
            val proximity = proximityFor(keyboardDef)
            val isTypingAtEnd = !cursorWord.isSelection && cursorWord.rightLen == 0
            words =
                AutocorrectEngine.suggestMidWord(
                    typed = word,
                    previousWord = prevCtx.previousWord,
                    atSentenceStart = prevCtx.atSentenceStart,
                    manuallyShifted = if (isTypingAtEnd) wordHadManualShift else false,
                    languages = languages,
                    proximity = proximity,
                    config = config,
                    stats = dictionaryManager.correctionStats,
                    memory = dictionaryManager.correctionMemory,
                    lexicon = dictionaryManager.userLexicon,
                    dictionaryManager = dictionaryManager,
                    workspace = beamWorkspace,
                    lmSnapshot = matchingSnapshot,
                    limit = SUGGESTION_LIMIT,
                )
            emojis =
                if (emojiEnabled) {
                    dictionaryManager.emojiSuggester.suggest(
                        word = word,
                        previousWord = prevCtx.previousWord.orEmpty(),
                        limit = EMOJI_LIMIT,
                    )
                } else {
                    emptyList()
                }
        } else if (hasMultiWordSelection) {
            words = emptyList()
            emojis = emptyList()
        } else {
            words =
                AutocorrectEngine.suggestNextWords(
                    previousWord = prevCtx.previousWord,
                    atSentenceStart = prevCtx.atSentenceStart,
                    languages = languages,
                    lexicon = dictionaryManager.userLexicon,
                    dictionaryManager = dictionaryManager,
                    capitalizeNames = config.capitalizeNames,
                    lmSnapshot = matchingSnapshot,
                    limit = SUGGESTION_LIMIT,
                )
            val prevWord = prevCtx.previousWord
            emojis =
                if (emojiEnabled && prevWord != null && !prevCtx.atSentenceStart && beforeCursor.endsWithWhitespaceOrPunct()) {
                    val prevPrev = sentenceWords.dropLast(1).lastOrNull().orEmpty()
                    dictionaryManager.emojiSuggester.suggest(
                        word = prevWord,
                        previousWord = prevPrev,
                        limit = EMOJI_LIMIT,
                    )
                } else {
                    emptyList()
                }
        }

        val nextState =
            SuggestionStripState(
                undoChip = activeUndoChip,
                words = words,
                emojis = emojis,
            )
        if (nextState != stripState) {
            stripState = nextState
        }
    }

    fun applySuggestion(
        ime: IMEService,
        suggestion: AutocorrectEngine.WordSuggestion,
        onAutoCapitalizeDone: () -> Unit = {},
    ) {
        val ic = ime.appInputConnection
        val settings = ime.currentAppSettings
        val editorInfo = ime.currentInputEditorInfo
        val layout = ime.currentKeyboardLayout
        val languages = resolveActiveLanguages(layout, settings)
        val allowLearning =
            !isNoPersonalizedLearning(editorInfo) &&
                (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()

        val selectedText = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()
        val beforeCursor = ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0) ?: ""
        val afterCursor = ic.getTextAfterCursor(1, 0)

        suppressNextCursorClear = true
        ime.ignoreNextCursorMove()

        var prevWordForLearn: String? = null
        var atSentenceStartForLearn = true

        ic.beginBatchEdit()
        if (suggestion.replacesCurrentWord) {
            val cw = extractWordAroundCursor(ic, selectedText)
            val ctxBefore = cw?.beforeWordText ?: beforeCursor
            val prevCtx = extractPreviousContext(ctxBefore, ctxBefore.length)
            prevWordForLearn = prevCtx.previousWord
            atSentenceStartForLearn = prevCtx.atSentenceStart
            if (cw != null && cw.isSelection) {
                ic.commitText(cw.leadingWs + suggestion.text + cw.trailingWs, 1)
            } else if (cw != null) {
                val trailingSpace = if (cw.afterWordChar == null) " " else ""
                ic.deleteSurroundingText(cw.leftLen, cw.rightLen)
                ic.commitText(suggestion.text + trailingSpace, 1)
            } else {
                val trailingSpace = if (afterCursor.isNullOrEmpty() || afterCursor[0] != ' ') " " else ""
                ic.commitText(suggestion.text + trailingSpace, 1)
            }
        } else {
            val trailingSpace = if (afterCursor.isNullOrEmpty() || afterCursor[0] != ' ') " " else ""
            val prevCtx = extractPreviousContext(beforeCursor, beforeCursor.length)
            prevWordForLearn = prevCtx.previousWord
            atSentenceStartForLearn = prevCtx.atSentenceStart
            val lastChar = beforeCursor.lastOrNull()
            val needsLeadingSpace =
                lastChar != null && !lastChar.isWhitespace() && lastChar !in NO_SPACE_BEFORE_WORD_CHARS
            val leadingSpace = if (needsLeadingSpace) " " else ""
            ic.commitText(leadingSpace + suggestion.text + trailingSpace, 1)
        }
        ic.endBatchEdit()

        pendingUndo = null
        pendingAutoSpace = false
        wordHadManualShift = false
        shiftToggledOnManually = false
        recentCommits.clear()
        onAutoCapitalizeDone()

        if (allowLearning) {
            recordAcceptedBoundaryWord(
                word = suggestion.text.substringAfterLast(' '),
                prevWord = prevWordForLearn,
                atSentenceStart = atSentenceStartForLearn,
                languages = languages,
                wasAutocorrected = false,
            )
        }
        refreshSuggestions(ime)
    }

    fun applyUndoChip(ime: IMEService): Boolean {
        val undo = pendingUndo ?: return false
        if (undo.fieldGeneration != fieldGeneration) {
            pendingUndo = null
            refreshSuggestions(ime)
            return false
        }
        val ic = ime.appInputConnection
        val expectedSuffix = undo.replacedWord + undo.delimiter
        val before = ic.getTextBeforeCursor(expectedSuffix.length + 4, 0) ?: ""
        if (!before.endsWith(expectedSuffix)) {
            pendingUndo = null
            refreshSuggestions(ime)
            return false
        }
        restoreUndoLocked(ic, ime, undo, ime.currentAppSettings)
        refreshSuggestions(ime)
        return true
    }

    fun applyEmojiSuggestion(
        ime: IMEService,
        emoji: String,
    ) {
        val ic = ime.appInputConnection
        val before = ic.getTextBeforeCursor(8, 0) ?: ""
        val after = ic.getTextAfterCursor(1, 0)
        val last = before.lastOrNull()
        val leading = if (last != null && !last.isWhitespace()) " " else ""
        val trailing = if (after.isNullOrEmpty() || after[0] != ' ') " " else ""

        suppressNextCursorClear = true
        ime.ignoreNextCursorMove()
        ic.commitText(leading + emoji + trailing, 1)

        pendingUndo = null
        pendingAutoSpace = false
        refreshSuggestions(ime)
    }

    private fun restoreUndoLocked(
        ic: android.view.inputmethod.InputConnection,
        ime: IMEService,
        undo: PendingUndo,
        settings: AppSettings?,
    ) {
        val expectedSuffix = undo.replacedWord + undo.delimiter
        suppressNextCursorClear = true
        ime.ignoreNextCursorMove()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expectedSuffix.length, 0)
        ic.commitText(undo.originalWord + undo.delimiter, 1)
        ic.endBatchEdit()

        dictionaryManager.correctionStats.recordRevert(
            typed = undo.originalWord,
            fixed = undo.replacedWord,
            deliberate = true,
        )
        val editorInfo = ime.currentInputEditorInfo
        val learnEnabled =
            !isNoPersonalizedLearning(editorInfo) &&
                (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()
        if (learnEnabled) {
            dictionaryManager.userLexicon.promoteRevertedWord(undo.originalWord)
        }
        pendingUndo = null
    }

    private fun shouldTriggerAutocorrectForCommit(
        text: String,
        allowAutocorrect: Boolean,
        languages: List<String>,
        settings: AppSettings?,
    ): Boolean {
        if (!allowAutocorrect || languages.isEmpty()) return false
        if (!(settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool()) return false
        if (text == " " || text == "\t") return true
        if (text == "\n" || text == "\r\n") {
            return (settings?.autocorrectBeforeEnter ?: DEFAULT_AUTOCORRECT_BEFORE_ENTER).toBool()
        }
        if (text.length == 1 && SmartPunctuation.isTargetPunctuation(text[0])) {
            return (settings?.autocorrectBeforePunctuation ?: DEFAULT_AUTOCORRECT_BEFORE_PUNCTUATION).toBool()
        }
        return false
    }

    private fun evaluateAndApplyAtBoundary(
        ic: android.view.inputmethod.InputConnection,
        ime: IMEService,
        beforeCursor: CharSequence,
        delimiter: String,
        deleteExtraSpaceBeforeWord: Boolean,
        layout: KeyboardLayout,
        keyboardDef: KeyboardDefinition,
        languages: List<String>,
        settings: AppSettings?,
        allowLearning: Boolean,
    ): String? {
        dictionaryManager.ensureInitializedAsync()
        if (!dictionaryManager.isReady) return null

        val extracted = extractWordAtEnd(beforeCursor) ?: return null
        val (word, wordStartIdx) = extracted
        val previousContext = extractPreviousContext(beforeCursor, wordStartIdx)
        val sentenceWords = extractSentenceWords(beforeCursor, wordStartIdx)

        val undoEnabled =
            (settings?.autocorrectUndoOnBackspace ?: DEFAULT_AUTOCORRECT_UNDO_ON_BACKSPACE).toBool()
        dictionaryManager.correctionStats.memory =
            if (undoEnabled) {
                CorrectionStats.UndoMemory.NORMAL
            } else {
                CorrectionStats.UndoMemory.OFF
            }

        val contextModelEnabled =
            (settings?.autocorrectContextModelEnabled ?: DEFAULT_AUTOCORRECT_CONTEXT_MODEL_ENABLED).toBool()
        val activeLang = languages.first()
        val matchingSnapshot =
            if (contextModelEnabled) {
                currentLmSnapshot?.takeIf { snap ->
                    snap.languageId == activeLang && snap.matchesContext(sentenceWords)
                }
            } else {
                null
            }

        val config = buildConfig(settings, ime.currentInputEditorInfo, contextModelEnabled)
        val proximity = proximityFor(keyboardDef)
        val result =
            AutocorrectEngine.evaluate(
                typed = word,
                previousWord = previousContext.previousWord,
                atSentenceStart = previousContext.atSentenceStart,
                manuallyShifted = wordHadManualShift,
                languages = languages,
                proximity = proximity,
                config = config,
                stats = dictionaryManager.correctionStats,
                memory = dictionaryManager.correctionMemory,
                lexicon = dictionaryManager.userLexicon,
                dictionaryManager = dictionaryManager,
                workspace = beamWorkspace,
                lmSnapshot = matchingSnapshot,
            ) ?: return null

        val extraDelete = if (deleteExtraSpaceBeforeWord) 1 else 0
        val committedText =
            markAutoCorrection(
                context = ime,
                text = result.replacement + delimiter,
                original = word,
                wordEnd = result.replacement.length,
                languageId = activeLang,
            )
        suppressNextCursorClear = true
        ime.ignoreNextCursorMove()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length + extraDelete, 0)
        ic.commitText(committedText, 1)
        ic.endBatchEdit()

        wordHadManualShift = false
        shiftToggledOnManually = false
        pendingUndo =
            if (delimiter.isNotEmpty()) {
                PendingUndo(
                    originalWord = word,
                    replacedWord = result.replacement,
                    delimiter = delimiter,
                    fieldGeneration = fieldGeneration,
                )
            } else {
                null
            }

        if (allowLearning) {
            recordAcceptedBoundaryWord(
                word = result.replacement.substringAfterLast(' '),
                prevWord = previousContext.previousWord,
                atSentenceStart = previousContext.atSentenceStart,
                languages = languages,
                wasAutocorrected = true,
            )
        }
        return result.replacement
    }

    private fun requestLmUpdate(
        ime: IMEService,
        sentenceWords: List<String>,
        activeLang: String,
        autocorrectEnabled: Boolean,
        finishedRevision: Pair<List<String>, List<CommittedEntry>>?,
    ) {
        val model = dictionaryManager.contextModelFor(activeLang)
        if (model == null) {
            dictionaryManager.ensureContextModelLoadedAsync(true)
            currentLmSnapshot = null
            return
        }
        if (!model.supportsLanguage(activeLang)) {
            currentLmSnapshot = null
            return
        }
        val activeCommits =
            finishedRevision?.second
                ?: if (recentCommits.size >= 2 && commitSeq != lastRevisedCommitSeq) {
                    recentCommits.toList()
                } else {
                    emptyList()
                }
        val revisionContextWords = finishedRevision?.first ?: sentenceWords
        val targetSeq = if (finishedRevision != null) -1L else commitSeq
        val shouldRunRevision =
            autocorrectEnabled &&
                activeCommits.size >= 2 &&
                model.supportsLanguage(activeLang)

        val existing = currentLmSnapshot
        val matchesExisting =
            existing != null &&
                existing.model === model &&
                existing.languageId == activeLang &&
                existing.matchesContext(sentenceWords)
        if (matchesExisting && !shouldRunRevision) {
            return
        }

        val fieldHint = detectContextField(ime.currentInputEditorInfo)
        val capturedGen = fieldGeneration
        val imeRef = WeakReference(ime)
        lmJob?.cancel()
        lmJob =
            lmScope.launch {
                val session =
                    if (cachedLmModel === model && cachedLmSession != null) {
                        cachedLmSession!!
                    } else {
                        WmlmSession(model).also {
                            cachedLmModel = model
                            cachedLmSession = it
                        }
                    }
                val pendingRevision =
                    if (shouldRunRevision) {
                        evaluateRetroactiveRevision(
                            session = session,
                            model = model,
                            sentenceWords = revisionContextWords,
                            commits = activeCommits,
                            fieldHint = fieldHint,
                            activeLang = activeLang,
                            generation = capturedGen,
                        )
                    } else {
                        null
                    }
                session.setContext(
                    words = sentenceWords,
                    register = 0,
                    field = fieldHint,
                    langSlot = model.slotOf(activeLang),
                )
                currentLmSnapshot = session.buildSnapshot(sentenceWords, activeLang)
                mainHandler.post {
                    if (targetSeq >= 0L && commitSeq == targetSeq) {
                        lastRevisedCommitSeq = targetSeq
                    }
                    val host = imeRef.get() ?: return@post
                    if (pendingRevision != null && applyRetroactiveRewrite(host, pendingRevision)) {
                        return@post
                    }
                    val s = host.currentAppSettings
                    if ((s?.suggestionsEnabled ?: DEFAULT_SUGGESTIONS_ENABLED).toBool()) {
                        refreshSuggestions(host)
                    }
                }
            }
    }

    private fun evaluateRetroactiveRevision(
        session: WmlmSession,
        model: WmlmModel,
        sentenceWords: List<String>,
        commits: List<CommittedEntry>,
        fieldHint: Int,
        activeLang: String,
        generation: Long,
    ): PendingRevision? {
        if (commits.size < 2 || sentenceWords.size < 2) return null
        val stats = dictionaryManager.correctionStats
        val langSlot = model.slotOf(activeLang)
        val maxBack = minOf(REVISION_MAX_BACK, commits.size - 1)
        for (back in 1..maxBack) {
            val entryIdx = commits.size - 1 - back
            val entry = commits[entryIdx]
            if (entry.wasCorrected || entry.alternatives.isEmpty() || entry.word.length < 2) continue
            val followers = commits.subList(entryIdx + 1, commits.size).map { it.word }
            val tailNeeded = 1 + followers.size
            if (sentenceWords.size < tailNeeded) continue
            val entrySentenceIdx = sentenceWords.size - tailNeeded
            if (!sentenceWords[entrySentenceIdx].equals(entry.word, ignoreCase = true) ||
                !sentenceWords.last().equals(followers.last(), ignoreCase = true)
            ) {
                continue
            }
            val ctxBeforeEntry = sentenceWords.subList(0, entrySentenceIdx)
            val filteredAlts =
                entry.alternatives.filter { alt ->
                    stats.penalty(entry.word, alt) != CorrectionStats.Penalty.BLOCKED
                }
            if (filteredAlts.isEmpty()) continue
            val candidates = ArrayList<String>(1 + filteredAlts.size)
            candidates.add(entry.word)
            candidates.addAll(filteredAlts)

            val scores =
                session.revise(
                    context = ctxBeforeEntry,
                    candidates = candidates,
                    followers = followers,
                    register = 0,
                    field = fieldHint,
                    langSlot = langSlot,
                )
            val typedScore = scores.getOrNull(0) ?: Float.NaN
            var bestAltIdx = -1
            var bestAltScore = Float.NEGATIVE_INFINITY
            var secondAltScore = Float.NEGATIVE_INFINITY
            for (i in 1 until scores.size) {
                val s = scores[i]
                if (!s.isFinite()) continue
                if (s > bestAltScore) {
                    secondAltScore = bestAltScore
                    bestAltScore = s
                    bestAltIdx = i
                } else if (s > secondAltScore) {
                    secondAltScore = s
                }
            }
            if (bestAltIdx < 1) continue
            val bestAlt = candidates[bestAltIdx]
            val accepted =
                if (!entry.wasKnown) {
                    val beatsTyped = typedScore.isNaN() || (bestAltScore - typedScore) >= REVISION_OOV_GAIN
                    val beatsRunner = !secondAltScore.isFinite() || (bestAltScore - secondAltScore) >= REVISION_RUNNER_MARGIN
                    beatsTyped && beatsRunner
                } else {
                    EditOps.distance(entry.word.lowercase(), bestAlt.lowercase()) == 1 &&
                        stats.penalty(entry.word, bestAlt) == CorrectionStats.Penalty.NONE &&
                        typedScore.isFinite() &&
                        (bestAltScore - typedScore) >= REVISION_KNOWN_WORD_GAIN &&
                        (!secondAltScore.isFinite() || (bestAltScore - secondAltScore) >= REVISION_KNOWN_RUNNER_MARGIN)
                }
            if (accepted) {
                return PendingRevision(
                    wrongWord = entry.word,
                    replacement = bestAlt,
                    followers = followers,
                    languageId = activeLang,
                    generation = generation,
                )
            }
        }
        return null
    }

    private fun applyRetroactiveRewrite(
        ime: IMEService,
        revision: PendingRevision,
    ): Boolean {
        if (fieldGeneration != revision.generation) return false
        val ic = ime.appInputConnection
        val selected = runCatching { ic.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return false
        val before = runCatching { ic.getTextBeforeCursor(REWRITE_LOOKBACK, 0)?.toString() }.getOrNull() ?: return false
        val tailStart = findRevisionTailStart(before, revision.wrongWord, revision.followers)
        if (tailStart < 0) return false
        val tailAfterWord = before.substring(tailStart + revision.wrongWord.length)
        val replacedText =
            markAutoCorrection(
                context = ime,
                text = revision.replacement + tailAfterWord,
                original = revision.wrongWord,
                wordEnd = revision.replacement.length,
                languageId = revision.languageId,
            )
        suppressNextCursorClear = true
        ime.ignoreNextCursorMove()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length - tailStart, 0)
        ic.commitText(replacedText, 1)
        ic.endBatchEdit()

        for (i in recentCommits.indices) {
            if (recentCommits[i].word == revision.wrongWord && !recentCommits[i].wasCorrected) {
                recentCommits[i] =
                    recentCommits[i].copy(
                        word = revision.replacement,
                        wasKnown = true,
                        wasCorrected = true,
                        alternatives = emptyList(),
                    )
                break
            }
        }
        commitSeq++
        pendingUndo =
            PendingUndo(
                originalWord = revision.wrongWord,
                replacedWord = revision.replacement,
                delimiter = tailAfterWord,
                fieldGeneration = fieldGeneration,
                allowBackspaceUndo = false,
            )
        refreshSuggestions(ime)
        return true
    }

    private fun appendRecentCommit(entry: CommittedEntry) {
        if (recentCommits.size >= RECENT_COMMIT_CAPACITY) {
            recentCommits.removeFirst()
        }
        recentCommits.addLast(entry)
        commitSeq++
    }

    private fun recentCommitsMatchesBuffer(before: String): Boolean {
        if (recentCommits.size < 2) return true
        val first = recentCommits.first().word
        val rest = recentCommits.drop(1).map { it.word }
        return findRevisionTailStart(before, first, rest) >= 0
    }

    private fun isWordKnown(
        word: String,
        languages: List<String>,
    ): Boolean {
        val lower = word.lowercase()
        val primaryLang = languages.firstOrNull() ?: return false
        val primaryDict = dictionaryManager.sourceForLanguage(primaryLang)
        val bestFreq =
            languages.maxOfOrNull { lang ->
                dictionaryManager.sourceForLanguage(lang)?.frequencyOf(lower) ?: 0
            } ?: 0
        val userFreq = dictionaryManager.userLexicon.frequencyOf(lower)
        val isRegularInflection =
            primaryLang == "en" &&
                primaryDict != null &&
                Inflections.isKnownInflection(lower, primaryDict)
        return bestFreq > 0 || userFreq > 0 || isRegularInflection
    }

    private fun buildConfig(
        settings: AppSettings?,
        editorInfo: EditorInfo?,
        contextModelEnabled: Boolean,
    ): AutocorrectEngine.Config {
        val allowLearning =
            !isNoPersonalizedLearning(editorInfo) &&
                (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()
        return AutocorrectEngine.Config(
            enabled = (settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool(),
            confidencePercent = settings?.autocorrectConfidence ?: DEFAULT_AUTOCORRECT_CONFIDENCE,
            fixApostrophes =
                (settings?.autocorrectFixApostrophes ?: DEFAULT_AUTOCORRECT_FIX_APOSTROPHES).toBool(),
            capitalizeNames =
                (settings?.autocorrectDictionaryCapitals ?: DEFAULT_AUTOCORRECT_DICTIONARY_CAPITALS).toBool(),
            restoreAccents = true,
            fixMissingSpaces = true,
            learnWords = allowLearning,
            undoMemory = dictionaryManager.correctionStats.memory,
            ignoreAllCaps =
                (settings?.autocorrectSkipAllCaps ?: DEFAULT_AUTOCORRECT_SKIP_ALL_CAPS).toBool(),
            useLearnedGate = false,
        )
    }

    private fun resolveActiveLanguages(
        layout: KeyboardLayout,
        settings: AppSettings?,
    ): List<String> =
        LayoutLanguageResolver
            .resolveActiveLanguage(
                layout = layout,
                additionalLanguagesCsv =
                    settings?.autocorrectAdditionalLanguages ?: DEFAULT_AUTOCORRECT_ADDITIONAL_LANGUAGES,
                activeLanguage =
                    settings?.autocorrectActiveLanguage ?: DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE,
            )?.let(::listOf)
            ?: emptyList()

    private fun observeBoundaryWordIfAny(
        beforeCursor: CharSequence,
        languages: List<String>,
    ) {
        val extracted = extractWordAtEnd(beforeCursor) ?: return
        val (word, wordStartIdx) = extracted
        val prevCtx = extractPreviousContext(beforeCursor, wordStartIdx)
        val repairSrc = awaitingManualRepairFor
        if (repairSrc != null) {
            dictionaryManager.correctionMemory.recordManualRepair(repairSrc, word)
            awaitingManualRepairFor = null
        }
        recordAcceptedBoundaryWord(
            word = word,
            prevWord = prevCtx.previousWord,
            atSentenceStart = prevCtx.atSentenceStart,
            languages = languages,
            wasAutocorrected = false,
        )
    }

    private fun recordAcceptedBoundaryWord(
        word: String,
        prevWord: String?,
        atSentenceStart: Boolean,
        languages: List<String>,
        wasAutocorrected: Boolean,
    ) {
        val lower = word.lowercase()
        val known =
            languages.any { lang ->
                (dictionaryManager.sourceForLanguage(lang)?.frequencyOf(lower) ?: 0) > 0
            }
        dictionaryManager.userLexicon.observeWord(
            surface = word,
            knownInDictionary = known || wasAutocorrected,
            caseEvidence = !atSentenceStart && WordCase.startsUpperCase(word),
        )
        if (prevWord != null && !atSentenceStart) {
            dictionaryManager.userLexicon.recordBigram(prevWord.lowercase(), lower)
        }
        lastCommittedWord = if (wasAutocorrected) null else word
        lastCommittedAtSentenceStart = atSentenceStart
        backspacesSinceCommit = 0
        wordHadManualShift = false
        shiftToggledOnManually = false
    }

    private fun proximityFor(definition: KeyboardDefinition): KeyboardProximity {
        val key = definition.title
        if (cachedLayoutTitle != key) {
            cachedLayoutTitle = key
            cachedProximity = ThumbKeyProximity.fromDefinition(definition)
        }
        return cachedProximity
    }

    private fun detectContextField(editorInfo: EditorInfo?): Int {
        if (editorInfo == null) return FIELD_OTHER
        val action = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_SEARCH) return FIELD_SEARCH
        val variation = editorInfo.inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (variation) {
            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
            EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            -> FIELD_EMAIL

            EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> FIELD_CHAT

            else -> if (action == EditorInfo.IME_ACTION_SEND) FIELD_CHAT else FIELD_OTHER
        }
    }

    private data class WordAtEnd(
        val word: String,
        val startIndex: Int,
    )

    private data class CursorWord(
        val word: String,
        val leftLen: Int,
        val rightLen: Int,
        val isSelection: Boolean,
        val leadingWs: String,
        val trailingWs: String,
        val beforeWordText: String,
        val afterWordChar: Char?,
    )

    private data class PreviousContext(
        val previousWord: String?,
        val atSentenceStart: Boolean,
    )

    private fun extractWordAroundCursor(
        ic: android.view.inputmethod.InputConnection,
        selectedText: String? = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull(),
    ): CursorWord? {
        if (!selectedText.isNullOrEmpty()) {
            var startWs = 0
            while (startWs < selectedText.length && selectedText[startWs] == ' ') startWs++
            var endWs = selectedText.length
            while (endWs > startWs && selectedText[endWs - 1] == ' ') endWs--
            val leadingWs = selectedText.substring(0, startWs)
            val trailingWs = selectedText.substring(endWs)
            val trimmed = selectedText.substring(startWs, endWs)
            if (trimmed.isEmpty() || trimmed.length > MAX_EDIT_WORD_LENGTH) return null
            if (!trimmed.all { isTokenChar(it) } || !trimmed.any { it.isLetter() }) return null
            val beforeSel = runCatching { ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString() }.getOrNull() ?: ""
            val afterSel = runCatching { ic.getTextAfterCursor(8, 0)?.toString() }.getOrNull() ?: ""
            if (leadingWs.isEmpty() && beforeSel.isNotEmpty()) {
                val boundary = beforeSel.last()
                if (boundary in BLOCKING_PREFIX_CHARS || isTokenChar(boundary)) return null
            }
            if (trailingWs.isEmpty() && afterSel.isNotEmpty() && isTokenChar(afterSel.first())) {
                return null
            }
            return CursorWord(
                word = trimmed,
                leftLen = 0,
                rightLen = 0,
                isSelection = true,
                leadingWs = leadingWs,
                trailingWs = trailingWs,
                beforeWordText = beforeSel,
                afterWordChar = trailingWs.firstOrNull() ?: afterSel.firstOrNull(),
            )
        }

        val before = runCatching { ic.getTextBeforeCursor(CONTEXT_LOOKBACK, 0)?.toString() }.getOrNull() ?: ""
        val after = runCatching { ic.getTextAfterCursor(WORD_LOOKAHEAD, 0)?.toString() }.getOrNull() ?: ""
        var i = before.length - 1
        while (i >= 0 && isTokenChar(before[i])) {
            i--
        }
        val leftStart = i + 1
        var rightEnd = 0
        while (rightEnd < after.length && isTokenChar(after[rightEnd])) {
            rightEnd++
        }
        val leftPart = before.substring(leftStart)
        val rightPart = after.substring(0, rightEnd)
        val fullWord = leftPart + rightPart
        if (fullWord.isEmpty() || fullWord.length > MAX_EDIT_WORD_LENGTH || !fullWord.any { it.isLetter() }) {
            return null
        }
        if (leftStart > 0 && before[leftStart - 1] in BLOCKING_PREFIX_CHARS) {
            return null
        }
        return CursorWord(
            word = fullWord,
            leftLen = leftPart.length,
            rightLen = rightPart.length,
            isSelection = false,
            leadingWs = "",
            trailingWs = "",
            beforeWordText = before.substring(0, leftStart),
            afterWordChar = after.getOrNull(rightEnd),
        )
    }

    private fun extractWordAtEnd(beforeCursor: CharSequence): WordAtEnd? {
        if (beforeCursor.isEmpty()) return null
        var i = beforeCursor.length - 1
        while (i >= 0 && isTokenChar(beforeCursor[i])) {
            i--
        }
        val start = i + 1
        if (start >= beforeCursor.length) return null
        if (start > 0) {
            val boundary = beforeCursor[start - 1]
            if (boundary in BLOCKING_PREFIX_CHARS) return null
        }
        val token = beforeCursor.subSequence(start, beforeCursor.length).toString()
        if (!token.any { it.isLetter() }) return null
        return WordAtEnd(token, start)
    }

    private fun extractSentenceWords(
        beforeCursor: CharSequence,
        endIdx: Int,
    ): List<String> {
        if (endIdx <= 0) return emptyList()
        var sentenceStart = endIdx - 1
        while (sentenceStart >= 0) {
            val ch = beforeCursor[sentenceStart]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                sentenceStart++
                break
            }
            sentenceStart--
        }
        if (sentenceStart < 0) sentenceStart = 0
        val out = ArrayList<String>(12)
        var i = sentenceStart
        while (i < endIdx) {
            while (i < endIdx && !isTokenChar(beforeCursor[i])) i++
            val wStart = i
            while (i < endIdx && isTokenChar(beforeCursor[i])) i++
            if (i > wStart) {
                out.add(beforeCursor.subSequence(wStart, i).toString())
            }
        }
        return out.takeLast(MAX_SENTENCE_CONTEXT_WORDS)
    }

    private fun extractPreviousContext(
        beforeCursor: CharSequence,
        wordStartIdx: Int,
    ): PreviousContext {
        if (wordStartIdx <= 0) return PreviousContext(null, true)
        var i = wordStartIdx - 1
        var sawSentenceEnd = false
        while (i >= 0 && !beforeCursor[i].isLetterOrDigit()) {
            val ch = beforeCursor[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                sawSentenceEnd = true
            }
            i--
        }
        if (i < 0) return PreviousContext(null, true)
        if (sawSentenceEnd) return PreviousContext(null, true)
        val end = i + 1
        while (i >= 0 && isTokenChar(beforeCursor[i])) {
            i--
        }
        val prev = beforeCursor.subSequence(i + 1, end).toString()
        return PreviousContext(prev.ifEmpty { null }, false)
    }

    private fun CharSequence.endsWithWhitespaceOrPunct(): Boolean {
        if (isEmpty()) return false
        val ch = last()
        return ch.isWhitespace() || ch == ',' || ch == ';' || ch == ':'
    }

    private fun isTokenChar(c: Char): Boolean = isTokenCharInternal(c)

    private fun markAutoCorrection(
        context: Context,
        text: String,
        original: String,
        wordEnd: Int,
        languageId: String?,
    ): CharSequence {
        val end = wordEnd.coerceIn(0, text.length)
        if (end == 0 || !shouldMarkAutoCorrection(text.substring(0, end), original)) {
            return text
        }
        return try {
            val flags = SuggestionSpan.FLAG_AUTO_CORRECTION or SuggestionSpan.FLAG_EASY_CORRECT
            val locale = languageId?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }
            val span =
                if (locale != null) {
                    SuggestionSpan(context, locale, arrayOf(original), flags, null)
                } else {
                    SuggestionSpan(context, arrayOf(original), flags)
                }
            SpannableString(text).apply {
                setSpan(span, 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (_: Throwable) {
            text
        }
    }

    companion object {
        private const val CONTEXT_LOOKBACK = 96
        private const val WORD_LOOKAHEAD = 40
        private const val REWRITE_LOOKBACK = 128
        private const val MAX_EDIT_WORD_LENGTH = 36
        private const val MAX_SENTENCE_CONTEXT_WORDS = 16
        private const val SUGGESTION_LIMIT = 6
        private const val EMOJI_LIMIT = 3
        private const val RECENT_COMMIT_CAPACITY = 6
        private const val REVISION_CANDIDATE_LIMIT = 4
        private const val REVISION_MAX_BACK = 4
        private const val REVISION_OOV_GAIN = 2.0f
        private const val REVISION_RUNNER_MARGIN = 1.0f
        private const val REVISION_KNOWN_WORD_GAIN = 4.5f
        private const val REVISION_KNOWN_RUNNER_MARGIN = 1.5f
        private const val FIELD_OTHER = 0
        private const val FIELD_CHAT = 1
        private const val FIELD_EMAIL = 2
        private const val FIELD_SEARCH = 3
        private val BLOCKING_PREFIX_CHARS = charArrayOf('@', '/', '\\', '#', '_', ':')
        private val NO_SPACE_BEFORE_WORD_CHARS = charArrayOf('(', '[', '{', '"', '«', '¿', '¡', '\n')
        private val SENTENCE_END_PUNCT_CHARS = charArrayOf('.', '!', '?')
        private val SENTENCE_BOUNDARY_CHARS = charArrayOf('.', '!', '?', '\n', '\r')

        private fun isTokenCharInternal(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'

        internal fun findRevisionTailStart(
            before: String,
            wrongWord: String,
            followers: List<String>,
        ): Int {
            if (wrongWord.isEmpty() || followers.isEmpty() || before.isEmpty()) return -1

            fun matchFrom(
                startEnd: Int,
                requireSepBeforeEnd: Boolean,
            ): Int {
                var idx = startEnd
                if (requireSepBeforeEnd) {
                    val beforeSep = idx
                    while (idx > 0 && !isTokenCharInternal(before[idx - 1])) {
                        if (before[idx - 1] in SENTENCE_BOUNDARY_CHARS) return -1
                        idx--
                    }
                    if (idx == beforeSep) return -1
                } else {
                    while (idx > 0 && !isTokenCharInternal(before[idx - 1])) {
                        idx--
                    }
                }

                for (fIdx in followers.size - 1 downTo 0) {
                    val f = followers[fIdx]
                    if (idx < f.length) return -1
                    val fStart = idx - f.length
                    if (!before.regionMatches(fStart, f, 0, f.length, ignoreCase = false)) return -1
                    idx = fStart
                    val sepEnd = idx
                    while (idx > 0 && !isTokenCharInternal(before[idx - 1])) {
                        if (before[idx - 1] in SENTENCE_BOUNDARY_CHARS) return -1
                        idx--
                    }
                    if (idx == sepEnd) return -1
                }

                if (idx < wrongWord.length) return -1
                val wStart = idx - wrongWord.length
                if (!before.regionMatches(wStart, wrongWord, 0, wrongWord.length, ignoreCase = false)) return -1
                if (wStart > 0 && isTokenCharInternal(before[wStart - 1])) return -1
                return wStart
            }

            if (!isTokenCharInternal(before.last())) {
                val exact = matchFrom(before.length, requireSepBeforeEnd = false)
                if (exact >= 0) return exact
            }
            var scan = before.length
            while (scan > 0 && isTokenCharInternal(before[scan - 1])) {
                scan--
            }
            if (scan < before.length) {
                return matchFrom(scan, requireSepBeforeEnd = true)
            }
            return -1
        }

        internal fun shouldMarkAutoCorrection(
            replacement: String,
            original: String,
        ): Boolean =
            replacement.isNotEmpty() &&
                original.isNotEmpty() &&
                !original.equals(replacement, ignoreCase = true)
    }
}
