package com.dessalines.thumbkey.autocorrect

/**
 * Compact BFS-ordered array-backed static prefix trie compatible with `.wmdict` v3.
 *
 * Node `0` is the root; every edge index `e` points directly to node `e + 1`,
 * eliminating explicit child pointers.
 */
class PackedTrie internal constructor(
    internal val wordCountValue: Int,
    internal val nodeCount: Int,
    internal val edgeCount: Int,
    internal val symbols: CharArray,
    internal val childStart: IntArray?,
    internal val degreeU8: ByteArray?,
    internal val checkpoint: IntArray?,
    internal val edgeLabelU16: CharArray?,
    internal val edgeLabelU8: ByteArray?,
    internal val freqCodes: ShortArray,
    internal val maxSubtreeCodes: ShortArray,
    internal val isWordBytes: ByteArray,
) : TrieWalker,
    WordSource {
    private val completer = TrieCompleter(this)
    private val rankCache = RankFloorCache(this)

    override val root: Int = 0

    override fun wordCount(): Int = wordCountValue

    override fun isWord(node: Int): Boolean {
        if (node !in 0 until nodeCount) return false
        return ((isWordBytes[node ushr 3].toInt() ushr (node and 7)) and 1) != 0
    }

    override fun frequency(node: Int): Int {
        if (node !in 0 until nodeCount || !isWord(node)) return 0
        return FrequencyCodec.decode(freqCodes[node].toInt() and 0xFFFF)
    }

    override fun maxSubtree(node: Int): Int {
        if (node !in 0 until nodeCount) return 0
        return FrequencyCodec.decode(maxSubtreeCodes[node].toInt() and 0xFFFF)
    }

    override fun childCount(node: Int): Int {
        if (node !in 0 until nodeCount) return 0
        val degs = degreeU8
        return if (degs != null) {
            degs[node].toInt() and 0xFF
        } else {
            val cs = childStart ?: return 0
            cs[node + 1] - cs[node]
        }
    }

    private fun startEdgeOf(node: Int): Int {
        val degs = degreeU8
        if (degs != null) {
            val cp = checkpoint ?: return 0
            val block = node ushr CHECKPOINT_SHIFT
            var start = cp[block]
            val base = block shl CHECKPOINT_SHIFT
            for (k in base until node) {
                start += degs[k].toInt() and 0xFF
            }
            return start
        }
        return childStart?.get(node) ?: 0
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
        val u8 = edgeLabelU8
        val u16 = edgeLabelU16
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c =
                if (u8 != null) {
                    symbols[u8[mid].toInt() and 0xFF]
                } else {
                    u16!![mid]
                }
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
    ): Char {
        val edgeIdx = startEdgeOf(node) + index
        val u8 = edgeLabelU8
        return if (u8 != null) symbols[u8[edgeIdx].toInt() and 0xFF] else edgeLabelU16!![edgeIdx]
    }

    override fun children(
        node: Int,
        buf: ChildBuffer,
    ): Int {
        if (node !in 0 until nodeCount) return 0
        val start = startEdgeOf(node)
        val count = childCount(node)
        buf.ensureCapacity(count)
        val u8 = edgeLabelU8
        val u16 = edgeLabelU16
        for (i in 0 until count) {
            val edgeIdx = start + i
            buf.symbols[i] = if (u8 != null) symbols[u8[edgeIdx].toInt() and 0xFF] else u16!![edgeIdx]
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

    companion object {
        internal const val CHECKPOINT_STRIDE = 32
        internal const val CHECKPOINT_SHIFT = 5
        internal const val MAX_SYMBOLS = 256
        internal const val MAX_DEGREE = 255

        val EMPTY: PackedTrie = of(emptyList())

        fun of(entries: Iterable<Pair<String, Int>>): PackedTrie {
            val dedup = HashMap<String, Int>()
            for ((rawWord, rawFreq) in entries) {
                if (rawWord.isEmpty() || rawFreq <= 0) continue
                val existing = dedup[rawWord]
                if (existing == null || rawFreq > existing) {
                    dedup[rawWord] = rawFreq
                }
            }
            if (dedup.isEmpty()) {
                return PackedTrie(
                    wordCountValue = 0,
                    nodeCount = 1,
                    edgeCount = 0,
                    symbols = CharArray(0),
                    childStart = null,
                    degreeU8 = ByteArray(1),
                    checkpoint = intArrayOf(0),
                    edgeLabelU16 = null,
                    edgeLabelU8 = ByteArray(0),
                    freqCodes = ShortArray(1),
                    maxSubtreeCodes = ShortArray(1),
                    isWordBytes = ByteArray(1),
                )
            }

            class BuildNode {
                var children = ArrayList<Pair<Char, BuildNode>>(2)
                var isWord = false
                var freqCode = 0
                var maxCode = 0

                fun findChild(ch: Char): Int {
                    var lo = 0
                    var hi = children.size - 1
                    while (lo <= hi) {
                        val mid = (lo + hi) ushr 1
                        val c = children[mid].first
                        when {
                            c < ch -> lo = mid + 1
                            c > ch -> hi = mid - 1
                            else -> return mid
                        }
                    }
                    return -lo - 1
                }
            }

            val rootNode = BuildNode()
            val distinctChars = HashSet<Char>()
            var totalNodes = 1
            for ((word, freq) in dedup) {
                var cur = rootNode
                for (i in 0 until word.length) {
                    val ch = word[i]
                    distinctChars.add(ch)
                    val idx = cur.findChild(ch)
                    cur =
                        if (idx >= 0) {
                            cur.children[idx].second
                        } else {
                            val child = BuildNode()
                            cur.children.add(-idx - 1, ch to child)
                            totalNodes++
                            child
                        }
                }
                cur.isWord = true
                cur.freqCode = FrequencyCodec.encode(freq)
            }

            // BFS numbering: edge i targets node i + 1
            val bfsOrder = ArrayList<BuildNode>(totalNodes)
            bfsOrder.add(rootNode)
            var maxDegree = 0
            var cursor = 0
            while (cursor < bfsOrder.size) {
                val node = bfsOrder[cursor++]
                if (node.children.size > maxDegree) maxDegree = node.children.size
                for ((_, child) in node.children) {
                    bfsOrder.add(child)
                }
            }

            for (i in bfsOrder.lastIndex downTo 0) {
                val node = bfsOrder[i]
                var m = if (node.isWord) node.freqCode else 0
                for ((_, child) in node.children) {
                    if (child.maxCode > m) m = child.maxCode
                }
                node.maxCode = m
            }

            val nodeCount = bfsOrder.size
            val edgeCount = nodeCount - 1
            val useU8Degrees = maxDegree <= MAX_DEGREE
            val useU8Labels = distinctChars.size <= MAX_SYMBOLS

            val symbolTable: CharArray
            val symbolIndex = HashMap<Char, Int>()
            if (useU8Labels) {
                symbolTable = distinctChars.toCharArray().apply { sort() }
                for (i in symbolTable.indices) {
                    symbolIndex[symbolTable[i]] = i
                }
            } else {
                symbolTable = CharArray(0)
            }

            val childStart = if (useU8Degrees) null else IntArray(nodeCount + 1)
            val degreeU8 = if (useU8Degrees) ByteArray(nodeCount) else null
            val checkpoint = if (useU8Degrees) IntArray((nodeCount ushr CHECKPOINT_SHIFT) + 1) else null
            val edgeLabelU8 = if (useU8Labels) ByteArray(edgeCount) else null
            val edgeLabelU16 = if (useU8Labels) null else CharArray(edgeCount)
            val freqCodes = ShortArray(nodeCount)
            val maxSubtreeCodes = ShortArray(nodeCount)
            val isWordBytes = ByteArray((nodeCount + 7) ushr 3)

            var edgeCursor = 0
            for (id in 0 until nodeCount) {
                val node = bfsOrder[id]
                val deg = node.children.size
                if (useU8Degrees) {
                    if ((id and (CHECKPOINT_STRIDE - 1)) == 0) {
                        checkpoint!![id ushr CHECKPOINT_SHIFT] = edgeCursor
                    }
                    degreeU8!![id] = (deg and 0xFF).toByte()
                } else {
                    childStart!![id] = edgeCursor
                }
                for ((ch, _) in node.children) {
                    if (useU8Labels) {
                        edgeLabelU8!![edgeCursor] = (symbolIndex[ch]!! and 0xFF).toByte()
                    } else {
                        edgeLabelU16!![edgeCursor] = ch
                    }
                    edgeCursor++
                }
                freqCodes[id] = node.freqCode.toShort()
                maxSubtreeCodes[id] = node.maxCode.toShort()
                if (node.isWord) {
                    val bIdx = id ushr 3
                    isWordBytes[bIdx] = (isWordBytes[bIdx].toInt() or (1 shl (id and 7))).toByte()
                }
            }
            if (!useU8Degrees) {
                childStart!![nodeCount] = edgeCursor
            } else if ((nodeCount and (CHECKPOINT_STRIDE - 1)) == 0) {
                checkpoint!![nodeCount ushr CHECKPOINT_SHIFT] = edgeCursor
            }

            return PackedTrie(
                wordCountValue = dedup.size,
                nodeCount = nodeCount,
                edgeCount = edgeCount,
                symbols = symbolTable,
                childStart = childStart,
                degreeU8 = degreeU8,
                checkpoint = checkpoint,
                edgeLabelU16 = edgeLabelU16,
                edgeLabelU8 = edgeLabelU8,
                freqCodes = freqCodes,
                maxSubtreeCodes = maxSubtreeCodes,
                isWordBytes = isWordBytes,
            )
        }
    }
}
