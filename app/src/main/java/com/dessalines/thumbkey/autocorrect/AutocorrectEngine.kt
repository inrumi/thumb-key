package com.dessalines.thumbkey.autocorrect

/**
 * Pure-Kotlin autocorrect & live-suggestion orchestrator combining
 * `wmkeyboard`'s guards, contraction/capital/accent passes, [FuzzyBeamSearch],
 * 27-feature [Confidence] MLP gate, `.wmlm` context blending, missing-space
 * splits, [CorrectionMemory], [CorrectionStats], and [UserLexicon].
 */
object AutocorrectEngine {
    data class Config(
        val enabled: Boolean = true,
        val confidencePercent: Int = 40,
        val fixApostrophes: Boolean = true,
        val capitalizeNames: Boolean = true,
        val restoreAccents: Boolean = true,
        val fixMissingSpaces: Boolean = true,
        val learnWords: Boolean = true,
        val undoMemory: CorrectionStats.UndoMemory = CorrectionStats.UndoMemory.NORMAL,
        val ignoreAllCaps: Boolean = true,
        val useLearnedGate: Boolean = false,
    )

    data class Result(
        val replacement: String,
        val reason: String,
    )

    data class WordSuggestion(
        val text: String,
        val isPrimary: Boolean = false,
        val replacesCurrentWord: Boolean = true,
    )

