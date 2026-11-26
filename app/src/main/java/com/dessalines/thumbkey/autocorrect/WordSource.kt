package com.dessalines.thumbkey.autocorrect

import java.util.PriorityQueue
import kotlin.math.ln

data class Suggestion(
    val word: String,
    val frequency: Int,
    val fromUser: Boolean = false,
)

/**
 * Reusable buffer filled by [TrieWalker.children] to avoid per-node allocation
 * during best-first trie searches.
 */
class ChildBuffer {
    var symbols: CharArray = CharArray(32)
        private set
    var nodes: IntArray = IntArray(32)
        private set

    fun ensureCapacity(needed: Int) {
        if (needed <= symbols.size) return
        val newCap = maxOf(needed, symbols.size * 2)
        symbols = symbols.copyOf(newCap)
        nodes = nodes.copyOf(newCap)
    }
}

/**
 * Low-level cursor over a prefix trie keyed by opaque integer node handles.
 */
interface TrieWalker {
    val root: Int

    fun isWord(node: Int): Boolean

    fun frequency(node: Int): Int

    fun maxSubtree(node: Int): Int

    fun child(
        node: Int,
        ch: Char,
    ): Int

    fun childCount(node: Int): Int

    fun childAt(
        node: Int,
        index: Int,
    ): Int

    fun childSymbol(
        node: Int,
        index: Int,
    ): Char

    fun children(
        node: Int,
        buf: ChildBuffer,
    ): Int {
        val count = childCount(node)
        buf.ensureCapacity(count)
        for (i in 0 until count) {
            buf.symbols[i] = childSymbol(node, i)
            buf.nodes[i] = childAt(node, i)
        }
        return count
    }

    fun subtreeWordCount(node: Int = root): Int

    fun countAtRank(rank: Int): Int

    fun walk(word: CharSequence): Int {
        var cur = root
        for (i in 0 until word.length) {
            cur = child(cur, word[i])
            if (cur == NONE) return NONE
        }
        return cur
    }

    companion object {
        const val NONE = -1
    }
}

/**
 * Word dictionary queried by the fuzzy searcher and autocorrect engine.
 */
interface WordSource {
    fun contains(word: CharSequence): Boolean

    fun frequencyOf(word: CharSequence): Int

    fun complete(
        prefix: CharSequence,
        limit: Int = 8,
        includePrefix: Boolean = true,
    ): List<Suggestion>

    fun walkers(): List<TrieWalker>

    fun wordCount(): Int = walkers().sumOf { it.subtreeWordCount() }
}

/**
 * Lazy top-k frequency oracle using best-first search over [TrieWalker.maxSubtree]
 * so [RankScale.ANCHOR_RANK] (2000) lookups finish in microseconds on large tries.
 */
class RankFloorCache(
    private val walker: TrieWalker,
) {
    private data class Candidate(
        val node: Int,
        val score: Int,
        val isTerminal: Boolean,
    ) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int = other.score.compareTo(score)
    }

    private val cachedTop = ArrayList<Int>(256)
    private var maxComputed = false

    @Synchronized
    fun countAtRank(rank: Int): Int {
        if (rank <= 0) return 0
        if (maxComputed || cachedTop.size >= rank) {
            return if (rank <= cachedTop.size) cachedTop[rank - 1] else 0
        }
        val rootMax = walker.maxSubtree(walker.root)
        if (rootMax <= 0) {
            maxComputed = true
            return 0
        }
        val target = maxOf(rank, DEFAULT_PRECOMPUTE_RANKS).coerceAtMost(MAX_CACHED_RANKS)
        val pq = PriorityQueue<Candidate>(128)
        pq.add(Candidate(walker.root, rootMax, false))
        val results = ArrayList<Int>(target)
        val buf = ChildBuffer()
        while (pq.isNotEmpty() && results.size < target) {
            val cur = pq.poll() ?: break
            if (cur.isTerminal) {
                results.add(cur.score)
                continue
            }
            if (walker.isWord(cur.node)) {
                val ownFreq = walker.frequency(cur.node)
                if (ownFreq > 0) {
                    pq.add(Candidate(cur.node, ownFreq, true))
                }
            }
            val n = walker.children(cur.node, buf)
            for (i in 0 until n) {
                val child = buf.nodes[i]
                val maxSub = walker.maxSubtree(child)
                if (maxSub > 0) {
                    pq.add(Candidate(child, maxSub, false))
                }
            }
        }
        cachedTop.clear()
        cachedTop.addAll(results)
        if (pq.isEmpty()) maxComputed = true
        return if (rank <= cachedTop.size) cachedTop[rank - 1] else 0
    }

    companion object {
        private const val DEFAULT_PRECOMPUTE_RANKS = 2048
        private const val MAX_CACHED_RANKS = 4096
    }
}

