package com.dessalines.thumbkey.autocorrect

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Memory-mapped reader for `.wmdict` v3 big-endian tries.
 *
 * Reads directly out of an mmap-backed `ByteBuffer` without inflating the
 * full dictionary onto the Dalvik/ART heap, while caching the symbol table
 * and top breadth-first trie levels for fast hot lookups.
 */
class MappedTrie private constructor(
    private val buffer: ByteBuffer,
    private val channel: FileChannel?,
    val wordCountValue: Int,
    val nodeCount: Int,
    val edgeCount: Int,
    private val symbols: CharArray?,
    private val useU8Degrees: Boolean,
    private val symbolsOffset: Int,
    private val childStartOffset: Int,
    private val checkpointOffset: Int,
    private val edgeLabelOffset: Int,
    private val freqOffset: Int,
    private val maxSubtreeOffset: Int,
    private val isWordOffset: Int,
) : TrieWalker,
    WordSource,
    Closeable {
    private val completer = TrieCompleter(this)
    private val rankCache = RankFloorCache(this)

    private val cachedCount = minOf(nodeCount, CACHE_NODES)
    private val childStartCache = IntArray(cachedCount)
    private val childCountCache = IntArray(cachedCount)
    private val maxSubtreeCache = IntArray(cachedCount)
    private val isWordCache = BooleanArray(cachedCount)

    init {
        for (i in 0 until cachedCount) {
            childStartCache[i] = computeStartEdgeOf(i)
            childCountCache[i] = computeChildCount(i)
            maxSubtreeCache[i] = computeMaxSubtree(i)
            isWordCache[i] = computeIsWord(i)
        }
    }

    override val root: Int = 0

    override fun wordCount(): Int = wordCountValue

    private fun computeIsWord(node: Int): Boolean = ((buffer.get(isWordOffset + (node ushr 3)).toInt() ushr (node and 7)) and 1) != 0

    override fun isWord(node: Int): Boolean {
        if (node !in 0 until nodeCount) return false
        return if (node < cachedCount) isWordCache[node] else computeIsWord(node)
    }

    override fun frequency(node: Int): Int {
        if (node !in 0 until nodeCount || !isWord(node)) return 0
        val code = buffer.getShort(freqOffset + (node shl 1)).toInt() and 0xFFFF
        return FrequencyCodec.decode(code)
    }

    private fun computeMaxSubtree(node: Int): Int {
        val code = buffer.getShort(maxSubtreeOffset + (node shl 1)).toInt() and 0xFFFF
        return FrequencyCodec.decode(code)
    }

    override fun maxSubtree(node: Int): Int {
        if (node !in 0 until nodeCount) return 0
        return if (node < cachedCount) maxSubtreeCache[node] else computeMaxSubtree(node)
    }

    private fun computeChildCount(node: Int): Int =
        if (useU8Degrees) {
            buffer.get(childStartOffset + node).toInt() and 0xFF
        } else {
            val start = buffer.getInt(childStartOffset + (node shl 2))
            val end = buffer.getInt(childStartOffset + ((node + 1) shl 2))
            end - start
        }

    override fun childCount(node: Int): Int {
        if (node !in 0 until nodeCount) return 0
        return if (node < cachedCount) childCountCache[node] else computeChildCount(node)
    }

    private fun computeStartEdgeOf(node: Int): Int {
        if (!useU8Degrees) {
            return buffer.getInt(childStartOffset + (node shl 2))
        }
        val block = node ushr PackedTrie.CHECKPOINT_SHIFT
        var start = buffer.getInt(checkpointOffset + (block shl 2))
        val base = block shl PackedTrie.CHECKPOINT_SHIFT
        for (k in base until node) {
            start += buffer.get(childStartOffset + k).toInt() and 0xFF
        }
        return start
    }

    private fun startEdgeOf(node: Int): Int = if (node < cachedCount) childStartCache[node] else computeStartEdgeOf(node)

    private fun edgeSymbol(edgeIdx: Int): Char {
        val syms = symbols
        return if (syms != null) {
            syms[buffer.get(edgeLabelOffset + edgeIdx).toInt() and 0xFF]
        } else {
            buffer.getChar(edgeLabelOffset + (edgeIdx shl 1))
        }
    }

    override fun child(
        node: Int,
        ch: Char,
    ): Int {
        if (node !in 0 until nodeCount) return TrieWalker.NONE
        val start = startEdgeOf(node)
        val count = childCount(node)
        var lo = start
        var hi = start + count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = edgeSymbol(mid)
            when {
                c < ch -> lo = mid + 1
                c > ch -> hi = mid - 1
                else -> return mid + 1
            }
        }
        return TrieWalker.NONE
    }

    override fun childAt(
        node: Int,
        index: Int,
    ): Int = startEdgeOf(node) + index + 1

    override fun childSymbol(
        node: Int,
        index: Int,
    ): Char = edgeSymbol(startEdgeOf(node) + index)

    override fun children(
        node: Int,
        buf: ChildBuffer,
    ): Int {
        if (node !in 0 until nodeCount) return 0
        val start = startEdgeOf(node)
        val count = childCount(node)
        buf.ensureCapacity(count)
        for (i in 0 until count) {
            val edgeIdx = start + i
            buf.symbols[i] = edgeSymbol(edgeIdx)
            buf.nodes[i] = edgeIdx + 1
        }
        return count
    }

    override fun subtreeWordCount(node: Int): Int {
        if (node !in 0 until nodeCount) return 0
        if (node == root) return wordCountValue
        var total = 0
        val stack = ArrayDeque<Int>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (isWord(cur)) total++
            val start = startEdgeOf(cur)
            val count = childCount(cur)
            for (i in 0 until count) {
                stack.addLast(start + i + 1)
            }
        }
        return total
    }

    override fun countAtRank(rank: Int): Int = rankCache.countAtRank(rank)

    override fun contains(word: CharSequence): Boolean {
        val node = walk(word)
        return node >= 0 && isWord(node)
    }

    override fun frequencyOf(word: CharSequence): Int {
        val node = walk(word)
        return if (node >= 0 && isWord(node)) frequency(node) else 0
    }

    override fun complete(
        prefix: CharSequence,
        limit: Int,
        includePrefix: Boolean,
    ): List<Suggestion> = completer.complete(prefix, limit, includePrefix)

    override fun walkers(): List<TrieWalker> = listOf(this)

    fun toPackedTrie(): PackedTrie {
        val syms = symbols?.copyOf() ?: CharArray(0)
        val childStart =
            if (useU8Degrees) {
                null
            } else {
                IntArray(nodeCount + 1) { idx -> buffer.getInt(childStartOffset + (idx shl 2)) }
            }
        val degs =
            if (useU8Degrees) {
                ByteArray(nodeCount) { idx -> buffer.get(childStartOffset + idx) }
            } else {
                null
            }
        val cp =
            if (useU8Degrees) {
                val count = (nodeCount ushr PackedTrie.CHECKPOINT_SHIFT) + 1
                IntArray(count) { idx -> buffer.getInt(checkpointOffset + (idx shl 2)) }
            } else {
                null
            }
        val u8 =
            if (symbols != null) {
                ByteArray(edgeCount) { idx -> buffer.get(edgeLabelOffset + idx) }
            } else {
                null
            }
        val u16 =
            if (symbols != null) {
                null
            } else {
                CharArray(edgeCount) { idx -> buffer.getChar(edgeLabelOffset + (idx shl 1)) }
            }
        val freqs = ShortArray(nodeCount) { idx -> buffer.getShort(freqOffset + (idx shl 1)) }
        val maxSubs = ShortArray(nodeCount) { idx -> buffer.getShort(maxSubtreeOffset + (idx shl 1)) }
        val bitsetLen = (nodeCount + 7) ushr 3
        val wordBytes = ByteArray(bitsetLen) { idx -> buffer.get(isWordOffset + idx) }
        return PackedTrie(
            wordCountValue = wordCountValue,
            nodeCount = nodeCount,
            edgeCount = edgeCount,
            symbols = syms,
            childStart = childStart,
            degreeU8 = degs,
            checkpoint = cp,
            edgeLabelU16 = u16,
            edgeLabelU8 = u8,
            freqCodes = freqs,
            maxSubtreeCodes = maxSubs,
            isWordBytes = wordBytes,
        )
    }

    override fun close() {
        channel?.close()
    }

    companion object {
        private const val CACHE_NODES = 1024

        fun open(file: File): MappedTrie {
            val channel = FileChannel.open(file.toPath())
            return try {
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
                fromBuffer(mapped.order(ByteOrder.BIG_ENDIAN), channel)
            } catch (t: Throwable) {
                channel.close()
                throw t
            }
        }

        fun fromBytes(bytes: ByteArray): MappedTrie {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return fromBuffer(bb, null)
        }

        private fun fromBuffer(
            bb: ByteBuffer,
            channel: FileChannel?,
        ): MappedTrie {
            require(bb.capacity() >= PackedTrieCodec.HEADER_BYTES) { "Truncated .wmdict header" }
            val magic = bb.getInt(0)
            require(magic == PackedTrieCodec.MAGIC) { "Invalid .wmdict magic: 0x${magic.toString(16)}" }
            val version = bb.getShort(4).toInt() and 0xFFFF
            require(version == PackedTrieCodec.VERSION) { "Unsupported .wmdict version: $version" }
            val flags = bb.getShort(6).toInt() and 0xFFFF
            val wordCount = bb.getInt(8)
            val nodeCount = bb.getInt(12)
            val edgeCount = bb.getInt(16)
            val symbolCount = bb.getInt(20)
            require(nodeCount > 0 && edgeCount == nodeCount - 1) {
                "Corrupt .wmdict counts: nodes=$nodeCount edges=$edgeCount"
            }
            val offSymbols = bb.getInt(24)
            val offChildStart = bb.getInt(28)
            val offCheckpoint = bb.getInt(32)
            val offEdgeLabel = bb.getInt(36)
            val offFreq = bb.getInt(40)
            val offMaxSubtree = bb.getInt(44)
            val offIsWord = bb.getInt(48)

            val useU8Labels = (flags and PackedTrieCodec.FLAG_LABELS_U8) != 0
            val useU8Degrees = (flags and PackedTrieCodec.FLAG_DEGREE_U8) != 0
            val syms =
                if (useU8Labels) {
                    require(symbolCount <= PackedTrie.MAX_SYMBOLS) { "Invalid U8 symbolCount: $symbolCount" }
                    CharArray(symbolCount) { idx -> bb.getChar(offSymbols + (idx shl 1)) }
                } else {
                    null
                }
            val bitsetBytes = (nodeCount + 7) ushr 3
            require(offIsWord + bitsetBytes <= bb.capacity()) { "Truncated .wmdict body" }

            return MappedTrie(
                buffer = bb,
                channel = channel,
                wordCountValue = wordCount,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                symbols = syms,
                useU8Degrees = useU8Degrees,
                symbolsOffset = offSymbols,
                childStartOffset = offChildStart,
                checkpointOffset = offCheckpoint,
                edgeLabelOffset = offEdgeLabel,
                freqOffset = offFreq,
                maxSubtreeOffset = offMaxSubtree,
                isWordOffset = offIsWord,
            )
        }
    }
}