    fun evaluate(
        typed: String,
        previousWord: String?,
        atSentenceStart: Boolean,
        manuallyShifted: Boolean,
        languages: List<String>,
        proximity: KeyboardProximity,
        config: Config,
        stats: CorrectionStats,
        memory: CorrectionMemory,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
        workspace: FuzzyBeamSearch.BeamWorkspace = FuzzyBeamSearch.BeamWorkspace(),
        lmSnapshot: WmlmSnapshot? = null,
    ): Result? {
        if (!config.enabled || languages.isEmpty() || typed.isEmpty()) return null
        if (typed.length > MAX_TOKEN_LENGTH) return null
        if (AddressGuard.shouldSkipToken(typed)) return null
        if (!dictionaryManager.hasAnyDictionaryFor(languages) && lexicon.wordCount() == 0) {
            return null
        }

        // Handle lone lowercase "i" -> "I" in English (respecting undo blocks)
        if (typed == "i" && config.capitalizeNames && languages.firstOrNull() == "en") {
            if (stats.penalty(typed, "I") != CorrectionStats.Penalty.BLOCKED) {
                return Result("I", "cap_i")
            }
            return null
        }
        if (typed.length < MIN_TOKEN_LENGTH) return null

        // ALL-CAPS guard (acronyms / SHOUTING)
        val letterCount = typed.count { it.isLetter() }
        if (config.ignoreAllCaps && letterCount >= 2 && WordCase.lettersAllUpper(typed)) {
            return null
        }

        // Manual shift capitalization guard (preserve user-typed proper nouns outside sentence start)
        if (manuallyShifted || (!atSentenceStart && WordCase.startsUpperCase(typed))) {
            return null
        }

        val lower = typed.lowercase()
        val primaryLang = languages.first()
        val primaryDict = dictionaryManager.sourceForLanguage(primaryLang)
        val activeSnapshot =
            lmSnapshot?.takeIf { snap ->
                languages.any { DictionaryManager.normalizeLang(it) == snap.languageId }
            }

        fun isNotBlocked(candidate: String): Boolean =
            candidate != typed && stats.penalty(typed, candidate) != CorrectionStats.Penalty.BLOCKED

        // 1. Dictionary & User frequency lookup for `lower` using each language's own normalized RankScale
        var bestDictFreq = 0
        var bestDictScore = 0.0
        for (idx in languages.indices) {
            val lang = languages[idx]
            val src = dictionaryManager.sourceForLanguage(lang) ?: continue
            val f = src.frequencyOf(lower)
            if (f > 0) {
                if (f > bestDictFreq) bestDictFreq = f
                val s =
                    dictionaryManager.scaleForLanguage(lang).ln1p(f) +
                        if (idx == 0) 0.0 else SECONDARY_LANG_LOG_WEIGHT
                if (s > bestDictScore) bestDictScore = s
            }
        }
        val userFreq = lexicon.frequencyOf(lower)
        val isRegularInflection =
            primaryLang == "en" &&
                primaryDict != null &&
                Inflections.isKnownInflection(lower, primaryDict)
        val typedIsKnown = bestDictFreq > 0 || userFreq > 0 || isRegularInflection

        // 2. Taught manual repairs from CorrectionMemory
        memory.lookupTaughtFix(lower, typedIsKnown)?.let { taughtLower ->
            val surfaced = WordCase.applyCase(typed, taughtLower, atSentenceStart)
            if (isNotBlocked(surfaced)) {
                return Result(surfaced, "memory")
            }
        }

        // 3. Unambiguous missing apostrophes (`dont` -> `don't`, `im` -> `I'm`, `cest` -> `c'est`),
        // guarded against valid lowercase words in any active secondary language.
        if (config.fixApostrophes) {
            for (lang in languages) {
                Apostrophes.fix(typed, lang)?.let { contracted ->
                    val protectedInOtherLang =
                        languages.size > 1 &&
                            languages.any { other ->
                                other != lang &&
                                    (dictionaryManager.sourceForLanguage(other)?.frequencyOf(lower) ?: 0) >=
                                    KNOWN_PROTECTION_FREQ
                            }
                    if (!protectedInOtherLang && isNotBlocked(contracted)) {
                        return Result(contracted, "apostrophe")
                    }
                }
            }
        }

        // 4. Dictionary & User capitals (`monday` -> `Monday`, `america` -> `America`, `ai` -> `AI`),
        // guarded so English acronyms/capitals (`LA`, `DE`, `UN`, `Los`, `Como`) never clobber Spanish words.
        if (config.capitalizeNames) {
            val targetCap = resolveSafeCapital(lower, languages, lexicon, dictionaryManager)
            if (targetCap != null && targetCap != typed && isNotBlocked(targetCap)) {
                return Result(targetCap, "cap_dict")
            }
        }

        // Known valid words are protected unless very rare uninflected accidental slips or missing accents
        val stronglyKnown = userFreq > 0 || isRegularInflection || bestDictFreq >= KNOWN_PROTECTION_FREQ
        if (stronglyKnown && !config.restoreAccents) {
            return null
        }

        // 5. Dropped-head short slip guard (`nd`, `ell` -> don't rewrite aggressively unless swap)
        val droppedHeadGuarded =
            primaryDict != null && DroppedHead.shouldGuardShortSlip(lower, primaryDict)

        // 6. Build WalkSource list for FuzzyBeamSearch
        val walkSources = buildWalkSources(languages, lexicon, dictionaryManager)
        if (walkSources.isEmpty()) return null

        val maxEdits = if (stronglyKnown) 1 else FuzzyBeamSearch.defaultMaxEdits(lower.length)
        val candidates =
            FuzzyBeamSearch.search(
                typed = lower,
                sources = walkSources,
                proximity = proximity,
                maxEdits = maxEdits,
                allowAccents = config.restoreAccents,
                limit = FuzzyBeamSearch.AUTOCORRECT_K,
                workspace = workspace,
            )

        val filtered =
            candidates.filter { c ->
                if (c.word == lower) return@filter false
                if (!config.restoreAccents && c.edits == 0 && c.accents > 0) return@filter false
                if (stronglyKnown && !(config.restoreAccents && c.edits == 0 && c.accents > 0)) {
                    return@filter false
                }
                if (droppedHeadGuarded && !EditOps.isOneSwap(lower, c.word)) {
                    return@filter false
                }
                val candidateSurface = WordCase.applyCase(typed, c.word, atSentenceStart)
                isNotBlocked(candidateSurface)
            }
        val baseRanked = filtered.filter { it.edits == 0 && it.accents > 0 }.ifEmpty { filtered }
        val ranked = applyLmBlend(baseRanked, activeSnapshot)

        fun trySplitResult(): Result? {
            if (!config.fixMissingSpaces ||
                stronglyKnown ||
                lower.length < 4 ||
                lower.contains('\'') ||
                lower.contains('’')
            ) {
                return null
            }
            val splitResult =
                findBestSpaceSplit(
                    typed = typed,
                    lower = lower,
                    previousWord = previousWord?.lowercase(),
                    atSentenceStart = atSentenceStart,
                    languages = languages,
                    primaryLang = primaryLang,
                    dictionaryManager = dictionaryManager,
                    lexicon = lexicon,
                ) ?: return null
            return if (isNotBlocked(splitResult)) Result(splitResult, "split") else null
        }

        if (ranked.isNotEmpty()) {
            val top = ranked[0]
            val runner =
                if (!top.mixesSlips) {
                    ranked.drop(1).firstOrNull { !it.mixesSlips }
                } else {
                    ranked.getOrNull(1)
                }
            val typedScore =
                maxOf(
                    bestDictScore,
                    if (userFreq > 0) USER_LEXICON_LOG_WEIGHT + RankScale.RAW.ln1p(userFreq) else 0.0,
                )
            val effectiveRunnerScore = maxOf(runner?.score ?: SOLO_RUNNER_UP_SCORE, typedScore)
            val margin = top.score - effectiveRunnerScore
            val typedShadowed = bestDictFreq > 0 && top.score > typedScore + 2.0
            val twoSources = top.fromDictionary && top.fromUser && !stronglyKnown

            val pairPenalty = stats.penalty(typed, top.word)
            val penaltyBump =
                if (pairPenalty == CorrectionStats.Penalty.PENALIZED ||
                    pairPenalty == CorrectionStats.Penalty.PROBATION
                ) {
                    PENALTY_CONFIDENCE_BUMP
                } else {
                    0.0
                }
            val baseSlider = (config.confidencePercent / 10.0).coerceIn(1.5, 10.0)
            val effectiveSlider = (baseSlider * stats.multiplier() + penaltyBump).coerceIn(1.5, 10.0)
            val gate = StrictMath.log(effectiveSlider)

            val lmScores = activeSnapshot?.scores(ranked.map { it.word })
            val lmTop = lmScores?.getOrNull(0) ?: Float.NaN
            val lmSorted = lmScores?.filterNot { it.isNaN() }?.sortedDescending().orEmpty()
            val lmBest = lmSorted.firstOrNull() ?: Float.NaN
            val lmSecond = lmSorted.getOrNull(1) ?: Float.NaN
            val lmTyped = activeSnapshot?.logProbListed(lower) ?: Float.NaN
            val lmTypedRank = if (activeSnapshot != null && !lmTyped.isNaN()) activeSnapshot.rankOf(lower) else -1
            val lmEntropy = activeSnapshot?.entropy ?: Float.NaN

            // Protect a typed known word if the neural context model says it fits well here
            val lmProtectsTyped =
                typedIsKnown &&
                    !lmTyped.isNaN() &&
                    lmTyped >= -5.5f &&
                    (lmTop.isNaN() || lmTop - lmTyped < 2.5f)

            val isSwapWinner =
                !stronglyKnown &&
                    top.edits == 1 &&
                    EditOps.isOneSwap(lower, top.word) &&
                    margin >= 0.5 &&
                    pairPenalty == CorrectionStats.Penalty.NONE

            val refusedByUndo =
                pairPenalty == CorrectionStats.Penalty.PROBATION ||
                    (ranked.size == 1 && pairPenalty != CorrectionStats.Penalty.NONE) ||
                    lmProtectsTyped

            val isStronglyKnownAccentSibling = stronglyKnown && top.edits == 0 && top.accents > 0

            val passesGate =
                if (!refusedByUndo) {
                    if (isStronglyKnownAccentSibling) {
                        // Valid unaccented words (`esta`, `si`, `el`) must not be overwritten lightly;
                        // offer `está`/`sí`/`él` on the suggestion strip instead.
                        margin >= maxOf(gate, STRONGLY_KNOWN_ACCENT_MARGIN)
                    } else if (config.useLearnedGate) {
                        val case =
                            buildConfidenceCase(
                                margin = margin,
                                top = top,
                                runner = runner,
                                twoSources = twoSources,
                                typedLength = lower.length,
                                candidatesCount = ranked.size,
                                typedShadowed = typedShadowed,
                                lmTop = lmTop,
                                lmBest = lmBest,
                                lmSecond = lmSecond,
                                lmTyped = lmTyped,
                                lmTypedRank = lmTypedRank,
                                lmEntropy = lmEntropy,
                            )
                        Confidence.probability(case) >= Confidence.applyThreshold(effectiveSlider) || isSwapWinner
                    } else {
                        val lmRescue =
                            if (activeSnapshot != null && margin >= gate * 0.65) {
                                val case =
                                    buildConfidenceCase(
                                        margin = margin,
                                        top = top,
                                        runner = runner,
                                        twoSources = twoSources,
                                        typedLength = lower.length,
                                        candidatesCount = ranked.size,
                                        typedShadowed = typedShadowed,
                                        lmTop = lmTop,
                                        lmBest = lmBest,
                                        lmSecond = lmSecond,
                                        lmTyped = lmTyped,
                                        lmTypedRank = lmTypedRank,
                                        lmEntropy = lmEntropy,
                                    )
                                Confidence.probability(case) >= Confidence.applyThreshold(effectiveSlider)
                            } else {
                                false
                            }
                        twoSources || margin >= gate || isSwapWinner || lmRescue
                    }
                } else {
                    false
                }

            if (passesGate) {
                if (top.edits >= 2) {
                    trySplitResult()?.let { return it }
                }
                val finalOut =
                    formatSuggestionSurface(typed, top.word, atSentenceStart, config.capitalizeNames, languages, lexicon, dictionaryManager)
                if (isNotBlocked(finalOut)) {
                    val reason =
                        when {
                            top.edits == 0 && top.accents > 0 -> "accent"
                            isSwapWinner -> "swap"
                            else -> "fuzzy"
                        }
                    return Result(finalOut, reason)
                }
            }
        }

        // 7. Missing-space word split (`inthe` -> `in the`, `thankyou` -> `thank you`)
        trySplitResult()?.let { return it }

        return null
    }

