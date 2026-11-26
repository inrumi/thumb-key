package com.dessalines.thumbkey.ui.components.settings.autocorrect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dessalines.thumbkey.R
import com.dessalines.thumbkey.ThumbkeyApplication
import com.dessalines.thumbkey.autocorrect.DictionaryManager
import com.dessalines.thumbkey.autocorrect.LayoutLanguageResolver
import com.dessalines.thumbkey.db.AppSettingsViewModel
import com.dessalines.thumbkey.db.AutocorrectSettingsUpdate
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
import com.dessalines.thumbkey.db.DEFAULT_KEYBOARD_LAYOUT
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTIONS_EMOJI_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTIONS_ENABLED
import com.dessalines.thumbkey.db.DEFAULT_SUGGESTION_BAR_FLOATING_PLACEMENT
import com.dessalines.thumbkey.db.MAX_AUTOCORRECT_CONFIDENCE
import com.dessalines.thumbkey.db.MIN_AUTOCORRECT_CONFIDENCE
import com.dessalines.thumbkey.ui.components.common.TestOutTextField
import com.dessalines.thumbkey.utils.KeyboardLayout
import com.dessalines.thumbkey.utils.SimpleTopAppBar
import com.dessalines.thumbkey.utils.SuggestionBarFloatingPlacement
import com.dessalines.thumbkey.utils.toBool
import com.dessalines.thumbkey.utils.toInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutocorrectScreen(
    navController: NavController,
    appSettingsViewModel: AppSettingsViewModel,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ThumbkeyApplication
    val dictionaryManager = app.dictionaryManager
    val settings by appSettingsViewModel.appSettings.observeAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var installedDicts by remember { mutableStateOf<List<DictionaryManager.DictionaryInfo>>(emptyList()) }
    var installedModels by remember { mutableStateOf<List<DictionaryManager.ContextModelInfo>>(emptyList()) }
    var learnedWordCount by remember { mutableIntStateOf(0) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showModelLanguageDialog by remember { mutableStateOf(false) }
    var showPlacementDialog by remember { mutableStateOf(false) }
    var showAdditionalLanguagesDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var pendingDeleteLang by remember { mutableStateOf<String?>(null) }
    var pendingDeleteModelLang by remember { mutableStateOf<String?>(null) }
    var pendingImportLang by remember { mutableStateOf("en") }
    var pendingModelImportLang by remember { mutableStateOf("en") }

    suspend fun refreshStats() {
        withContext(Dispatchers.IO) {
            dictionaryManager.ensureInitializedBlocking()
        }
        installedDicts = dictionaryManager.listInstalledDictionaries()
        installedModels = dictionaryManager.listInstalledContextModels()
        learnedWordCount = dictionaryManager.userLexicon.wordCount()
    }

    LaunchedEffect(Unit) {
        refreshStats()
    }

    var enabledState = (settings?.autocorrectEnabled ?: DEFAULT_AUTOCORRECT_ENABLED).toBool()
    var confidenceState =
        (settings?.autocorrectConfidence ?: DEFAULT_AUTOCORRECT_CONFIDENCE).toFloat()
    var confidenceSliderState by
        remember(settings?.autocorrectConfidence) {
            mutableFloatStateOf(
                (settings?.autocorrectConfidence ?: DEFAULT_AUTOCORRECT_CONFIDENCE).toFloat(),
            )
        }
    var undoOnBackspaceState =
        (settings?.autocorrectUndoOnBackspace ?: DEFAULT_AUTOCORRECT_UNDO_ON_BACKSPACE).toBool()
    var beforePunctuationState =
        (settings?.autocorrectBeforePunctuation ?: DEFAULT_AUTOCORRECT_BEFORE_PUNCTUATION).toBool()
    var beforeEnterState =
        (settings?.autocorrectBeforeEnter ?: DEFAULT_AUTOCORRECT_BEFORE_ENTER).toBool()
    var fixApostrophesState =
        (settings?.autocorrectFixApostrophes ?: DEFAULT_AUTOCORRECT_FIX_APOSTROPHES).toBool()
    var dictionaryCapitalsState =
        (settings?.autocorrectDictionaryCapitals ?: DEFAULT_AUTOCORRECT_DICTIONARY_CAPITALS).toBool()
    var skipAllCapsState =
        (settings?.autocorrectSkipAllCaps ?: DEFAULT_AUTOCORRECT_SKIP_ALL_CAPS).toBool()
    var learnWordsState =
        (settings?.autocorrectLearnWords ?: DEFAULT_AUTOCORRECT_LEARN_WORDS).toBool()
    var suggestionsEnabledState =
        (settings?.suggestionsEnabled ?: DEFAULT_SUGGESTIONS_ENABLED).toBool()
    var floatingPlacementState =
        settings?.suggestionBarFloatingPlacement ?: DEFAULT_SUGGESTION_BAR_FLOATING_PLACEMENT
    var suggestionsEmojiEnabledState =
        (settings?.suggestionsEmojiEnabled ?: DEFAULT_SUGGESTIONS_EMOJI_ENABLED).toBool()
    var contextModelEnabledState =
        (settings?.autocorrectContextModelEnabled ?: DEFAULT_AUTOCORRECT_CONTEXT_MODEL_ENABLED).toBool()
    var additionalLanguagesCsvState =
        settings?.autocorrectAdditionalLanguages ?: DEFAULT_AUTOCORRECT_ADDITIONAL_LANGUAGES
    var activeLanguageState =
        settings?.autocorrectActiveLanguage ?: DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE
    val currentLayout =
        KeyboardLayout.entries.getOrElse(
            settings?.keyboardLayout ?: DEFAULT_KEYBOARD_LAYOUT,
        ) { KeyboardLayout.ENThumbKey }

    fun updateAutocorrect() {
        appSettingsViewModel.updateAutocorrectSettings(
            AutocorrectSettingsUpdate(
                id = 1,
                autocorrectEnabled = enabledState.toInt(),
                autocorrectConfidence = confidenceState.roundToInt(),
                autocorrectUndoOnBackspace = undoOnBackspaceState.toInt(),
                autocorrectBeforePunctuation = beforePunctuationState.toInt(),
                autocorrectBeforeEnter = beforeEnterState.toInt(),
                autocorrectFixApostrophes = fixApostrophesState.toInt(),
                autocorrectDictionaryCapitals = dictionaryCapitalsState.toInt(),
                autocorrectSkipAllCaps = skipAllCapsState.toInt(),
                autocorrectLearnWords = learnWordsState.toInt(),
                suggestionsEnabled = suggestionsEnabledState.toInt(),
                suggestionBarFloatingPlacement = floatingPlacementState,
                suggestionsEmojiEnabled = suggestionsEmojiEnabledState.toInt(),
                autocorrectContextModelEnabled = contextModelEnabledState.toInt(),
                autocorrectAdditionalLanguages = additionalLanguagesCsvState,
                autocorrectActiveLanguage = activeLanguageState,
            ),
        )
    }

    val importLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val result =
                    runCatching {
                        val stream =
                            context.contentResolver.openInputStream(uri)
                                ?: error("Unable to open selected file")
                        dictionaryManager.importDictionary(pendingImportLang, stream)
                    }
                result
                    .onSuccess { imported ->
                        refreshStats()
                        val countFormatted = NumberFormat.getIntegerInstance().format(imported.wordCount)
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.autocorrect_import_success,
                                imported.languageId,
                                countFormatted,
                            ),
                        )
                    }.onFailure {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.autocorrect_import_failed),
                        )
                    }
            }
        }

    val importModelLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val result =
                    runCatching {
                        val stream =
                            context.contentResolver.openInputStream(uri)
                                ?: error("Unable to open selected file")
                        dictionaryManager.importContextModel(pendingModelImportLang, stream)
                    }
                result
                    .onSuccess { imported ->
                        refreshStats()
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.autocorrect_import_model_success,
                                imported.languageId,
                            ),
                        )
                    }.onFailure {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.autocorrect_import_model_failed),
                        )
                    }
            }
        }

    Scaffold(
        topBar = {
            SimpleTopAppBar(
                text = stringResource(R.string.autocorrect),
                navController = navController,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        ProvidePreferenceTheme {
            Column(
                modifier =
                    Modifier
                        .imePadding()
                        .padding(innerPadding),
            ) {
                Column(
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(scrollState)
                            .weight(1f),
                ) {
                    SwitchPreference(
                        value = enabledState,
                        onValueChange = {
                            enabledState = it
                            updateAutocorrect()
                        },
                        title = { Text(stringResource(R.string.autocorrect_enabled)) },
                        summary = { Text(stringResource(R.string.autocorrect_enabled_description)) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Spellcheck,
                                contentDescription = null,
                            )
                        },
                    )
                    SliderPreference(
                        value = confidenceState,
                        sliderValue = confidenceSliderState,
                        onValueChange = {
                            confidenceState = it
                            updateAutocorrect()
                        },
                        onSliderValueChange = { confidenceSliderState = it },
                        valueRange =
                            MIN_AUTOCORRECT_CONFIDENCE.toFloat()..MAX_AUTOCORRECT_CONFIDENCE.toFloat(),
                        enabled = enabledState,
                        title = {
                            val formatted =
                                String.format(Locale.US, "%.1f", confidenceSliderState / 10f)
                            Text(stringResource(R.string.autocorrect_confidence, formatted))
                        },
                        summary = {
                            Text(stringResource(R.string.autocorrect_confidence_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = undoOnBackspaceState,
                        onValueChange = {
                            undoOnBackspaceState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_undo_on_backspace)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_undo_on_backspace_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = beforePunctuationState,
                        onValueChange = {
                            beforePunctuationState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_before_punctuation)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_before_punctuation_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.AutoFixHigh,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = beforeEnterState,
                        onValueChange = {
                            beforeEnterState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_before_enter)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_before_enter_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.KeyboardReturn,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = fixApostrophesState,
                        onValueChange = {
                            fixApostrophesState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_fix_apostrophes)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_fix_apostrophes_description))
                        },
                    )
                    SwitchPreference(
                        value = dictionaryCapitalsState,
                        onValueChange = {
                            dictionaryCapitalsState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_dictionary_capitals)) },
                        summary = {
                            Text(
                                stringResource(
                                    R.string.autocorrect_dictionary_capitals_description,
                                ),
                            )
                        },
                    )
                    SwitchPreference(
                        value = skipAllCapsState,
                        onValueChange = {
                            skipAllCapsState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_skip_all_caps)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_skip_all_caps_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.TextFields,
                                contentDescription = null,
                            )
                        },
                    )
                    SwitchPreference(
                        value = learnWordsState,
                        onValueChange = {
                            learnWordsState = it
                            updateAutocorrect()
                        },
                        enabled = enabledState,
                        title = { Text(stringResource(R.string.autocorrect_learn_words)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_learn_words_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.School,
                                contentDescription = null,
                            )
                        },
                    )

                    PreferenceCategory(
                        title = { Text(stringResource(R.string.suggestion_bar)) },
                    )
                    SwitchPreference(
                        value = suggestionsEnabledState,
                        onValueChange = {
                            suggestionsEnabledState = it
                            updateAutocorrect()
                        },
                        title = { Text(stringResource(R.string.suggestions_enabled)) },
                        summary = { Text(stringResource(R.string.suggestions_enabled_description)) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                            )
                        },
                    )
                    if (suggestionsEnabledState) {
                        val currentPlacement =
                            SuggestionBarFloatingPlacement.entries.getOrElse(
                                floatingPlacementState,
                            ) { SuggestionBarFloatingPlacement.AboveKeyboard }
                        val placementLabel = stringResource(currentPlacement.resId)
                        Preference(
                            title = { Text(stringResource(R.string.suggestion_bar_floating_placement)) },
                            summary = {
                                Text(
                                    stringResource(
                                        R.string.suggestion_bar_floating_placement_summary,
                                        placementLabel,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.OpenWith,
                                    contentDescription = null,
                                )
                            },
                            onClick = { showPlacementDialog = true },
                        )
                        SwitchPreference(
                            value = suggestionsEmojiEnabledState,
                            onValueChange = {
                                suggestionsEmojiEnabledState = it
                                updateAutocorrect()
                            },
                            title = { Text(stringResource(R.string.suggestions_emoji_enabled)) },
                            summary = {
                                Text(stringResource(R.string.suggestions_emoji_enabled_description))
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.EmojiEmotions,
                                    contentDescription = null,
                                )
                            },
                        )
                    }

                    PreferenceCategory(
                        title = { Text(stringResource(R.string.autocorrect_context_model)) },
                    )
                    SwitchPreference(
                        value = contextModelEnabledState,
                        onValueChange = {
                            contextModelEnabledState = it
                            dictionaryManager.ensureContextModelLoadedAsync(it)
                            updateAutocorrect()
                        },
                        enabled = enabledState || suggestionsEnabledState,
                        title = { Text(stringResource(R.string.autocorrect_context_model_enabled)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_context_model_enabled_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                            )
                        },
                    )
                    for (model in installedModels) {
                        val sizeMb = String.format(Locale.US, "%.1f MB", model.sizeBytes / (1024f * 1024f))
                        val label = displayLanguageName(model.languageId)
                        Preference(
                            title = { Text(label) },
                            summary = {
                                Text(
                                    stringResource(
                                        if (model.isImported) {
                                            R.string.autocorrect_context_model_summary_imported
                                        } else {
                                            R.string.autocorrect_context_model_summary_bundled
                                        },
                                        sizeMb,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Psychology,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                if (model.isImported) {
                                    pendingDeleteModelLang = model.languageId
                                }
                            },
                        )
                    }
                    Preference(
                        title = { Text(stringResource(R.string.autocorrect_import_context_model)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_import_context_model_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.UploadFile,
                                contentDescription = null,
                            )
                        },
                        onClick = { showModelLanguageDialog = true },
                    )

                    PreferenceCategory(
                        title = { Text(stringResource(R.string.autocorrect_dictionaries)) },
                    )
                    val selectedExtraLangs =
                        LayoutLanguageResolver.parseAdditionalLanguages(additionalLanguagesCsvState)
                    val enabledCycle =
                        LayoutLanguageResolver.enabledLanguageCycle(
                            currentLayout,
                            additionalLanguagesCsvState,
                        )
                    Preference(
                        title = { Text(stringResource(R.string.autocorrect_additional_languages)) },
                        summary = {
                            val text =
                                if (selectedExtraLangs.isEmpty() && enabledCycle.size <= 1) {
                                    stringResource(R.string.autocorrect_additional_languages_none)
                                } else {
                                    val cycleNames = enabledCycle.joinToString(", ") { displayLanguageName(it) }
                                    stringResource(R.string.autocorrect_languages_active_hint, cycleNames)
                                }
                            Text(text)
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                            )
                        },
                        onClick = { showAdditionalLanguagesDialog = true },
                    )
                    for (dict in installedDicts) {
                        val wordCountStr = NumberFormat.getIntegerInstance().format(dict.wordCount)
                        val label = displayLanguageName(dict.languageId)
                        Preference(
                            title = { Text(label) },
                            summary = {
                                Text(
                                    stringResource(
                                        if (dict.isImported) {
                                            R.string.autocorrect_dictionary_summary_imported
                                        } else {
                                            R.string.autocorrect_dictionary_summary_bundled
                                        },
                                        wordCountStr,
                                    ),
                                )
                            },
                            icon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                if (dict.isImported) {
                                    pendingDeleteLang = dict.languageId
                                }
                            },
                        )
                    }
                    Preference(
                        title = { Text(stringResource(R.string.autocorrect_import_dictionary)) },
                        summary = {
                            Text(stringResource(R.string.autocorrect_import_dictionary_description))
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.UploadFile,
                                contentDescription = null,
                            )
                        },
                        onClick = { showLanguageDialog = true },
                    )

                    PreferenceCategory(
                        title = { Text(stringResource(R.string.autocorrect_personal_data)) },
                    )
                    Preference(
                        title = { Text(stringResource(R.string.autocorrect_reset_learned)) },
                        summary = {
                            val countStr = NumberFormat.getIntegerInstance().format(learnedWordCount)
                            Text(
                                stringResource(
                                    R.string.autocorrect_reset_learned_summary,
                                    countStr,
                                ),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.DeleteForever,
                                contentDescription = null,
                            )
                        },
                        onClick = { showResetDialog = true },
                    )
                }
                TestOutTextField()
            }
        }
    }

    if (showPlacementDialog) {
        AlertDialog(
            onDismissRequest = { showPlacementDialog = false },
            title = { Text(stringResource(R.string.suggestion_bar_floating_placement)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.suggestion_bar_floating_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    for (option in SuggestionBarFloatingPlacement.entries) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        floatingPlacementState = option.ordinal
                                        updateAutocorrect()
                                        showPlacementDialog = false
                                    },
                        ) {
                            RadioButton(
                                selected = floatingPlacementState == option.ordinal,
                                onClick = {
                                    floatingPlacementState = option.ordinal
                                    updateAutocorrect()
                                    showPlacementDialog = false
                                },
                            )
                            Text(stringResource(option.resId))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlacementDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showAdditionalLanguagesDialog) {
        val availableLangs = installedDicts.map { it.languageId }
        val initialSelected =
            remember(additionalLanguagesCsvState) {
                LayoutLanguageResolver.parseAdditionalLanguages(additionalLanguagesCsvState).toSet()
            }
        var draftSelection by remember(initialSelected) { mutableStateOf(initialSelected) }
        AlertDialog(
            onDismissRequest = { showAdditionalLanguagesDialog = false },
            title = { Text(stringResource(R.string.autocorrect_additional_languages)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.autocorrect_additional_languages_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    for (lang in availableLangs) {
                        val checked = lang in draftSelection
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        draftSelection =
                                            if (checked) draftSelection - lang else draftSelection + lang
                                    },
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    draftSelection =
                                        if (isChecked) draftSelection + lang else draftSelection - lang
                                },
                            )
                            Text(displayLanguageName(lang))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        additionalLanguagesCsvState =
                            availableLangs.filter { it in draftSelection }.joinToString(",")
                        val nextCycle =
                            LayoutLanguageResolver.enabledLanguageCycle(
                                currentLayout,
                                additionalLanguagesCsvState,
                            )
                        if (activeLanguageState.isNotEmpty() && activeLanguageState !in nextCycle) {
                            activeLanguageState = DEFAULT_AUTOCORRECT_ACTIVE_LANGUAGE
                        }
                        updateAutocorrect()
                        showAdditionalLanguagesDialog = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdditionalLanguagesDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showModelLanguageDialog) {
        LanguagePickerDialog(
            initialLang = pendingModelImportLang,
            onDismiss = { showModelLanguageDialog = false },
            onConfirm = { chosenLang ->
                pendingModelImportLang = chosenLang
                showModelLanguageDialog = false
                importModelLauncher.launch(
                    arrayOf(
                        "application/octet-stream",
                        "*/*",
                    ),
                )
            },
        )
    }

    pendingDeleteModelLang?.let { lang ->
        AlertDialog(
            onDismissRequest = { pendingDeleteModelLang = null },
            title = { Text(stringResource(R.string.autocorrect_remove_model_title)) },
            text = {
                Text(stringResource(R.string.autocorrect_remove_model_message, lang))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteModelLang = null
                        scope.launch {
                            dictionaryManager.deleteImportedContextModel(lang)
                            refreshStats()
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.autocorrect_remove_model_done, lang),
                            )
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteModelLang = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            initialLang = pendingImportLang,
            onDismiss = { showLanguageDialog = false },
            onConfirm = { chosenLang ->
                pendingImportLang = chosenLang
                showLanguageDialog = false
                importLauncher.launch(
                    arrayOf(
                        "application/octet-stream",
                        "application/gzip",
                        "application/x-gzip",
                        "text/plain",
                        "*/*",
                    ),
                )
            },
        )
    }

    pendingDeleteLang?.let { lang ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLang = null },
            title = { Text(stringResource(R.string.autocorrect_remove_dictionary_title)) },
            text = {
                Text(stringResource(R.string.autocorrect_remove_dictionary_message, lang))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteLang = null
                        scope.launch {
                            dictionaryManager.deleteImportedDictionary(lang)
                            refreshStats()
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.autocorrect_remove_dictionary_done, lang),
                            )
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteLang = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.autocorrect_reset_learned_title)) },
            text = { Text(stringResource(R.string.autocorrect_reset_learned_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        scope.launch {
                            dictionaryManager.resetLearningData()
                            refreshStats()
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.autocorrect_reset_learned_done),
                            )
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    initialLang: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val presetCodes =
        listOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "ru", "uk", "sv", "nb", "da", "fi", "cs", "hu", "tr")
    var selected by remember { mutableStateOf(initialLang) }
    var customCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autocorrect_select_language)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (code in presetCodes) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = customCode.isBlank() && selected == code,
                            onClick = {
                                customCode = ""
                                selected = code
                            },
                        )
                        Text(displayLanguageName(code))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customCode,
                    onValueChange = { customCode = it.trim().lowercase() },
                    label = { Text(stringResource(R.string.autocorrect_custom_language_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = customCode.ifBlank { selected }.ifBlank { "en" }
                    onConfirm(DictionaryManager.normalizeLang(chosen))
                },
            ) {
                Text(stringResource(R.string.autocorrect_import_choose_file))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun displayLanguageName(langId: String): String {
    val display = Locale.forLanguageTag(langId).getDisplayLanguage(Locale.getDefault())
    val clean = if (display.isNullOrBlank()) langId else display.replaceFirstChar { it.uppercase() }
    return "$clean ($langId)"
}
