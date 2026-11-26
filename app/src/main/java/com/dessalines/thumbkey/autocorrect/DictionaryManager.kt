package com.dessalines.thumbkey.autocorrect

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates bundled offline dictionaries, user-imported `.txt`/`.txt.gz`/`.wmdict`
 * packs, offline `.wmlm` neural context models, emoji suggestion tables, and
 * on-device learning stores.
 */
class DictionaryManager {
    private class LoadedDict(
        val source: WordSource,
        val scale: RankScale,
        val capitals: Map<String, String>,
        val seedBigrams: SeedBigrams,
        val isBundled: Boolean,
        val sizeBytes: Long,
    )

    private class LoadedContextModel(
        val model: WmlmModel,
        val isBundled: Boolean,
        val sizeBytes: Long,
    )

    data class DictionaryInfo(
        val languageId: String,
        val wordCount: Int,
        val isBundled: Boolean,
        val isImported: Boolean,
        val sizeBytes: Long,
    )

    data class ContextModelInfo(
        val languageId: String,
        val name: String,
        val vocabSize: Int,
        val isBundled: Boolean,
        val isImported: Boolean,
        val sizeBytes: Long,
    )

    data class ImportResult(
        val languageId: String,
        val wordCount: Int,
        val sizeBytes: Long,
    )

    private val appContext: Context?
    private val importedDir: File
    private val contextModelsDir: File
    private val bundledCacheDir: File

    val userLexicon: UserLexicon
    val correctionStats: CorrectionStats
    val correctionMemory: CorrectionMemory

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dictionaries = ConcurrentHashMap<String, LoadedDict>()
    private val contextModels = ConcurrentHashMap<String, LoadedContextModel>()
    private val initStarted = AtomicBoolean(false)

    @Volatile
    var emojiSuggester: EmojiSuggester = EmojiSuggester.EMPTY
        private set

    @Volatile
    private var contextModelsRequested: Boolean = false

    @Volatile
    var isReady: Boolean = false
        private set

    constructor(context: Context) {
        val ctx = context.applicationContext
        this.appContext = ctx
        this.importedDir = File(ctx.filesDir, "dictionaries")
        this.contextModelsDir = File(ctx.filesDir, "context_models")
        this.bundledCacheDir = File(ctx.cacheDir, "dictionaries")
        val autocorrectDir = File(ctx.filesDir, "autocorrect")
        this.userLexicon = UserLexicon(File(autocorrectDir, "user_lexicon.json"))
        this.correctionStats = CorrectionStats(File(autocorrectDir, "correction_stats.json"))
        this.correctionMemory = CorrectionMemory(File(autocorrectDir, "correction_memory.json"))
    }

    /** Constructor for pure-JVM unit tests (no Android Context required). */
    constructor(
        importedDir: File,
        cacheDir: File,
        autocorrectDir: File,
    ) {
        this.appContext = null
        this.importedDir = importedDir
        this.contextModelsDir = File(importedDir, "context_models")
        this.bundledCacheDir = cacheDir
        this.userLexicon = UserLexicon(File(autocorrectDir, "user_lexicon.json"))
        this.correctionStats = CorrectionStats(File(autocorrectDir, "correction_stats.json"))
        this.correctionMemory = CorrectionMemory(File(autocorrectDir, "correction_memory.json"))
    }

    fun ensureInitializedAsync() {
        if (initStarted.compareAndSet(false, true)) {
            ioScope.launch {
                loadAllInternal()
            }
        }
    }

    fun ensureInitializedBlocking() {
        if (initStarted.compareAndSet(false, true) || !isReady) {
            loadAllInternal()
        }
    }

    fun ensureContextModelLoadedAsync(enabled: Boolean) {
        contextModelsRequested = enabled
        ioScope.launch {
            if (enabled) {
                loadContextModelsInternal()
            } else if (appContext != null) {
                contextModels.clear()
            }
        }
    }