    fun suggestMidWord(
        typed: String,
        previousWord: String?,
        atSentenceStart: Boolean,
        manuallyShifted: Boolean,
        languages: List<String>,
        proximity: KeyboardProximity,
        config: Config,
        stats: CorrectionStats,
        memory: CorrectionMemory,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
        workspace: FuzzyBeamSearch.BeamWorkspace = FuzzyBeamSearch.BeamWorkspace(),
        lmSnapshot: WmlmSnapshot? = null,
        limit: Int = DEFAULT_STRIP_LIMIT,
    ): List<WordSuggestion> {
        if (languages.isEmpty() || typed.isEmpty() || limit <= 0) return emptyList()
        if (typed.length > MAX_TOKEN_LENGTH || AddressGuard.shouldSkipToken(typed)) return emptyList()
        if (!dictionaryManager.hasAnyDictionaryFor(languages) && lexicon.wordCount() == 0) {
            return emptyList()
        }

        val lower = typed.lowercase()
        val prevLower = previousWord?.lowercase()
        val activeSnapshot =
            lmSnapshot?.takeIf { snap ->
                languages.any { DictionaryManager.normalizeLang(it) == snap.languageId }
            }

        val mergedScores = LinkedHashMap<String, Double>(24)

        fun putMax(
            wordLower: String,
            score: Double,
        ) {
            val prev = mergedScores[wordLower]
            if (prev == null || score > prev) {
                mergedScores[wordLower] = score
            }
        }

        // 1. Evaluate boundary auto-correction (if length >= 1, run with enabled=true for primary guess)
        val autoFix =
            if (typed.length >= 2) {
                evaluate(
                    typed = typed,
                    previousWord = previousWord,
                    atSentenceStart = atSentenceStart,
                    manuallyShifted = manuallyShifted,
                    languages = languages,
                    proximity = proximity,
                    config = config.copy(enabled = true),
                    stats = stats,
                    memory = memory,
                    lexicon = lexicon,
                    dictionaryManager = dictionaryManager,
                    workspace = workspace,
                    lmSnapshot = activeSnapshot,
                )
            } else if (typed == "i" && config.capitalizeNames && languages.firstOrNull() == "en") {
                Result("I", "cap_i")
            } else {
                null
            }

        // 2. Prefix completions from active dictionaries & personal lexicon
        for (idx in languages.indices) {
            val lang = languages[idx]
            val src = dictionaryManager.sourceForLanguage(lang) ?: continue
            val scale = dictionaryManager.scaleForLanguage(lang)
            val langWeight = if (idx == 0) 0.0 else SECONDARY_LANG_LOG_WEIGHT
            val completions = src.complete(lower, limit = 8, includePrefix = true)
            for (s in completions) {
                val exactBonus = if (s.word == lower) 0.6 else 0.0
                putMax(s.word, scale.ln1p(s.frequency) + langWeight + exactBonus)
            }
        }
        val userCompletions = lexicon.wordSource().complete(lower, limit = 6, includePrefix = true)
        for (s in userCompletions) {
            val exactBonus = if (s.word == lower) 0.6 else 0.0
            putMax(s.word, USER_LEXICON_LOG_WEIGHT + RankScale.RAW.ln1p(s.frequency) + exactBonus)
        }

        // 3. Fuzzy walk candidates for mid-word typo suggestions & accent siblings (`esta` -> `está`)
        if (typed.length >= 2) {
            val walkSources = buildWalkSources(languages, lexicon, dictionaryManager)
            if (walkSources.isNotEmpty()) {
                val fuzzy =
                    FuzzyBeamSearch.search(
                        typed = lower,
                        sources = walkSources,
                        proximity = proximity,
                        maxEdits = FuzzyBeamSearch.defaultMaxEdits(lower.length).coerceAtMost(2),
                        allowAccents = config.restoreAccents,
                        limit = 8,
                        workspace = workspace,
                    )
                for (c in fuzzy) {
                    if (stats.penalty(typed, c.word) == CorrectionStats.Penalty.BLOCKED) continue
                    val accentSiblingBoost = if (c.edits == 0 && c.accents > 0) 0.4 else 0.0
                    putMax(c.word, c.score + accentSiblingBoost)
                }
            }
        }

        // 4. Apply bigram + .wmlm context boost across candidates
        if (mergedScores.isNotEmpty()) {
            val keys = mergedScores.keys.toList()
            for (k in keys) {
                var s = mergedScores.getValue(k)
                if (prevLower != null) {
                    val seedBg = dictionaryManager.seedBigramScore(languages, prevLower, k)
                    val userBg = lexicon.bigramCount(prevLower, k)
                    if (seedBg > 0 || userBg > 0) {
                        s += StrictMath.log(1.0 + seedBg * 0.02 + userBg * 1.5).coerceAtMost(1.8)
                    }
                }
                mergedScores[k] = s
            }
            if (activeSnapshot != null && mergedScores.size >= 2) {
                val sortedKeys = mergedScores.entries.sortedByDescending { it.value }.map { it.key }
                val lmRef = activeSnapshot.logProbListed(sortedKeys[0])
                if (!lmRef.isNaN()) {
                    for (k in sortedKeys) {
                        val lp = activeSnapshot.logProbListed(k)
                        if (!lp.isNaN()) {
                            val shift = (LM_BLEND * (lp - lmRef)).coerceIn(-LM_BLEND_CAP, LM_BLEND_CAP)
                            mergedScores[k] = mergedScores.getValue(k) + shift
                        }
                    }
                }
            }
        }

        val ordered = ArrayList<WordSuggestion>(limit)
        val seenDisplay = HashSet<String>(limit * 2)

        fun addSuggestion(
            text: String,
            isPrimary: Boolean,
        ) {
            if (text.isEmpty() || ordered.size >= limit) return
            if (seenDisplay.add(text.lowercase())) {
                ordered.add(WordSuggestion(text = text, isPrimary = isPrimary, replacesCurrentWord = true))
            }
        }

        if (autoFix != null) {
            addSuggestion(autoFix.replacement, isPrimary = true)
        } else if (config.fixApostrophes) {
            for (lang in languages) {
                val contracted = Apostrophes.fix(typed, lang) ?: continue
                if (stats.penalty(typed, contracted) != CorrectionStats.Penalty.BLOCKED) {
                    addSuggestion(contracted, isPrimary = ordered.isEmpty())
                    break
                }
            }
        }

        // If user typed a valid unaccented word that has an accented twin (`esta` -> `está`, `si` -> `sí`),
        // ensure exact typed word + accented twin lead cleanly.
        val exactLowerKnown = mergedScores.containsKey(lower)
        val rankedWords = mergedScores.entries.sortedByDescending { it.value }.map { it.key }
        if (exactLowerKnown && autoFix == null) {
            val selfSurface =
                formatSuggestionSurface(
                    typed = typed,
                    wordLower = lower,
                    atSentenceStart = atSentenceStart,
                    capitalizeNames = config.capitalizeNames,
                    languages = languages,
                    lexicon = lexicon,
                    dictionaryManager = dictionaryManager,
                )
            addSuggestion(selfSurface, isPrimary = ordered.isEmpty())
        }

        for (w in rankedWords) {
            if (ordered.size >= limit) break
            val surface =
                formatSuggestionSurface(
                    typed = typed,
                    wordLower = w,
                    atSentenceStart = atSentenceStart,
                    capitalizeNames = config.capitalizeNames,
                    languages = languages,
                    lexicon = lexicon,
                    dictionaryManager = dictionaryManager,
                )
            addSuggestion(surface, isPrimary = ordered.isEmpty() && config.enabled)
        }

        // Preserve exact typed token as a tap-to-keep option if nothing else took first place
        if (ordered.isEmpty() && typed.any { it.isLetter() }) {
            addSuggestion(typed, isPrimary = false)
        }

        return ordered
    }

