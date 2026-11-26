package com.dessalines.thumbkey.autocorrect

import java.io.File

/**
 * Personal dictionary & bigram store persisted atomically on device.
 *
 * Unknown words must be seen [PROMOTE_AT] times in staging (or explicitly
 * reverted via Backspace undo) before entering the active [Trie], preventing
 * single-use typos from poisoning autocorrect.
 */
class UserLexicon(
    private val storageFile: File?,
) {
    private val trie = Trie()
    private val words = HashMap<String, Int>()
    private val stagingCounts = HashMap<String, Int>()
    private val wordCase = HashMap<String, String>()
    private val caseVotes = HashMap<String, Int>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val wordGen = HashMap<String, Long>()
    private var generation = 0L
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun wordSource(): WordSource = trie

    @Synchronized
    fun containsWord(wordLower: String): Boolean = words.containsKey(wordLower)

    @Synchronized
    fun frequencyOf(wordLower: String): Int = words[wordLower] ?: 0

    @Synchronized
    fun preferredCase(wordLower: String): String? = wordCase[wordLower]

    @Synchronized
    fun bigramCount(
        prevLower: String,
        nextLower: String,
    ): Int = bigrams[prevLower]?.get(nextLower) ?: 0

    @Synchronized
    fun nextWords(
        prevLower: String,
        limit: Int = 6,
    ): List<Pair<String, Int>> {
        if (limit <= 0) return emptyList()
        val followers = bigrams[prevLower] ?: return emptyList()
        return followers.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
    }

    @Synchronized
    fun wordCount(): Int = words.size

    @Synchronized
    fun observeWord(
        surface: String,
        knownInDictionary: Boolean,
        caseEvidence: Boolean = false,
        boost: Int = 1,
    ): Boolean {
        val key = surface.lowercase()
        if (!isValidLearnToken(key)) return false

        if (caseEvidence) {
            voteCaseLocked(key, surface)
        }

        val currentFreq = words[key]
        if (currentFreq != null) {
            words[key] = (currentFreq + boost).coerceAtMost(MAX_WORD_FREQ)
            wordGen[key] = generation
            trie.insert(key, words[key]!!)
            dirty = true
            return true
        }

        if (knownInDictionary) {
            words[key] = boost.coerceAtLeast(1) * 5
            wordGen[key] = generation
            trie.insert(key, words[key]!!)
            enforceWordCapLocked()
            dirty = true
            return true
        }

        val nextStage = (stagingCounts[key] ?: 0) + boost
        if (nextStage >= PROMOTE_AT) {
            stagingCounts.remove(key)
            val freq = PROMOTED_BASE_FREQ
            words[key] = freq
            wordGen[key] = generation
            trie.insert(key, freq)
            enforceWordCapLocked()
            dirty = true
            return true
        } else {
            stagingCounts[key] = nextStage
            dirty = true
            return false
        }
    }

    @Synchronized
    fun promoteRevertedWord(surface: String) {
        observeWord(
            surface = surface,
            knownInDictionary = false,
            caseEvidence = WordCase.startsUpperCase(surface),
            boost = REVERT_BOOST,
        )
    }

    @Synchronized
    fun recordBigram(
        prevLower: String,
        nextLower: String,
    ) {
        if (!isValidLearnToken(prevLower) || !isValidLearnToken(nextLower)) return
        val inner = bigrams.getOrPut(prevLower) { HashMap(4) }
        inner[nextLower] = ((inner[nextLower] ?: 0) + 1).coerceAtMost(MAX_BIGRAM_FREQ)
        if (inner.size > MAX_FOLLOWERS) {
            val victim = inner.entries.minByOrNull { it.value }?.key
            if (victim != null) inner.remove(victim)
        }
        if (bigrams.size > MAX_BIGRAM_PARENTS) {
            val oldestParent = bigrams.keys.firstOrNull { it != prevLower }
            if (oldestParent != null) bigrams.remove(oldestParent)
        }
        dirty = true
    }

    @Synchronized
    fun clear() {
        trie.clear()
        words.clear()
        stagingCounts.clear()
        wordCase.clear()
        caseVotes.clear()
        bigrams.clear()
        wordGen.clear()
        generation = 0L
        dirty = false
        storageFile?.let { file ->
            if (file.exists()) file.delete()
        }
    }

    @Synchronized
    fun saveIfDirty() {
        if (!dirty || storageFile == null) return
        generation++
        val rootObj = LinkedHashMap<String, Any?>(8)
        rootObj["generation"] = generation
        rootObj["words"] = words.toMap()
        rootObj["staging"] = stagingCounts.toMap()
        rootObj["wordCase"] = wordCase.toMap()
        rootObj["caseVotes"] = caseVotes.toMap()
        rootObj["wordGen"] = wordGen.toMap()
        rootObj["bigrams"] = bigrams.mapValues { it.value.toMap() }
        writeAtomic(storageFile, SimpleJson.write(rootObj))
        dirty = false
    }

    @Synchronized
    fun load() {
        val file = storageFile ?: return
        if (!file.isFile) return
        val parsed =
            try {
                SimpleJson.parseObject(file.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                null
            } ?: return

        trie.clear()
        words.clear()
        stagingCounts.clear()
        wordCase.clear()
        caseVotes.clear()
        bigrams.clear()
        wordGen.clear()

        generation = parsed["generation"].asLong(0L)
        for ((k, v) in parsed["words"].asStringAnyMap()) {
            val freq = v.asInt(0)
            if (freq > 0) {
                words[k] = freq
                trie.insert(k, freq)
            }
        }
        for ((k, v) in parsed["staging"].asStringAnyMap()) {
            val c = v.asInt(0)
            if (c > 0) stagingCounts[k] = c
        }
        for ((k, v) in parsed["wordCase"].asStringAnyMap()) {
            if (v is String && v.isNotEmpty()) wordCase[k] = v
        }
        for ((k, v) in parsed["caseVotes"].asStringAnyMap()) {
            val c = v.asInt(0)
            if (c > 0) caseVotes[k] = c
        }
        for ((k, v) in parsed["wordGen"].asStringAnyMap()) {
            wordGen[k] = v.asLong(generation)
        }
        for ((prev, followersObj) in parsed["bigrams"].asStringAnyMap()) {
            val inner = HashMap<String, Int>()
            for ((next, countObj) in followersObj.asStringAnyMap()) {
                val c = countObj.asInt(0)
                if (c > 0) inner[next] = c
            }
            if (inner.isNotEmpty()) bigrams[prev] = inner
        }
        dirty = false
    }

    private fun voteCaseLocked(
        key: String,
        surface: String,
    ) {
        if (!WordCase.startsUpperCase(surface)) return
        val prev = wordCase[key]
        if (prev == null) {
            wordCase[key] = surface
            caseVotes[key] = 1
        } else if (prev == surface) {
            caseVotes[key] = ((caseVotes[key] ?: 0) + 1).coerceAtMost(50)
        } else {
            val votes = (caseVotes[key] ?: 1) - 1
            if (votes <= 0) {
                wordCase[key] = surface
                caseVotes[key] = 1
            } else {
                caseVotes[key] = votes
            }
        }
        dirty = true
    }

    private fun enforceWordCapLocked() {
        while (words.size > MAX_WORDS) {
            val victim =
                words.keys.minWithOrNull(
                    compareBy<String> { wordGen[it] ?: 0L }.thenBy { words[it] ?: 0 },
                ) ?: break
            words.remove(victim)
            wordGen.remove(victim)
            wordCase.remove(victim)
            caseVotes.remove(victim)
            trie.remove(victim)
        }
    }

    private fun isValidLearnToken(token: String): Boolean {
        if (token.isEmpty() || token.length > MAX_WORD_LENGTH) return false
        var letters = 0
        for (i in token.indices) {
            val c = token[i]
            when {
                c.isLetter() -> {
                    letters++
                }

                c == '\'' || c == '’' -> {
                    if (i == 0 || i == token.lastIndex) return false
                }

                else -> {
                    return false
                }
            }
        }
        return letters > 0
    }

    companion object {
        const val PROMOTE_AT = 3
        const val REVERT_BOOST = 2
        const val PROMOTED_BASE_FREQ = 200
        const val MAX_WORDS = 5000
        const val MAX_WORD_LENGTH = 36
        const val MAX_FOLLOWERS = 32
        const val MAX_BIGRAM_PARENTS = 1500
        const val MAX_WORD_FREQ = 50_000
        const val MAX_BIGRAM_FREQ = 10_000
    }
}

internal fun writeAtomic(
    target: File,
    content: String,
) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(content, Charsets.UTF_8)
    if (!tmp.renameTo(target)) {
        target.writeText(content, Charsets.UTF_8)
        tmp.delete()
    }
}
