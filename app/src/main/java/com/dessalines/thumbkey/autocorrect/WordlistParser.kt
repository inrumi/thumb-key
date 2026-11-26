package com.dessalines.thumbkey.autocorrect

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Parses user-supplied dictionaries into a [PackedTrie] and optional display
 * capitalization map.
 *
 * Supported formats:
 * - `.wmdict` binary trie (checked by magic bytes `WMDC`)
 * - `.txt.gz` / gzipped text wordlist (checked by gzip magic `0x1F 0x8B`)
 * - Plain text wordlists:
 *   - AOSP `wordlist.combined`: ` word=the,f=248,...`
 *   - Frequency table (`word freq` or `word\tfreq`)
 *   - Bare words (one per line; descending synthetic rank assigned)
 */
object WordlistParser {
    data class ParsedWordlist(
        val trie: PackedTrie,
        val capitals: Map<String, String> = emptyMap(),
    )

    private const val MAX_WORD_LENGTH = 48
    private const val MIN_GZIP_HEADER = 2
    private const val AOSP_BOOST = 250

    fun parseStream(input: InputStream): ParsedWordlist {
        val buffered = input.buffered()
        buffered.mark(64)
        val probe = ByteArray(16)
        val n = buffered.read(probe)
        buffered.reset()

        if (n >= 8 && isWmdictMagic(probe)) {
            return ParsedWordlist(PackedTrieCodec.read(buffered))
        }

        val textStream =
            if (n >= MIN_GZIP_HEADER && (probe[0].toInt() and 0xFF) == 0x1F && (probe[1].toInt() and 0xFF) == 0x8B) {
                GZIPInputStream(buffered)
            } else {
                buffered
            }
        return parseText(BufferedReader(InputStreamReader(textStream, Charsets.UTF_8)))
    }

    fun parseText(reader: BufferedReader): ParsedWordlist {
        val entries = LinkedHashMap<String, Int>(4096)
        val capitals = LinkedHashMap<String, String>(512)
        var lineIndex = 0
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachLine
            if (line.startsWith("dictionary=")) return@forEachLine

            val parsed = parseLine(line, lineIndex++)
            if (parsed != null) {
                val (surface, freq) = parsed
                val lower = surface.lowercase()
                if (lower.isEmpty() || lower.length > MAX_WORD_LENGTH) return@forEachLine
                if (!lower.any { it.isLetter() }) return@forEachLine
                val prev = entries[lower]
                if (prev == null || freq > prev) {
                    entries[lower] = freq
                }
                if (surface != lower && !capitals.containsKey(lower)) {
                    capitals[lower] = surface
                }
            }
        }
        return ParsedWordlist(
            trie = PackedTrie.of(entries.entries.map { it.key to it.value }),
            capitals = capitals,
        )
    }

    private fun parseLine(
        line: String,
        lineIndex: Int,
    ): Pair<String, Int>? {
        if (line.startsWith("word=")) {
            return parseAospEntry(line, lineIndex)
        }
        val sepIdx = indexOfSeparator(line)
        if (sepIdx < 0) {
            return line to syntheticFreq(lineIndex)
        }
        val word = line.substring(0, sepIdx).trim()
        if (word.isEmpty()) return null
        val rest = line.substring(sepIdx + 1).trim()
        val firstToken = rest.takeWhile { !it.isWhitespace() }
        val freq = firstToken.toIntOrNull()?.coerceAtLeast(1) ?: syntheticFreq(lineIndex)
        return word to freq
    }

    private fun parseAospEntry(
        line: String,
        lineIndex: Int,
    ): Pair<String, Int>? {
        var word: String? = null
        var rawFreq = -1
        for (part in line.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq).trim()
            val v = part.substring(eq + 1).trim()
            when (k) {
                "word" -> word = v
                "f" -> rawFreq = v.toIntOrNull() ?: -1
            }
        }
        val cleaned = word ?: return null
        val freq =
            if (rawFreq > 0) {
                rawFreq.coerceAtLeast(1) * AOSP_BOOST
            } else {
                syntheticFreq(lineIndex)
            }
        return cleaned to freq
    }

    private fun indexOfSeparator(line: String): Int {
        for (i in line.indices) {
            val c = line[i]
            if (c == '\t' || c == ' ' || c == ',') return i
        }
        return -1
    }

    private fun syntheticFreq(lineIndex: Int): Int = (50_000 - (lineIndex / 4)).coerceAtLeast(50)

    private fun isWmdictMagic(probe: ByteArray): Boolean {
        val m = PackedTrieCodec.MAGIC
        return (probe[0].toInt() and 0xFF) == ((m ushr 24) and 0xFF) &&
            (probe[1].toInt() and 0xFF) == ((m ushr 16) and 0xFF) &&
            (probe[2].toInt() and 0xFF) == ((m ushr 8) and 0xFF) &&
            (probe[3].toInt() and 0xFF) == (m and 0xFF)
    }
}