    fun suggestNextWords(
        previousWord: String?,
        atSentenceStart: Boolean,
        languages: List<String>,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
        capitalizeNames: Boolean = true,
        lmSnapshot: WmlmSnapshot? = null,
        limit: Int = DEFAULT_STRIP_LIMIT,
    ): List<WordSuggestion> {
        if (languages.isEmpty() || limit <= 0) return emptyList()

        val activeSnapshot =
            lmSnapshot?.takeIf { snap ->
                languages.any { DictionaryManager.normalizeLang(it) == snap.languageId }
            }
        val prevLower = previousWord?.lowercase()

        val out = ArrayList<WordSuggestion>(limit)
        val seen = HashSet<String>(limit * 2)

        fun addCandidate(rawWord: String) {
            if (rawWord.isEmpty() || rawWord.startsWith('<') || out.size >= limit) return
            val lower = rawWord.lowercase()
            if (!seen.add(lower)) return
            val surfaced =
                when {
                    lower == "i" && capitalizeNames && languages.firstOrNull() == "en" -> {
                        "I"
                    }

                    rawWord.any { it.isUpperCase() } -> {
                        rawWord
                    }

                    atSentenceStart -> {
                        WordCase.capitalizeFirst(rawWord)
                    }

                    capitalizeNames -> {
                        resolveSafeCapital(lower, languages, lexicon, dictionaryManager) ?: rawWord
                    }

                    else -> {
                        rawWord
                    }
                }
            out.add(WordSuggestion(text = surfaced, isPrimary = false, replacesCurrentWord = false))
        }

        if (!atSentenceStart && prevLower != null) {
            val userFollowers = lexicon.nextWords(prevLower, limit * 2)
            // 1. Habitual personal bigrams (typed 2+ times)
            for ((w, count) in userFollowers) {
                if (count >= 2) addCandidate(w)
            }
            // 2. Neural .wmlm context predictions
            activeSnapshot?.topWords?.forEach { (w, _) -> addCandidate(w) }
            // 3. Seed bigrams
            for (w in dictionaryManager.seedNextWords(languages, prevLower, limit * 2)) {
                addCandidate(w)
            }
            // 4. Single-use personal bigrams
            for ((w, count) in userFollowers) {
                if (count == 1) addCandidate(w)
            }
        } else {
            // Sentence opening
            activeSnapshot?.topWords?.forEach { (w, _) -> addCandidate(w) }
            for (w in dictionaryManager.seedNextWords(languages, "<s>", limit * 2)) {
                addCandidate(w)
            }
            val primary = languages.firstOrNull() ?: "en"
            val fallback =
                when (primary) {
                    "es" -> DEFAULT_ES_OPENERS
                    else -> DEFAULT_EN_OPENERS
                }
            for (w in fallback) addCandidate(w)
        }

        return out
    }