    fun registerTestDictionary(
        languageId: String,
        source: WordSource,
        capitals: Map<String, String> = emptyMap(),
        seedBigrams: SeedBigrams = SeedBigrams.EMPTY,
    ) {
        dictionaries[normalizeLang(languageId)] =
            LoadedDict(
                source = source,
                scale = computeRankScale(source),
                capitals = capitals,
                seedBigrams = seedBigrams,
                isBundled = true,
                sizeBytes = 0L,
            )
        isReady = true
    }

    fun registerTestContextModel(
        languageId: String,
        model: WmlmModel,
    ) {
        contextModels[normalizeLang(languageId)] =
            LoadedContextModel(
                model = model,
                isBundled = true,
                sizeBytes = 0L,
            )
    }

    fun registerTestEmojiSuggester(suggester: EmojiSuggester) {
        emojiSuggester = suggester
    }

    fun sourceForLanguage(lang: String): WordSource? = dictionaries[normalizeLang(lang)]?.source

    fun scaleForLanguage(lang: String): RankScale = dictionaries[normalizeLang(lang)]?.scale ?: RankScale.RAW

    fun capitalsForLanguage(
        lang: String,
        lowerWord: String,
    ): String? = dictionaries[normalizeLang(lang)]?.capitals?.get(lowerWord)

    fun capitalsFor(
        langs: List<String>,
        lowerWord: String,
    ): String? {
        for (lang in langs) {
            dictionaries[normalizeLang(lang)]?.capitals?.get(lowerWord)?.let { return it }
        }
        return null
    }

    fun seedBigramScore(
        langs: List<String>,
        prevLower: String,
        nextLower: String,
    ): Int {
        var best = 0
        for (lang in langs) {
            val score = dictionaries[normalizeLang(lang)]?.seedBigrams?.score(prevLower, nextLower) ?: 0
            if (score > best) best = score
        }
        return best
    }

