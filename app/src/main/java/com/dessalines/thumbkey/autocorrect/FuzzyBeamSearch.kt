package com.dessalines.thumbkey.autocorrect

import java.util.PriorityQueue
import kotlin.math.ln

/**
 * Pure-Kotlin best-first fuzzy trie beam search modeled on `wmkeyboard`'s
 * `FuzzyBeamSearch` / `fuzzy.rs`.
 */
object FuzzyBeamSearch {
    val COST_TRANSPOSITION = -ln(0.9)
    val COST_SUB_ADJACENT = -ln(0.9)
    val COST_DELETE_DOUBLED = -ln(0.9)
    val COST_ACCENT = -ln(0.9)
    val COST_SUB_RING = -ln(0.85)
    val COST_DELETION = -ln(0.7)
    val COST_INSERT_ADJACENT = -ln(0.7)
    val COST_SUB_NEAR_CELL = -ln(0.55)
    val COST_SUB_NEAR = -ln(0.45)
    val COST_INSERT_FAR = -ln(0.25)
    val COST_SUB_FAR = -ln(0.2)
    val COST_COMPLETION_AFTER_EDIT = -ln(0.25)

    const val MAX_EDIT_COST = 2.0
    const val SECOND_EDIT_SURCHARGE = 2.0
    const val MAX_POPS = 4096
    const val AUTOCORRECT_K = 8

    fun defaultMaxEdits(len: Int): Int = if (len >= 5) 2 else 1

    data class WalkSource(
        val walker: TrieWalker,
        val logWeight: Double = 0.0,
        val rankWeight: Double = 1.0,
        val scale: RankScale = RankScale.RAW,
        val isUser: Boolean = false,
        val isDictionary: Boolean = true,
    )

    data class Candidate(
        val word: String,
        val score: Double,
        val frequency: Int,
        val editCost: Double,
        val edits: Int,
        val slips: Int,
        val accents: Int,
        val completed: Int,
        val fromDictionary: Boolean,
        val fromUser: Boolean,
        val mixesSlips: Boolean,
    )

    /**
     * Reusable primitive workspace so fuzzy walks do minimal allocations per word.
     */
    class BeamWorkspace {
        var parentIdx = IntArray(512)
        var edgeChar = CharArray(512)
        var charDepth = IntArray(512)
        var size = 0

        val childBuf = ChildBuffer()
        val pq =
            PriorityQueue<BeamNode>(256) { a, b ->
                b.upperScore.compareTo(a.upperScore)
            }
        val visited = HashMap<Long, Double>(256)

        fun reset() {
            size = 0
            pq.clear()
            visited.clear()
        }

        fun pushChar(
            parent: Int,
            ch: Char,
        ): Int {
            if (size >= parentIdx.size) {
                val cap = parentIdx.size * 2
                parentIdx = parentIdx.copyOf(cap)
                edgeChar = edgeChar.copyOf(cap)
                charDepth = charDepth.copyOf(cap)
            }
            val idx = size++
            parentIdx[idx] = parent
            edgeChar[idx] = ch
            charDepth[idx] = if (parent < 0) 1 else charDepth[parent] + 1
            return idx
        }

        fun buildWord(handle: Int): String {
            if (handle < 0) return ""
            val len = charDepth[handle]
            val chars = CharArray(len)
            var cur = handle
            var i = len - 1
            while (cur >= 0 && i >= 0) {
                chars[i--] = edgeChar[cur]
                cur = parentIdx[cur]
            }
            return String(chars)
        }
    }

    class BeamNode(
        val sourceIdx: Int,
        val node: Int,
        val pos: Int,
        val edits: Int,
        val slips: Int,
        val accents: Int,
        val completed: Int,
        val editCost: Double,
        val upperScore: Double,
        val pathHandle: Int,
        val hasFarOp: Boolean,
    )