    fun revisionCandidates(
        typed: String,
        atSentenceStart: Boolean,
        languages: List<String>,
        proximity: KeyboardProximity,
        config: Config,
        stats: CorrectionStats,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
        workspace: FuzzyBeamSearch.BeamWorkspace = FuzzyBeamSearch.BeamWorkspace(),
        limit: Int = 4,
    ): List<String> {
        if (!config.enabled || languages.isEmpty() || typed.length < MIN_TOKEN_LENGTH || typed.length > MAX_TOKEN_LENGTH) {
            return emptyList()
        }
        if (AddressGuard.shouldSkipToken(typed)) return emptyList()
        val letterCount = typed.count { it.isLetter() }
        if (config.ignoreAllCaps && letterCount >= 2 && WordCase.lettersAllUpper(typed)) {
            return emptyList()
        }
        val lower = typed.lowercase()
        val walkSources = buildWalkSources(languages, lexicon, dictionaryManager)
        if (walkSources.isEmpty()) return emptyList()
        val candidates =
            FuzzyBeamSearch.search(
                typed = lower,
                sources = walkSources,
                proximity = proximity,
                maxEdits = FuzzyBeamSearch.defaultMaxEdits(lower.length),
                allowAccents = config.restoreAccents,
                limit = limit + 4,
                workspace = workspace,
            )
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)
        for (c in candidates) {
            if (out.size >= limit) break
            if (c.word == lower) continue
            if (!config.restoreAccents && c.edits == 0 && c.accents > 0) continue
            val surfaced =
                formatSuggestionSurface(
                    typed = typed,
                    wordLower = c.word,
                    atSentenceStart = atSentenceStart,
                    capitalizeNames = config.capitalizeNames,
                    languages = languages,
                    lexicon = lexicon,
                    dictionaryManager = dictionaryManager,
                )
            if (surfaced == typed || stats.penalty(typed, surfaced) == CorrectionStats.Penalty.BLOCKED) continue
            if (seen.add(surfaced.lowercase())) {
                out.add(surfaced)
            }
        }
        return out
    }

    private fun buildWalkSources(
        languages: List<String>,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
    ): List<FuzzyBeamSearch.WalkSource> {
        val walkSources = ArrayList<FuzzyBeamSearch.WalkSource>(languages.size + 1)
        for (idx in languages.indices) {
            val lang = languages[idx]
            val src = dictionaryManager.sourceForLanguage(lang) ?: continue
            val scale = dictionaryManager.scaleForLanguage(lang)
            val weight = if (idx == 0) 0.0 else SECONDARY_LANG_LOG_WEIGHT
            for (w in src.walkers()) {
                walkSources.add(
                    FuzzyBeamSearch.WalkSource(
                        walker = w,
                        logWeight = weight,
                        rankWeight = 1.0,
                        scale = scale,
                        isUser = false,
                        isDictionary = true,
                    ),
                )
            }
        }
        val userWordSrc = lexicon.wordSource()
        if (userWordSrc.wordCount() > 0) {
            for (w in userWordSrc.walkers()) {
                walkSources.add(
                    FuzzyBeamSearch.WalkSource(
                        walker = w,
                        logWeight = USER_LEXICON_LOG_WEIGHT,
                        rankWeight = 1.0,
                        scale = RankScale.RAW,
                        isUser = true,
                        isDictionary = false,
                    ),
                )
            }
        }
        return walkSources
    }

    private fun applyLmBlend(
        ranked: List<FuzzyBeamSearch.Candidate>,
        lmSnapshot: WmlmSnapshot?,
    ): List<FuzzyBeamSearch.Candidate> {
        if (lmSnapshot == null || ranked.size < 2) return ranked
        val lmScores = lmSnapshot.scores(ranked.map { it.word })
        val ref = lmScores.getOrNull(0) ?: return ranked
        if (ref.isNaN()) return ranked
        return ranked
            .mapIndexed { idx, cand ->
                val lp = lmScores[idx]
                val shift =
                    if (!lp.isNaN()) {
                        (LM_BLEND * (lp - ref)).coerceIn(-LM_BLEND_CAP, LM_BLEND_CAP)
                    } else {
                        0.0
                    }
                cand to (cand.score + shift)
            }.sortedByDescending { it.second }
            .map { (cand, newScore) ->
                if (newScore == cand.score) cand else cand.copy(score = newScore)
            }
    }

    private fun buildConfidenceCase(
        margin: Double,
        top: FuzzyBeamSearch.Candidate,
        runner: FuzzyBeamSearch.Candidate?,
        twoSources: Boolean,
        typedLength: Int,
        candidatesCount: Int,
        typedShadowed: Boolean,
        lmTop: Float,
        lmBest: Float,
        lmSecond: Float,
        lmTyped: Float,
        lmTypedRank: Int,
        lmEntropy: Float,
    ): Confidence.Case =
        Confidence.Case(
            margin = margin,
            topScore = top.score,
            topEditCost = top.editCost,
            topEdits = top.edits,
            topSlips = top.slips,
            topAccents = top.accents,
            topCompleted = top.completed,
            topInDictionary = top.fromDictionary,
            topInLexicon = top.fromUser,
            twoSources = twoSources,
            mixesSlips = top.mixesSlips,
            typedLength = typedLength,
            hasRunner = runner != null,
            runnerEditCost = runner?.editCost ?: 0.0,
            candidates = candidatesCount,
            lmTop = lmTop,
            lmBest = lmBest,
            typedShadowed = typedShadowed,
            lmTyped = lmTyped,
            lmTypedRank = lmTypedRank,
            lmEntropy = lmEntropy,
            lmSecond = lmSecond,
        )

    private fun resolveSafeCapital(
        lower: String,
        languages: List<String>,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
    ): String? {
        lexicon.preferredCase(lower)?.let { return it }
        for (lang in languages) {
            val candidateCap = dictionaryManager.capitalsForLanguage(lang, lower) ?: continue
            val shadowedAsLowercaseInOtherLang =
                languages.any { other ->
                    other != lang &&
                        (dictionaryManager.sourceForLanguage(other)?.frequencyOf(lower) ?: 0) >=
                        KNOWN_PROTECTION_FREQ &&
                        dictionaryManager.capitalsForLanguage(other, lower) == null
                }
            if (!shadowedAsLowercaseInOtherLang) {
                return candidateCap
            }
        }
        return null
    }

    private fun formatSuggestionSurface(
        typed: String,
        wordLower: String,
        atSentenceStart: Boolean,
        capitalizeNames: Boolean,
        languages: List<String>,
        lexicon: UserLexicon,
        dictionaryManager: DictionaryManager,
    ): String {
        val letterCount = typed.count { it.isLetter() }
        val cased = WordCase.applyCase(typed, wordLower, atSentenceStart)
        if (!capitalizeNames || (letterCount >= 2 && WordCase.lettersAllUpper(typed))) {
            return cased
        }
        return resolveSafeCapital(wordLower, languages, lexicon, dictionaryManager) ?: cased
    }

    private fun findBestSpaceSplit(
        typed: String,
        lower: String,
        previousWord: String?,
        atSentenceStart: Boolean,
        languages: List<String>,
        primaryLang: String,
        dictionaryManager: DictionaryManager,
        lexicon: UserLexicon,
    ): String? {
        var bestScore = Double.NEGATIVE_INFINITY
        var bestLeft: String? = null
        var bestRight: String? = null

        val primaryDict = dictionaryManager.sourceForLanguage(primaryLang) ?: return null
        val scale = dictionaryManager.scaleForLanguage(primaryLang)

        for (splitAt in 1 until lower.length) {
            val left = lower.substring(0, splitAt)
            val right = lower.substring(splitAt)
            if (!isAllowedSplitHalf(left, primaryLang, isLeft = true)) continue
            if (!isAllowedSplitHalf(right, primaryLang, isLeft = false)) continue

            val fLeft = maxOf(primaryDict.frequencyOf(left), lexicon.frequencyOf(left))
            val fRight = maxOf(primaryDict.frequencyOf(right), lexicon.frequencyOf(right))
            if (fLeft < SPLIT_MIN_PART_FREQ || fRight < SPLIT_MIN_PART_FREQ) continue

            val bigramSeed = dictionaryManager.seedBigramScore(languages, left, right)
            val bigramUser = lexicon.bigramCount(left, right)
            val prevBigram =
                if (previousWord != null) {
                    dictionaryManager.seedBigramScore(languages, previousWord, left) +
                        lexicon.bigramCount(previousWord, left) * 5
                } else {
                    0
                }

            val totalBigramBonus =
                if (bigramSeed > 0 || bigramUser > 0) {
                    StrictMath.log(1.0 + bigramSeed + bigramUser * 10.0)
                } else {
                    0.0
                }
            val bothVeryCommon = fLeft >= SPLIT_COMMON_WORD_FREQ && fRight >= SPLIT_COMMON_WORD_FREQ
            if (totalBigramBonus == 0.0 && !bothVeryCommon) continue

            val score =
                scale.ln1p(fLeft) +
                    scale.ln1p(fRight) +
                    totalBigramBonus * 2.0 +
                    (if (prevBigram > 0) 1.0 else 0.0)

            if (score > bestScore) {
                bestScore = score
                bestLeft = left
                bestRight = right
            }
        }

        if (bestLeft == null || bestRight == null || bestScore < SPLIT_MIN_SCORE) return null

        val leftOut =
            when {
                primaryLang == "en" && bestLeft == "i" -> "I"
                else -> WordCase.applyCase(typed.take(1), bestLeft, atSentenceStart)
            }
        val rightOut =
            if (primaryLang == "en" && bestRight == "i") "I" else bestRight
        return "$leftOut $rightOut"
    }

    private fun isAllowedSplitHalf(
        part: String,
        primaryLang: String,
        isLeft: Boolean,
    ): Boolean {
        if (part.length >= 2) return true
        return when (primaryLang) {
            "en" -> part == "i" || part == "a"
            "es", "pt", "it", "ca" -> part in ONE_CHAR_ROMANCE_WORDS && isLeft
            "fr" -> (part == "y" || part == "a") && isLeft
            else -> false
        }
    }

    private val ONE_CHAR_ROMANCE_WORDS = setOf("a", "e", "o", "y")
    private val DEFAULT_EN_OPENERS = listOf("I", "The", "Hey", "What", "We", "Yes")
    private val DEFAULT_ES_OPENERS = listOf("Hola", "Que", "El", "La", "Sí", "No")

    private const val MIN_TOKEN_LENGTH = 2
    private const val MAX_TOKEN_LENGTH = 36
    private const val KNOWN_PROTECTION_FREQ = 5
    private const val SECONDARY_LANG_LOG_WEIGHT = -1.2
    private const val USER_LEXICON_LOG_WEIGHT = 0.5
    private const val SOLO_RUNNER_UP_SCORE = 1.0
    private const val PENALTY_CONFIDENCE_BUMP = 1.5
    private const val STRONGLY_KNOWN_ACCENT_MARGIN = 2.5
    private const val LM_BLEND = 0.35
    private const val LM_BLEND_CAP = 1.5
    private const val SPLIT_MIN_PART_FREQ = 500
    private const val SPLIT_COMMON_WORD_FREQ = 2500
    private const val SPLIT_MIN_SCORE = 16.0
    private const val DEFAULT_STRIP_LIMIT = 6
}