/**
 * Best-first prefix completion using [TrieWalker.maxSubtree] bounds.
 */
class TrieCompleter(
    private val walker: TrieWalker,
) {
    private data class Candidate(
        val node: Int,
        val suffix: String,
        val bound: Int,
        val isTerminalEntry: Boolean,
    ) : Comparable<Candidate> {
        override fun compareTo(other: Candidate): Int = other.bound.compareTo(bound)
    }

    fun complete(
        prefix: CharSequence,
        limit: Int = 8,
        includePrefix: Boolean = true,
    ): List<Suggestion> {
        if (limit <= 0) return emptyList()
        val startNode = walker.walk(prefix)
        if (startNode == TrieWalker.NONE) return emptyList()
        val prefixStr = prefix.toString()
        val pq = PriorityQueue<Candidate>(32)
        val out = ArrayList<Suggestion>(limit)
        val buf = ChildBuffer()

        if (includePrefix && walker.isWord(startNode)) {
            val f = walker.frequency(startNode)
            if (f > 0) out.add(Suggestion(prefixStr, f))
        }
        val nStart = walker.children(startNode, buf)
        for (i in 0 until nStart) {
            val cNode = buf.nodes[i]
            val bound = walker.maxSubtree(cNode)
            if (bound > 0) {
                pq.add(Candidate(cNode, buf.symbols[i].toString(), bound, false))
            }
        }

        while (pq.isNotEmpty() && out.size < limit) {
            val cur = pq.poll() ?: break
            if (cur.isTerminalEntry) {
                out.add(Suggestion(prefixStr + cur.suffix, cur.bound))
                continue
            }
            if (walker.isWord(cur.node)) {
                val f = walker.frequency(cur.node)
                if (f > 0) {
                    pq.add(Candidate(cur.node, cur.suffix, f, true))
                }
            }
            val deg = walker.children(cur.node, buf)
            for (i in 0 until deg) {
                val cNode = buf.nodes[i]
                val bound = walker.maxSubtree(cNode)
                if (bound > 0) {
                    pq.add(Candidate(cNode, cur.suffix + buf.symbols[i], bound, false))
                }
            }
        }
        return out
    }
}

/**
 * Matches external corpus/dictionary counts onto the bundled reference scale
 * used by [FuzzyBeamSearch] and [Confidence] (`wmkeyboard`'s `RankScale`).
 */
class RankScale(
    val floor: Double,
    val perNat: Double,
) {
    val isRaw: Boolean get() = perNat <= 0.0

    fun read(frequency: Int): Double {
        val f = frequency.toDouble()
        return when {
            isRaw -> f
            f < floor -> f / floor
            else -> 1.0 + perNat * ln(f / floor)
        }
    }

    fun ln1p(frequency: Int): Double = ln(1.0 + read(frequency))

    override fun equals(other: Any?): Boolean = this === other || (other is RankScale && other.floor == floor && other.perNat == perNat)

    override fun hashCode(): Int = 31 * floor.hashCode() + perNat.hashCode()

    companion object {
        val RAW = RankScale(0.0, 0.0)
        const val DYNAMIC_RANGE = 28_787.591
        const val CORPUS_ANCHOR_SHARE = 0.05
        const val ANCHOR_RANK = 2_000
        const val REFERENCE_AT_ANCHOR = 3_077.0

        fun forList(
            top: Int,
            atAnchor: Int,
            reference: Double,
            tolerance: Double,
        ): RankScale =
            when {
                top > reference * tolerance -> anchored(top, atAnchor, reference)
                atAnchor > 0 && atAnchor < top * CORPUS_ANCHOR_SHARE -> anchored(top, atAnchor, reference)
                else -> RAW
            }

        fun logLine(
            top: Int,
            reference: Double,
        ): RankScale {
            if (top <= 0) return RAW
            return RankScale(top / DYNAMIC_RANGE, (reference - 1.0) / ln(DYNAMIC_RANGE))
        }

        fun anchored(
            top: Int,
            atAnchor: Int,
            reference: Double,
        ): RankScale {
            if (top <= 0) return RAW
            val line = logLine(top, reference)
            if (atAnchor <= 0 || atAnchor >= top) return line
            val perNat =
                ((reference - REFERENCE_AT_ANCHOR) / ln(top.toDouble() / atAnchor))
                    .coerceIn(line.perNat / 2.0, line.perNat * 2.0)
            return RankScale(top * StrictMath.pow(Math.E, -(reference - 1.0) / perNat), perNat)
        }
    }
}