    fun search(
        typed: String,
        sources: List<WalkSource>,
        proximity: KeyboardProximity = KeyboardProximity.NONE,
        maxEdits: Int = defaultMaxEdits(typed.length),
        maxEditCost: Double = MAX_EDIT_COST,
        allowAccents: Boolean = true,
        limit: Int = AUTOCORRECT_K,
        workspace: BeamWorkspace = BeamWorkspace(),
    ): List<Candidate> {
        if (typed.isEmpty() || sources.isEmpty() || limit <= 0) return emptyList()
        workspace.reset()
        val pq = workspace.pq
        val visited = workspace.visited
        val buf = workspace.childBuf
        val n = typed.length
        val lower = typed.lowercase()

        for (srcIdx in sources.indices) {
            val src = sources[srcIdx]
            val maxSub = src.walker.maxSubtree(src.walker.root)
            if (maxSub <= 0) continue
            val upper = src.logWeight + src.rankWeight * src.scale.ln1p(maxSub)
            pq.add(
                BeamNode(
                    sourceIdx = srcIdx,
                    node = src.walker.root,
                    pos = 0,
                    edits = 0,
                    slips = 0,
                    accents = 0,
                    completed = 0,
                    editCost = 0.0,
                    upperScore = upper,
                    pathHandle = -1,
                    hasFarOp = false,
                ),
            )
        }

        val bestByWord = HashMap<String, Candidate>(limit * 2)
        var thresholdScore = Double.NEGATIVE_INFINITY
        var pops = 0

        while (pq.isNotEmpty() && pops < MAX_POPS) {
            val cur = pq.poll() ?: break
            if (bestByWord.size >= limit && cur.upperScore <= thresholdScore) break
            pops++

            val src = sources[cur.sourceIdx]
            val walker = src.walker

            if (cur.pos >= n && walker.isWord(cur.node)) {
                val freq = walker.frequency(cur.node)
                if (freq > 0) {
                    val word = workspace.buildWord(cur.pathHandle)
                    if (word.isNotEmpty()) {
                        val surcharge = if (cur.edits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                        val score =
                            src.logWeight + src.rankWeight * src.scale.ln1p(freq) -
                                cur.editCost - surcharge
                        val mixes = cur.slips > 0 && cur.hasFarOp
                        val existing = bestByWord[word]
                        if (existing == null) {
                            bestByWord[word] =
                                Candidate(
                                    word = word,
                                    score = score,
                                    frequency = freq,
                                    editCost = cur.editCost,
                                    edits = cur.edits,
                                    slips = cur.slips,
                                    accents = cur.accents,
                                    completed = cur.completed,
                                    fromDictionary = src.isDictionary,
                                    fromUser = src.isUser,
                                    mixesSlips = mixes,
                                )
                            thresholdScore = recomputeCutoff(bestByWord.values, limit)
                        } else {
                            val mergedDict = existing.fromDictionary || src.isDictionary
                            val mergedUser = existing.fromUser || src.isUser
                            bestByWord[word] =
                                if (score > existing.score) {
                                    Candidate(
                                        word = word,
                                        score = score,
                                        frequency = maxOf(freq, existing.frequency),
                                        editCost = cur.editCost,
                                        edits = cur.edits,
                                        slips = cur.slips,
                                        accents = cur.accents,
                                        completed = cur.completed,
                                        fromDictionary = mergedDict,
                                        fromUser = mergedUser,
                                        mixesSlips = mixes,
                                    )
                                } else {
                                    existing.copy(
                                        fromDictionary = mergedDict,
                                        fromUser = mergedUser,
                                    )
                                }
                            thresholdScore = recomputeCutoff(bestByWord.values, limit)
                        }
                    }
                }
            }

            fun enqueueIfBetter(next: BeamNode) {
                if (bestByWord.size >= limit && next.upperScore <= thresholdScore) return
                val key = visitKey(cur.sourceIdx, next.edits, next.node, next.pos)
                val prevCost = visited[key]
                if (prevCost != null && prevCost <= next.editCost + 1e-9) return
                visited[key] = next.editCost
                pq.add(next)
            }

            if (cur.pos < n) {
                val target = lower[cur.pos]
                val deg = walker.children(cur.node, buf)
                for (i in 0 until deg) {
                    val ch = buf.symbols[i]
                    val child = buf.nodes[i]
                    val childMax = walker.maxSubtree(child)
                    if (childMax <= 0) continue

                    // 1. Match / Accent / Substitution advancing both trie & input
                    val nextPos = cur.pos + 1
                    val isExact = ch == target
                    val isAccent =
                        !isExact &&
                            allowAccents &&
                            Accents.foldChar(ch) == Accents.foldChar(target)

                    val stepCost: Double
                    val nextEdits: Int
                    val nextSlips: Int
                    val nextAccents: Int
                    val nextFar: Boolean

                    when {
                        isExact -> {
                            stepCost = 0.0
                            nextEdits = cur.edits
                            nextSlips = cur.slips
                            nextAccents = cur.accents
                            nextFar = cur.hasFarOp
                        }

                        isAccent -> {
                            stepCost = COST_ACCENT
                            nextEdits = cur.edits
                            nextSlips = cur.slips
                            nextAccents = cur.accents + 1
                            nextFar = cur.hasFarOp
                        }

                        else -> {
                            val subCost = proximity.substituteCost(target, ch)
                            val nearSlip =
                                proximity.distanceFactor(target, ch) <= KeyboardProximity.ADJACENT_CELL
                            stepCost = subCost
                            nextEdits = cur.edits + 1
                            nextSlips = cur.slips + if (nearSlip) 1 else 0
                            nextAccents = cur.accents
                            nextFar = cur.hasFarOp || !nearSlip
                        }
                    }

                    val nextCost = cur.editCost + stepCost
                    if (nextEdits <= maxEdits && nextAccents <= MAX_ACCENTS && nextCost <= maxEditCost) {
                        val surcharge = if (nextEdits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                        val upper = src.logWeight + src.rankWeight * src.scale.ln1p(childMax) - nextCost - surcharge
                        enqueueIfBetter(
                            BeamNode(
                                sourceIdx = cur.sourceIdx,
                                node = child,
                                pos = nextPos,
                                edits = nextEdits,
                                slips = nextSlips,
                                accents = nextAccents,
                                completed = cur.completed,
                                editCost = nextCost,
                                upperScore = upper,
                                pathHandle = workspace.pushChar(cur.pathHandle, ch),
                                hasFarOp = nextFar,
                            ),
                        )
                    }

                    // 2. Insertion (omitted char in typed word): advance trie only
                    if (cur.edits + 1 <= maxEdits) {
                        val adj =
                            proximity.isAdjacent(target, ch) ||
                                (cur.pos > 0 && proximity.isAdjacent(lower[cur.pos - 1], ch))
                        val insCost = if (adj) COST_INSERT_ADJACENT else COST_INSERT_FAR
                        val newCost = cur.editCost + insCost
                        if (newCost <= maxEditCost) {
                            val newEdits = cur.edits + 1
                            val surcharge = if (newEdits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                            val upper = src.logWeight + src.rankWeight * src.scale.ln1p(childMax) - newCost - surcharge
                            enqueueIfBetter(
                                BeamNode(
                                    sourceIdx = cur.sourceIdx,
                                    node = child,
                                    pos = cur.pos,
                                    edits = newEdits,
                                    slips = cur.slips + if (adj) 1 else 0,
                                    accents = cur.accents,
                                    completed = cur.completed,
                                    editCost = newCost,
                                    upperScore = upper,
                                    pathHandle = workspace.pushChar(cur.pathHandle, ch),
                                    hasFarOp = cur.hasFarOp || !adj,
                                ),
                            )
                        }
                    }
                }

                // 3. Deletion (stray char in typed word): advance input only
                if (cur.edits + 1 <= maxEdits) {
                    val isDoubled =
                        (cur.pos > 0 && lower[cur.pos] == lower[cur.pos - 1]) ||
                            (cur.pos + 1 < n && lower[cur.pos] == lower[cur.pos + 1])
                    val delCost = if (isDoubled) COST_DELETE_DOUBLED else COST_DELETION
                    val newCost = cur.editCost + delCost
                    if (newCost <= maxEditCost) {
                        val newEdits = cur.edits + 1
                        val surcharge = if (newEdits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                        val subMax = walker.maxSubtree(cur.node)
                        val upper = src.logWeight + src.rankWeight * src.scale.ln1p(subMax) - newCost - surcharge
                        enqueueIfBetter(
                            BeamNode(
                                sourceIdx = cur.sourceIdx,
                                node = cur.node,
                                pos = cur.pos + 1,
                                edits = newEdits,
                                slips = cur.slips + if (isDoubled) 1 else 0,
                                accents = cur.accents,
                                completed = cur.completed,
                                editCost = newCost,
                                upperScore = upper,
                                pathHandle = cur.pathHandle,
                                hasFarOp = cur.hasFarOp || !isDoubled,
                            ),
                        )
                    }
                }

                // 4. Transposition of two adjacent input characters
                if (cur.pos + 1 < n &&
                    lower[cur.pos] != lower[cur.pos + 1] &&
                    cur.edits + 1 <= maxEdits
                ) {
                    val newCost = cur.editCost + COST_TRANSPOSITION
                    if (newCost <= maxEditCost) {
                        val c1 = walker.child(cur.node, lower[cur.pos + 1])
                        if (c1 != TrieWalker.NONE) {
                            val c2 = walker.child(c1, lower[cur.pos])
                            if (c2 != TrieWalker.NONE) {
                                val subMax = walker.maxSubtree(c2)
                                if (subMax > 0) {
                                    val newEdits = cur.edits + 1
                                    val surcharge = if (newEdits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                                    val upper = src.logWeight + src.rankWeight * src.scale.ln1p(subMax) - newCost - surcharge
                                    val h1 = workspace.pushChar(cur.pathHandle, lower[cur.pos + 1])
                                    val h2 = workspace.pushChar(h1, lower[cur.pos])
                                    enqueueIfBetter(
                                        BeamNode(
                                            sourceIdx = cur.sourceIdx,
                                            node = c2,
                                            pos = cur.pos + 2,
                                            edits = newEdits,
                                            slips = cur.slips + 1,
                                            accents = cur.accents,
                                            completed = cur.completed,
                                            editCost = newCost,
                                            upperScore = upper,
                                            pathHandle = h2,
                                            hasFarOp = cur.hasFarOp,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (cur.completed < MAX_COMPLETION_CHARS && (cur.edits > 0 || cur.completed == 0)) {
                // Allow 1-2 omitted trailing characters when still within edit budget
                if (cur.edits + 1 <= maxEdits) {
                    val newCost = cur.editCost + COST_COMPLETION_AFTER_EDIT
                    if (newCost <= maxEditCost) {
                        val deg = walker.children(cur.node, buf)
                        for (i in 0 until deg) {
                            val ch = buf.symbols[i]
                            val child = buf.nodes[i]
                            val childMax = walker.maxSubtree(child)
                            if (childMax <= 0) continue
                            val newEdits = cur.edits + 1
                            val surcharge = if (newEdits >= 2) SECOND_EDIT_SURCHARGE else 0.0
                            val upper =
                                src.logWeight + src.rankWeight * src.scale.ln1p(childMax) - newCost - surcharge
                            enqueueIfBetter(
                                BeamNode(
                                    sourceIdx = cur.sourceIdx,
                                    node = child,
                                    pos = cur.pos,
                                    edits = newEdits,
                                    slips = cur.slips,
                                    accents = cur.accents,
                                    completed = cur.completed + 1,
                                    editCost = newCost,
                                    upperScore = upper,
                                    pathHandle = workspace.pushChar(cur.pathHandle, ch),
                                    hasFarOp = true,
                                ),
                            )
                        }
                    }
                }
            }
        }

        return bestByWord.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.editCost }.thenBy { it.word })
            .take(limit)
    }

    private fun recomputeCutoff(
        candidates: Collection<Candidate>,
        limit: Int,
    ): Double {
        if (candidates.size < limit) return Double.NEGATIVE_INFINITY
        return candidates
            .asSequence()
            .sortedByDescending { it.score }
            .elementAt(limit - 1)
            .score
    }

    private fun visitKey(
        srcIdx: Int,
        edits: Int,
        node: Int,
        pos: Int,
    ): Long =
        ((srcIdx.toLong() and 0xFFL) shl 56) or
            ((edits.coerceAtMost(7).toLong() and 0x7L) shl 53) or
            ((pos.toLong() and 0x1FFFL) shl 40) or
            (node.toLong() and 0xFFFFFFFFFFL)

    private const val MAX_COMPLETION_CHARS = 2
    private const val MAX_ACCENTS = 4
}
