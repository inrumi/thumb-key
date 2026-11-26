package com.dessalines.thumbkey.autocorrect

import com.dessalines.thumbkey.ui.components.keyboard.shouldShowSuggestionBar
import com.dessalines.thumbkey.utils.KeyAction
import com.dessalines.thumbkey.utils.KeyC
import com.dessalines.thumbkey.utils.KeyDisplay
import com.dessalines.thumbkey.utils.KeyItemC
import com.dessalines.thumbkey.utils.KeyboardC
import com.dessalines.thumbkey.utils.KeyboardDefinition
import com.dessalines.thumbkey.utils.KeyboardDefinitionModes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutocorrectEngineTest {
    private lateinit var tmpRoot: File
    private lateinit var dictManager: DictionaryManager
    private lateinit var mappedEn: MappedTrie
    private lateinit var mappedEs: MappedTrie
    private lateinit var enModel: WmlmModel
    private val proximity = ThumbKeyProximity.fromDefinition(buildTestThumbKeyDefinition())
    private val langs = listOf("en")

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("ac_engine_test").toFile()
        dictManager =
            DictionaryManager(
                importedDir = File(tmpRoot, "dicts"),
                cacheDir = File(tmpRoot, "cache"),
                autocorrectDir = File(tmpRoot, "ac"),
            )

        val enFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/en.wmdict")
        val capsFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/en_caps.txt")
        val bigramsFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/en_bigrams.txt")

        mappedEn = MappedTrie.open(enFile)
        val caps = capsFile.inputStream().use { DictionaryManager.loadCapsStream(it) }
        val bigrams = bigramsFile.inputStream().use { SeedBigrams.load(it) }
        dictManager.registerTestDictionary("en", mappedEn, caps, bigrams)

        val esFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/es.wmdict")
        val esCapsFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/es_caps.txt")
        val esBigramsFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/es_bigrams.txt")
        mappedEs = MappedTrie.open(esFile)
        val esCaps = esCapsFile.inputStream().use { DictionaryManager.loadCapsStream(it) }
        val esBigrams = esBigramsFile.inputStream().use { SeedBigrams.load(it) }
        dictManager.registerTestDictionary("es", mappedEs, esCaps, esBigrams)

        val wmlmFile = PackedTrieAndCodecTest.locateAssetFile("dictionaries/en_context.wmlm")
        enModel = WmlmModel.open(wmlmFile.readBytes())!!
        dictManager.registerTestContextModel("en", enModel)
    }

    @After
    fun tearDown() {
        mappedEn.close()
        mappedEs.close()
        tmpRoot.deleteRecursively()
    }

    private fun evalWithProximity(
        typed: String,
        prox: KeyboardProximity,
        prevWord: String? = null,
        atSentenceStart: Boolean = false,
        languages: List<String> = langs,
        config: AutocorrectEngine.Config = AutocorrectEngine.Config(),
        lmSnapshot: WmlmSnapshot? = null,
    ): String? =
        AutocorrectEngine
            .evaluate(
                typed = typed,
                previousWord = prevWord,
                atSentenceStart = atSentenceStart,
                manuallyShifted = false,
                languages = languages,
                proximity = prox,
                config = config,
                stats = dictManager.correctionStats,
                memory = dictManager.correctionMemory,
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
                lmSnapshot = lmSnapshot,
            )?.replacement

    private fun eval(
        typed: String,
        prevWord: String? = null,
        atSentenceStart: Boolean = false,
        manuallyShifted: Boolean = false,
        languages: List<String> = langs,
        config: AutocorrectEngine.Config = AutocorrectEngine.Config(),
        lmSnapshot: WmlmSnapshot? = null,
    ): String? =
        AutocorrectEngine
            .evaluate(
                typed = typed,
                previousWord = prevWord,
                atSentenceStart = atSentenceStart,
                manuallyShifted = manuallyShifted,
                languages = languages,
                proximity = proximity,
                config = config,
                stats = dictManager.correctionStats,
                memory = dictManager.correctionMemory,
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
                lmSnapshot = lmSnapshot,
            )?.replacement

    @Test
    fun commonTyposAndSwapsCorrectAccurately() {
        assertEquals("the", eval("teh"))
        assertEquals("definitely", eval("definately"))
        assertEquals("receive", eval("recieve"))
        assertEquals("because", eval("becuase"))
    }

    @Test
    fun thumbKeySameKeySwipeSlipIsCheapAndFixesCleanly() {
        // In KB_EN_THUMBKEY, top-left center is 'e' and top-left swipe is 'q';
        // middle-center is 'i' and swipe on same key is 'k'/'j'
        assertTrue(proximity.distanceFactor('t', 'y') < KeyboardProximity.FAR)
        // Substituting a neighboring Thumb-Key letter on the same key yields low cost
        val sameKeyFactor = proximity.distanceFactor('a', 'q')
        assertEquals(KeyboardProximity.SAME_KEY_CENTER_SWIPE, sameKeyFactor, 1e-6)
        assertEquals("that", eval("thqt"))
    }

    @Test
    fun apostrophesAndCapitalsRespectAmbiguousWords() {
        assertEquals("don't", eval("dont"))
        assertEquals("I'm", eval("im"))
        assertNull(eval("were"))
        assertNull(eval("well"))
        assertNull(eval("its"))

        assertEquals("Monday", eval("monday"))
        assertEquals("America", eval("america"))
    }

    @Test
    fun guardsProtectCapsManualNamesAddressesCodeAndInflections() {
        assertNull(eval("NASA"))
        assertNull(eval("ASAP"))
        assertNull(eval("Tobi", atSentenceStart = false, manuallyShifted = false))
        assertNull(eval("Grok", atSentenceStart = true, manuallyShifted = true))
        assertNull(eval("foo@bar.com"))
        assertNull(eval("https://example.com"))
        assertNull(eval("snake_case"))
        assertNull(eval("camelCaseVar"))
        assertNull(eval("1st"))
        assertNull(eval("24px"))
        assertNull(eval("walked"))
        assertNull(eval("quickly"))
    }

    @Test
    fun missingSpacesSplitAndUndoRetiresCorrections() {
        assertEquals("in the", eval("inthe"))
        assertEquals("going to", eval("goingto"))

        // Recording two deliberate undos retires the pair across the session & file
        dictManager.correctionStats.recordRevert("teh", "the", deliberate = true)
        assertNull(eval("teh"))

        // Learning staging promotion at 3 observations stops correcting a personal coined word
        dictManager.userLexicon.observeWord("zylophone", knownInDictionary = false)
        dictManager.userLexicon.observeWord("zylophone", knownInDictionary = false)
        assertEquals(0, dictManager.userLexicon.frequencyOf("zylophone"))
        dictManager.userLexicon.observeWord("zylophone", knownInDictionary = false)
        assertTrue(dictManager.userLexicon.frequencyOf("zylophone") > 0)
        assertNull(eval("zylophone"))
    }

    @Test
    fun accentRestorationWorksOnAccentedDictionaries() {
        val frTrie =
            PackedTrie.of(
                listOf(
                    "café" to 8000,
                    "naïve" to 5000,
                    "déjà" to 6000,
                ),
            )
        dictManager.registerTestDictionary("fr", frTrie)
        assertEquals("café", eval("cafe", languages = listOf("fr")))
        assertEquals("déjà", eval("deja", languages = listOf("fr")))
    }

    @Test
    fun learnedGateAcceptsAdjacentSlipWhenEnabled() {
        val gateConfig = AutocorrectEngine.Config(useLearnedGate = true)
        assertEquals("that", eval("thqt", config = gateConfig))
        assertEquals("the", eval("teh", config = gateConfig))
    }

    @Test
    fun suggestionSpanMarkingSkipsCaseOnlyAndMarksRewrites() {
        assertTrue(AutocorrectSession.shouldMarkAutoCorrection("failed", "fsilrd"))
        assertTrue(AutocorrectSession.shouldMarkAutoCorrection("don't", "dont"))
        assertTrue(AutocorrectSession.shouldMarkAutoCorrection("in the", "inthe"))
        assertTrue(AutocorrectSession.shouldMarkAutoCorrection("café", "cafe"))

        assertFalse(AutocorrectSession.shouldMarkAutoCorrection("Monday", "monday"))
        assertFalse(AutocorrectSession.shouldMarkAutoCorrection("I", "i"))
        assertFalse(AutocorrectSession.shouldMarkAutoCorrection("hello", "hello"))
        assertFalse(AutocorrectSession.shouldMarkAutoCorrection("", "test"))
        assertFalse(AutocorrectSession.shouldMarkAutoCorrection("test", ""))
    }

    @Test
    fun bilingualEnglishAndSpanishPreservesSpanishAndRestoresAccents() {
        val bilingualLangs = listOf("en", "es")
        val config = AutocorrectEngine.Config(confidencePercent = 45)

        val preservedSpanish =
            listOf(
                "hola",
                "buenos",
                "gracias",
                "porque",
                "tiempo",
                "esta",
                "si",
                "el",
                "la",
                "de",
                "mi",
                "como",
                "un",
                "los",
                "quiero",
            )
        for (word in preservedSpanish) {
            assertNull("Expected Spanish word '$word' not to be mangled", eval(word, languages = bilingualLangs, config = config))
        }

        // Unambiguous Spanish missing accents restore cleanly
        assertEquals("mañana", eval("manana", languages = bilingualLangs, config = config))
        assertEquals("también", eval("tambien", languages = bilingualLangs, config = config))

        // Spanish typos fix cleanly
        assertEquals("gracias", eval("graicas", languages = bilingualLangs, config = config))
        assertEquals("porque", eval("porqeu", languages = bilingualLangs, config = config))

        // English fixes continue working cleanly under bilingual EN+ES
        assertEquals("the", eval("teh", languages = bilingualLangs, config = config))
        assertEquals("don't", eval("dont", languages = bilingualLangs, config = config))
        assertEquals("definitely", eval("definately", languages = bilingualLangs, config = config))
    }

    @Test
    fun contextModelEnhancesCorrectionsAndLiveSuggestionStrip() {
        val session = WmlmSession(enModel)
        session.setContext(listOf("the", "build"))
        val snapBuild = session.buildSnapshot(listOf("the", "build"), "en")
        assertTrue(snapBuild.logProbListed("failed") > snapBuild.logProbListed("filed"))
        // On test_thumbkey grid, q/a and r/e share keys -> 'fqilrd' has two same-key slips to 'failed'
        assertEquals("failed", eval("fqilrd", prevWord = "build", lmSnapshot = snapBuild))

        session.setContext(listOf("i", "want"))
        val snapWant = session.buildSnapshot(listOf("i", "want"), "en")
        val nextWords =
            AutocorrectEngine.suggestNextWords(
                previousWord = "want",
                atSentenceStart = false,
                languages = listOf("en"),
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
                lmSnapshot = snapWant,
            )
        assertEquals("to", nextWords.firstOrNull()?.text)

        val midWordEn =
            AutocorrectEngine.suggestMidWord(
                typed = "defin",
                previousWord = null,
                atSentenceStart = false,
                manuallyShifted = false,
                languages = listOf("en"),
                proximity = proximity,
                config = AutocorrectEngine.Config(),
                stats = dictManager.correctionStats,
                memory = dictManager.correctionMemory,
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
            )
        assertTrue(midWordEn.any { it.text == "definitely" })

        // Bilingual suggestion strip for `esta` offers both `esta` and accented `está`
        val midWordEs =
            AutocorrectEngine.suggestMidWord(
                typed = "esta",
                previousWord = null,
                atSentenceStart = false,
                manuallyShifted = false,
                languages = listOf("en", "es"),
                proximity = proximity,
                config = AutocorrectEngine.Config(confidencePercent = 45),
                stats = dictManager.correctionStats,
                memory = dictManager.correctionMemory,
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
            )
        assertTrue(midWordEs.any { it.text == "esta" })
        assertTrue(midWordEs.any { it.text == "está" })
    }

    @Test
    fun suggestionBarVisibilityFollowsFieldAndPanelRules() {
        assertFalse(
            shouldShowSuggestionBar(
                suggestionsEnabled = true,
                isRestrictedField = true,
                isPanelOpen = false,
                hasActiveLanguages = true,
            ),
        )
        assertTrue(
            shouldShowSuggestionBar(
                suggestionsEnabled = true,
                isRestrictedField = false,
                isPanelOpen = false,
                hasActiveLanguages = true,
            ),
        )
        assertFalse(
            shouldShowSuggestionBar(
                suggestionsEnabled = true,
                isRestrictedField = false,
                isPanelOpen = true,
                hasActiveLanguages = true,
            ),
        )
        assertFalse(
            shouldShowSuggestionBar(
                suggestionsEnabled = true,
                isRestrictedField = false,
                isPanelOpen = false,
                hasActiveLanguages = false,
            ),
        )
    }

    @Test
    fun realMessagEaseProximityAndSingleLanguageAccuracyAreSharp() {
        val messageaseProx = ThumbKeyProximity.fromDefinition(buildMessagEaseDefinition())
        // Same-key center <-> swipe ('o' <-> 'u'), adjacent ring ('u' <-> 'q'), orthogonal center ('a' <-> 'n')
        assertEquals(KeyboardProximity.SAME_KEY_CENTER_SWIPE, messageaseProx.distanceFactor('o', 'u'), 1e-6)
        assertEquals(KeyboardProximity.SAME_KEY_RING_ADJACENT, messageaseProx.distanceFactor('u', 'q'), 1e-6)
        assertEquals(KeyboardProximity.ADJACENT_CELL, messageaseProx.distanceFactor('a', 'n'), 1e-6)
        // Unrelated swipe directions across neighboring 3x3 cells must be FAR (not false adjacent slips)
        assertEquals(KeyboardProximity.FAR, messageaseProx.distanceFactor('c', 's'), 1e-6)
        assertEquals(KeyboardProximity.FAR, messageaseProx.distanceFactor('q', 'v'), 1e-6)

        val enCfg = AutocorrectEngine.Config(confidencePercent = 45)
        assertEquals("trying", evalWithProximity("tryng", messageaseProx, config = enCfg))
        assertEquals("something", evalWithProximity("somethng", messageaseProx, config = enCfg))
        assertEquals("definitely", evalWithProximity("definately", messageaseProx, config = enCfg))
        assertEquals("because", evalWithProximity("becuase", messageaseProx, config = enCfg))
        assertEquals("the", evalWithProximity("teh", messageaseProx, config = enCfg))
        assertEquals("don't", evalWithProximity("dont", messageaseProx, config = enCfg))

        // Single-language Spanish mode works cleanly without English confusion
        val esLangs = listOf("es")
        assertEquals("mañana", evalWithProximity("manana", messageaseProx, languages = esLangs, config = enCfg))
        assertEquals("también", evalWithProximity("tambien", messageaseProx, languages = esLangs, config = enCfg))
        assertEquals("gracias", evalWithProximity("grcias", messageaseProx, languages = esLangs, config = enCfg))
        assertEquals("porque", evalWithProximity("porqeu", messageaseProx, languages = esLangs, config = enCfg))
        val porqeChips =
            AutocorrectEngine.suggestMidWord(
                typed = "porqe",
                previousWord = null,
                atSentenceStart = false,
                manuallyShifted = false,
                languages = esLangs,
                proximity = messageaseProx,
                config = enCfg,
                stats = dictManager.correctionStats,
                memory = dictManager.correctionMemory,
                lexicon = dictManager.userLexicon,
                dictionaryManager = dictManager,
            )
        assertEquals("porque", porqeChips.firstOrNull()?.text)
        for (preserved in listOf("de", "la", "como", "si")) {
            assertNull(evalWithProximity(preserved, messageaseProx, languages = esLangs, config = enCfg))
        }
    }

    @Test
    fun retroactiveRevisionTailMatcherHandlesDelimitersAndGuardsBoundaries() {
        assertEquals(4, AutocorrectSession.findRevisionTailStart("I'm tryng to ", "tryng", listOf("to")))
        assertEquals(4, AutocorrectSession.findRevisionTailStart("I'm tryng to le", "tryng", listOf("to")))
        assertEquals(4, AutocorrectSession.findRevisionTailStart("I'm tryng to learn.", "tryng", listOf("to", "learn")))
        assertEquals(-1, AutocorrectSession.findRevisionTailStart("xtryng to ", "tryng", listOf("to")))
        assertEquals(-1, AutocorrectSession.findRevisionTailStart("I'm tryng. to ", "tryng", listOf("to")))
        assertEquals(-1, AutocorrectSession.findRevisionTailStart("I'm trying to ", "tryng", listOf("to")))
    }

    companion object {
        private fun keyC(letter: String): KeyC =
            KeyC(
                display = KeyDisplay.TextDisplay(letter),
                action = KeyAction.CommitText(letter),
            )

        private fun buildTestThumbKeyDefinition(): KeyboardDefinition {
            val main =
                KeyboardC(
                    listOf(
                        listOf(
                            KeyItemC(center = keyC("a"), topLeft = keyC("q"), bottom = keyC("z")),
                            KeyItemC(center = keyC("e"), topLeft = keyC("w"), topRight = keyC("r")),
                            KeyItemC(center = keyC("t"), topLeft = keyC("y"), topRight = keyC("u")),
                        ),
                        listOf(
                            KeyItemC(center = keyC("h"), left = keyC("g"), right = keyC("f")),
                            KeyItemC(center = keyC("i"), topLeft = keyC("k"), top = keyC("j")),
                            KeyItemC(center = keyC("o"), topLeft = keyC("p"), bottom = keyC("m")),
                        ),
                        listOf(
                            KeyItemC(center = keyC("n"), bottomLeft = keyC("b"), bottomRight = keyC("v")),
                            KeyItemC(center = keyC("s"), left = keyC("d"), right = keyC("c")),
                            KeyItemC(center = keyC("l"), topRight = keyC("x"), bottomRight = keyC("'")),
                        ),
                    ),
                )
            return KeyboardDefinition(
                title = "test_thumbkey",
                modes = KeyboardDefinitionModes(main = main, shifted = main, numeric = main),
            )
        }

        private fun buildMessagEaseDefinition(): KeyboardDefinition {
            val main =
                KeyboardC(
                    listOf(
                        listOf(
                            KeyItemC(center = keyC("a"), bottomRight = keyC("v")),
                            KeyItemC(center = keyC("n"), bottom = keyC("l")),
                            KeyItemC(center = keyC("i"), bottomLeft = keyC("x")),
                        ),
                        listOf(
                            KeyItemC(center = keyC("h"), right = keyC("k")),
                            KeyItemC(
                                center = keyC("o"),
                                topLeft = keyC("q"),
                                top = keyC("u"),
                                topRight = keyC("p"),
                                right = keyC("b"),
                                bottomRight = keyC("j"),
                                bottom = keyC("d"),
                                bottomLeft = keyC("g"),
                                left = keyC("c"),
                            ),
                            KeyItemC(center = keyC("r"), left = keyC("m")),
                        ),
                        listOf(
                            KeyItemC(center = keyC("t"), topRight = keyC("y")),
                            KeyItemC(center = keyC("e"), top = keyC("w"), right = keyC("z")),
                            KeyItemC(center = keyC("s"), topLeft = keyC("f")),
                        ),
                    ),
                )
            return KeyboardDefinition(
                title = "en_messagease_test",
                modes = KeyboardDefinitionModes(main = main, shifted = main, numeric = main),
            )
        }
    }
}
