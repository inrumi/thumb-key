package com.dessalines.thumbkey.autocorrect

import java.io.InputStream

/**
 * Immutable baseline bigrams loaded from bundled assets (`en_bigrams.txt`),
 * used to rank missing-space word splits alongside personal [UserLexicon] bigrams.
 */
class SeedBigrams(
    private val map: Map<String, Map<String, Int>>,
    private val sortedFollowers: Map<String, List<String>> =
        map.mapValues { (_, inner) ->
            inner.entries.sortedByDescending { it.value }.map { it.key }
        },
) {
    fun score(
        prevLower: String,
        nextLower: String,
    ): Int = map[prevLower]?.get(nextLower) ?: 0

    fun nextWords(
        prevLower: String,
        limit: Int = 6,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return sortedFollowers[prevLower]?.take(limit) ?: emptyList()
    }

    companion object {
        val EMPTY = SeedBigrams(emptyMap(), emptyMap())

        fun load(input: InputStream): SeedBigrams {
            val outerScore = HashMap<String, MutableMap<String, Int>>(256)
            val outerDisplay = HashMap<String, MutableMap<String, Int>>(256)
            input.bufferedReader(Charsets.UTF_8).forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachLine
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 3) return@forEachLine
                val prevRaw = parts[0]
                val prev = if (prevRaw == "<s>") prevRaw else prevRaw.lowercase()
                val nextSurface = parts[1]
                val nextLower = nextSurface.lowercase()
                val freq = parts[2].toIntOrNull()?.coerceAtLeast(1) ?: return@forEachLine

                val scoreInner = outerScore.getOrPut(prev) { HashMap(8) }
                val existingScore = scoreInner[nextLower]
                if (existingScore == null || freq > existingScore) {
                    scoreInner[nextLower] = freq
                }

                val dispInner = outerDisplay.getOrPut(prev) { HashMap(8) }
                val existingDisp = dispInner[nextSurface]
                if (existingDisp == null || freq > existingDisp) {
                    dispInner[nextSurface] = freq
                }
            }
            val sorted =
                outerDisplay.mapValues { (_, inner) ->
                    inner.entries.sortedByDescending { it.value }.map { it.key }
                }
            return SeedBigrams(outerScore, sorted)
        }
    }
}
