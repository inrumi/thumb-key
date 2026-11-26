package com.dessalines.thumbkey.autocorrect

/**
 * Mutable prefix trie used by [UserLexicon] for dynamic personal vocabulary.
 */
class Trie :
    TrieWalker,
    WordSource {
    private class Node {
        var children: MutableList<Edge> = ArrayList(2)
        var isWord: Boolean = false
        var freq: Int = 0
        var maxSub: Int = 0
        var wordCount: Int = 0
    }

    private class Edge(
        val ch: Char,
        val node: Node,
    )

    private val nodes =
        ArrayList<Node>().apply {
            add(Node()) // node 0 = root
        }

    private val idMap =
        HashMap<Node, Int>().apply {
            put(nodes[0], 0)
        }

    private val rankCache = RankFloorCache(this)
    private val completer = TrieCompleter(this)

    override val root: Int = 0

    fun insert(
        word: String,
        freq: Int,
    ) {
        if (word.isEmpty() || freq <= 0) return
        val path = ArrayList<Node>(word.length + 1)
        var cur = nodes[root]
        path.add(cur)
        for (i in 0 until word.length) {
            val ch = word[i]
            val idx = findChildIndex(cur.children, ch)
            val next =
                if (idx >= 0) {
                    cur.children[idx].node
                } else {
                    val created = Node()
                    val newId = nodes.size
                    nodes.add(created)
                    idMap[created] = newId
                    cur.children.add(-idx - 1, Edge(ch, created))
                    created
                }
            cur = next
            path.add(cur)
        }
        val wasWord = cur.isWord
        cur.isWord = true
        cur.freq = freq
        val deltaCount = if (wasWord) 0 else 1
        for (i in path.indices.reversed()) {
            val n = path[i]
            n.wordCount += deltaCount
            recomputeMax(n)
        }
    }

    fun remove(word: String): Boolean {
        if (word.isEmpty()) return false
        val path = ArrayList<Node>(word.length + 1)
        var cur = nodes[root]
        path.add(cur)
        for (i in 0 until word.length) {
            val idx = findChildIndex(cur.children, word[i])
            if (idx < 0) return false
            cur = cur.children[idx].node
            path.add(cur)
        }
        if (!cur.isWord) return false
        cur.isWord = false
        cur.freq = 0
        for (i in path.indices.reversed()) {
            val n = path[i]
            n.wordCount = (n.wordCount - 1).coerceAtLeast(0)
            recomputeMax(n)
        }
        return true
    }

    fun clear() {
        val rootNode = Node()
        nodes.clear()
        nodes.add(rootNode)
        idMap.clear()
        idMap[rootNode] = 0
    }

    override fun contains(word: CharSequence): Boolean {
        val node = walk(word)
        return node >= 0 && nodes[node].isWord
    }

    override fun frequencyOf(word: CharSequence): Int {
        val node = walk(word)
        return if (node >= 0 && nodes[node].isWord) nodes[node].freq else 0
    }

    override fun complete(
        prefix: CharSequence,
        limit: Int,
        includePrefix: Boolean,
    ): List<Suggestion> = completer.complete(prefix, limit, includePrefix)

    override fun walkers(): List<TrieWalker> = listOf(this)

    override fun isWord(node: Int): Boolean = node in 0 until nodes.size && nodes[node].isWord

    override fun frequency(node: Int): Int = if (node in 0 until nodes.size) nodes[node].freq else 0

    override fun maxSubtree(node: Int): Int = if (node in 0 until nodes.size) nodes[node].maxSub else 0

    override fun child(
        node: Int,
        ch: Char,
    ): Int {
        if (node !in 0 until nodes.size) return TrieWalker.NONE
        val idx = findChildIndex(nodes[node].children, ch)
        return if (idx >= 0) idMap[nodes[node].children[idx].node] ?: TrieWalker.NONE else TrieWalker.NONE
    }

    override fun childCount(node: Int): Int = if (node in 0 until nodes.size) nodes[node].children.size else 0

    override fun childAt(
        node: Int,
        index: Int,
    ): Int {
        val children = nodes[node].children
        return idMap[children[index].node] ?: TrieWalker.NONE
    }

    override fun childSymbol(
        node: Int,
        index: Int,
    ): Char = nodes[node].children[index].ch

    override fun subtreeWordCount(node: Int): Int = if (node in 0 until nodes.size) nodes[node].wordCount else 0

    override fun countAtRank(rank: Int): Int = rankCache.countAtRank(rank)

    private fun findChildIndex(
        children: List<Edge>,
        ch: Char,
    ): Int {
        var lo = 0
        var hi = children.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = children[mid].ch
            when {
                c < ch -> lo = mid + 1
                c > ch -> hi = mid - 1
                else -> return mid
            }
        }
        return -lo - 1
    }

    private fun recomputeMax(node: Node) {
        var best = if (node.isWord) node.freq else 0
        for (edge in node.children) {
            if (edge.node.maxSub > best) best = edge.node.maxSub
        }
        node.maxSub = best
    }
}