    fun seedNextWords(
        langs: List<String>,
        prevLower: String,
        limit: Int = 6,
    ): List<String> {
        if (limit <= 0) return emptyList()
        val seen = LinkedHashSet<String>(limit * 2)
        val out = ArrayList<String>(limit)
        for (lang in langs) {
            val followers = dictionaries[normalizeLang(lang)]?.seedBigrams?.nextWords(prevLower, limit) ?: continue
            for (word in followers) {
                if (seen.add(word.lowercase())) {
                    out.add(word)
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    fun hasAnyDictionaryFor(langs: List<String>): Boolean = langs.any { dictionaries.containsKey(normalizeLang(it)) }

    fun contextModelFor(lang: String): WmlmModel? = contextModels[normalizeLang(lang)]?.model

    suspend fun importDictionary(
        languageId: String,
        inputStream: InputStream,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val lang = normalizeLang(languageId)
            val parsed = inputStream.use { WordlistParser.parseStream(it) }
            importedDir.mkdirs()
            val outFile = File(importedDir, "$lang.wmdict")
            val tmpFile = File(importedDir, "$lang.wmdict.tmp")
            PackedTrieCodec.write(parsed.trie, tmpFile)
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }

            val capsFile = File(importedDir, "${lang}_caps.txt")
            if (parsed.capitals.isNotEmpty()) {
                val sb = StringBuilder()
                for ((_, surface) in parsed.capitals) {
                    sb.append(surface).append('\n')
                }
                writeAtomic(capsFile, sb.toString())
            } else if (capsFile.exists()) {
                capsFile.delete()
            }

            loadSingleFromFile(lang, outFile, isBundled = false, capsOverride = parsed.capitals)
            ImportResult(
                languageId = lang,
                wordCount = parsed.trie.wordCount(),
                sizeBytes = outFile.length(),
            )
        }

    suspend fun deleteImportedDictionary(languageId: String): Boolean =
        withContext(Dispatchers.IO) {
            val lang = normalizeLang(languageId)
            val file = File(importedDir, "$lang.wmdict")
            val caps = File(importedDir, "${lang}_caps.txt")
            val deleted = if (file.exists()) file.delete() else false
            if (caps.exists()) caps.delete()
            dictionaries.remove(lang)
            BUNDLED_DICT_VERSIONS[lang]?.let { version ->
                loadBundledDictionary(lang, version)
            }
            deleted
        }

    suspend fun importContextModel(
        languageId: String,
        inputStream: InputStream,
    ): ContextModelInfo =
        withContext(Dispatchers.IO) {
            val lang = normalizeLang(languageId)
            val bytes = inputStream.use { it.readBytes() }
            val model = WmlmModel.open(bytes) ?: error("Invalid .wmlm context model")
            contextModelsDir.mkdirs()
            val outFile = File(contextModelsDir, "$lang.wmlm")
            val tmpFile = File(contextModelsDir, "$lang.wmlm.tmp")
            tmpFile.writeBytes(bytes)
            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }
            contextModels[lang] =
                LoadedContextModel(
                    model = model,
                    isBundled = lang == "en",
                    sizeBytes = outFile.length(),
                )
            ContextModelInfo(
                languageId = lang,
                name = model.name.ifBlank { lang },
                vocabSize = model.vocabSize,
                isBundled = lang == "en",
                isImported = true,
                sizeBytes = outFile.length(),
            )
        }

    suspend fun deleteImportedContextModel(languageId: String): Boolean =
        withContext(Dispatchers.IO) {
            val lang = normalizeLang(languageId)
            val file = File(contextModelsDir, "$lang.wmlm")
            val deleted = if (file.exists()) file.delete() else false
            contextModels.remove(lang)
            if (lang == "en" && contextModelsRequested) {
                loadBundledEnglishContextModel()
            }
            deleted
        }

    fun listInstalledDictionaries(): List<DictionaryInfo> {
        val result = ArrayList<DictionaryInfo>()
        for ((lang, loaded) in dictionaries.entries.sortedBy { it.key }) {
            val importedFile = File(importedDir, "$lang.wmdict")
            result.add(
                DictionaryInfo(
                    languageId = lang,
                    wordCount = loaded.source.wordCount(),
                    isBundled = loaded.isBundled,
                    isImported = importedFile.isFile,
                    sizeBytes = loaded.sizeBytes,
                ),
            )
        }
        return result
    }

    fun listInstalledContextModels(): List<ContextModelInfo> {
        val byLang = LinkedHashMap<String, ContextModelInfo>()
        // Always include bundled English context model metadata if running in app context
        if (appContext != null) {
            val loadedEn = contextModels["en"]
            val importedEn = File(contextModelsDir, "en.wmlm")
            byLang["en"] =
                ContextModelInfo(
                    languageId = "en",
                    name = loadedEn?.model?.name?.ifBlank { "English" } ?: "English",
                    vocabSize = loadedEn?.model?.vocabSize ?: BUNDLED_EN_LM_VOCAB,
                    isBundled = true,
                    isImported = importedEn.isFile,
                    sizeBytes = if (importedEn.isFile) importedEn.length() else BUNDLED_EN_LM_SIZE,
                )
        }
        for ((lang, loaded) in contextModels) {
            val importedFile = File(contextModelsDir, "$lang.wmlm")
            byLang[lang] =
                ContextModelInfo(
                    languageId = lang,
                    name = loaded.model.name.ifBlank { lang },
                    vocabSize = loaded.model.vocabSize,
                    isBundled = loaded.isBundled,
                    isImported = importedFile.isFile,
                    sizeBytes = loaded.sizeBytes,
                )
        }
        if (contextModelsDir.isDirectory) {
            val files = contextModelsDir.listFiles { f -> f.isFile && f.name.endsWith(".wmlm") }
            files?.forEach { file ->
                val lang = normalizeLang(file.name.removeSuffix(".wmlm"))
                if (!byLang.containsKey(lang)) {
                    byLang[lang] =
                        ContextModelInfo(
                            languageId = lang,
                            name = lang.uppercase(),
                            vocabSize = 0,
                            isBundled = lang == "en",
                            isImported = true,
                            sizeBytes = file.length(),
                        )
                }
            }
        }
        return byLang.values.sortedBy { it.languageId }
    }

    fun saveLearningDataAsync() {
        ioScope.launch {
            saveLearningDataBlocking()
        }
    }

    fun saveLearningDataBlocking() {
        userLexicon.saveIfDirty()
        correctionStats.saveIfDirty()
        correctionMemory.saveIfDirty()
    }

    suspend fun resetLearningData() =
        withContext(Dispatchers.IO) {
            userLexicon.clear()
            correctionStats.clear()
            correctionMemory.clear()
        }

    @Synchronized
    private fun loadAllInternal() {
        for ((lang, version) in BUNDLED_DICT_VERSIONS) {
            loadBundledDictionary(lang, version)
        }
        if (importedDir.isDirectory) {
            val files = importedDir.listFiles { f -> f.isFile && f.name.endsWith(".wmdict") }
            files?.forEach { file ->
                val lang = normalizeLang(file.name.removeSuffix(".wmdict"))
                if (PackedTrieCodec.isReadable(file)) {
                    loadSingleFromFile(lang, file, isBundled = false)
                }
            }
        }
        loadBundledEmojiSuggester()
        isReady = true
    }

    @Synchronized
    private fun loadContextModelsInternal() {
        val ctx = appContext ?: return
        if (!contextModelsRequested) return
        if (!contextModels.containsKey("en")) {
            loadBundledEnglishContextModel()
        }
        if (contextModelsDir.isDirectory) {
            val files = contextModelsDir.listFiles { f -> f.isFile && f.name.endsWith(".wmlm") }
            files?.forEach { file ->
                val lang = normalizeLang(file.name.removeSuffix(".wmlm"))
                if (!contextModelsRequested) return
                runCatching {
                    val bytes = file.readBytes()
                    WmlmModel.open(bytes)?.let { model ->
                        contextModels[lang] =
                            LoadedContextModel(
                                model = model,
                                isBundled = lang == "en",
                                sizeBytes = file.length(),
                            )
                    }
                }
            }
        }
        // Check again in case user toggled off while loading
        if (!contextModelsRequested) {
            contextModels.clear()
        }
    }

    private fun loadBundledEnglishContextModel() {
        val ctx = appContext ?: return
        runCatching {
            val bytes = ctx.assets.open("dictionaries/en_context.wmlm").use { it.readBytes() }
            WmlmModel.open(bytes)?.let { model ->
                contextModels["en"] =
                    LoadedContextModel(
                        model = model,
                        isBundled = true,
                        sizeBytes = bytes.size.toLong(),
                    )
            }
        }
    }

    private fun loadBundledEmojiSuggester() {
        val ctx = appContext ?: return
        runCatching {
            val triggers = runCatching { ctx.assets.open("emoji/triggers.tsv") }.getOrNull()
            val shortcodes = runCatching { ctx.assets.open("emoji/shortcodes.tsv") }.getOrNull()
            emojiSuggester =
                triggers.use { tStream ->
                    shortcodes.use { sStream ->
                        EmojiSuggester.load(tStream, sStream)
                    }
                }
        }
    }

    private fun loadBundledDictionary(
        lang: String,
        assetVersion: String,
    ) {
        val ctx = appContext ?: return
        val caps =
            runCatching {
                ctx.assets.open("dictionaries/${lang}_caps.txt").use { loadCapsStream(it) }
            }.getOrDefault(emptyMap())
        val bigrams =
            runCatching {
                ctx.assets.open("dictionaries/${lang}_bigrams.txt").use { SeedBigrams.load(it) }
            }.getOrDefault(SeedBigrams.EMPTY)

        try {
            bundledCacheDir.mkdirs()
            val cachedWmdict = File(bundledCacheDir, "$lang.wmdict")
            val versionFile = File(bundledCacheDir, ".${lang}_asset_version")
            val cachedMatches =
                cachedWmdict.isFile &&
                    PackedTrieCodec.isReadable(cachedWmdict) &&
                    versionFile.isFile &&
                    versionFile.readText().trim() == assetVersion

            if (!cachedMatches) {
                val tmp = File(bundledCacheDir, "$lang.wmdict.tmp")
                ctx.assets.open("dictionaries/$lang.wmdict").use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (!tmp.renameTo(cachedWmdict)) {
                    tmp.copyTo(cachedWmdict, overwrite = true)
                    tmp.delete()
                }
                versionFile.writeText(assetVersion)
            }
            val mapped = MappedTrie.open(cachedWmdict)
            dictionaries[lang] =
                LoadedDict(
                    source = mapped,
                    scale = computeRankScale(mapped),
                    capitals = caps,
                    seedBigrams = bigrams,
                    isBundled = true,
                    sizeBytes = cachedWmdict.length(),
                )
        } catch (_: Exception) {
            runCatching {
                val bytes = ctx.assets.open("dictionaries/$lang.wmdict").use { it.readBytes() }
                val mapped = MappedTrie.fromBytes(bytes)
                dictionaries[lang] =
                    LoadedDict(
                        source = mapped,
                        scale = computeRankScale(mapped),
                        capitals = caps,
                        seedBigrams = bigrams,
                        isBundled = true,
                        sizeBytes = bytes.size.toLong(),
                    )
            }
        }
    }

    private fun loadSingleFromFile(
        lang: String,
        file: File,
        isBundled: Boolean,
        capsOverride: Map<String, String>? = null,
    ) {
        val mapped =
            runCatching { MappedTrie.open(file) }.getOrNull() ?: return
        val existingBundled = dictionaries[lang]
        val caps =
            capsOverride ?: run {
                val capsFile = File(importedDir, "${lang}_caps.txt")
                if (capsFile.isFile) {
                    runCatching { capsFile.inputStream().use { loadCapsStream(it) } }.getOrDefault(emptyMap())
                } else {
                    existingBundled?.capitals ?: emptyMap()
                }
            }
        val bigrams = existingBundled?.seedBigrams ?: SeedBigrams.EMPTY
        dictionaries[lang] =
            LoadedDict(
                source = mapped,
                scale = computeRankScale(mapped),
                capitals = caps,
                seedBigrams = bigrams,
                isBundled = existingBundled?.isBundled == true || isBundled,
                sizeBytes = file.length(),
            )
    }

    private fun computeRankScale(source: WordSource): RankScale {
        val walker = source.walkers().maxByOrNull { it.maxSubtree(it.root) } ?: return RankScale.RAW
        val top = walker.maxSubtree(walker.root)
        val atAnchor = walker.countAtRank(RankScale.ANCHOR_RANK)
        return RankScale.forList(
            top = top,
            atAnchor = atAnchor,
            reference = REFERENCE_TOP_FREQUENCY,
            tolerance = SCALE_TOLERANCE,
        )
    }

    companion object {
        private val BUNDLED_DICT_VERSIONS =
            mapOf(
                "en" to "en_v1_75155",
                "es" to "es_v1_75000",
            )
        private const val BUNDLED_EN_LM_VOCAB = 24576
        private const val BUNDLED_EN_LM_SIZE = 10_801_380L
        private const val REFERENCE_TOP_FREQUENCY = 10_000.0
        private const val SCALE_TOLERANCE = 1.5

        fun normalizeLang(lang: String): String =
            lang
                .trim()
                .lowercase()
                .substringBefore('-')
                .substringBefore('_')

        fun loadCapsStream(input: InputStream): Map<String, String> {
            val map = HashMap<String, String>(256)
            input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachLine
                val surface = line.takeWhile { !it.isWhitespace() }
                if (surface.isNotEmpty()) {
                    val lower = surface.lowercase()
                    if (!map.containsKey(lower)) map[lower] = surface
                }
            }
            return map
        }
    }
}
